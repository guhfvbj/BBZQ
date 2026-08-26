# 番剧外挂字幕不显示问题分析

**日期**: 2026-08-26  
**状态**: 调查中  
**问题**: 播放恢复正常，但外挂字幕不生效

---

## 问题现象

### 客户端日志
```
Bangumi DmView fallback: source=moss, region=TW, 
  requestBytes=77, responseBytes=699, subtitles=false, 
  rawPid=986355236, effectivePid=679957, rawCid=859778302, cid=859778302, augmented=true
```

**关键信息**:
- ✅ 播放正常（视频可以加载）
- ❌ `subtitles=false` - 响应中没有字幕数据
- ✅ `augmented=true` - 请求已被增强处理
- ✅ `source=moss` - 通过MOSS内部处理

### 服务器日志
```json
{
  "action": "BBZQ gRPC透传",
  "route": "dm",
  "method": "DmSegMobile",
  "request_bytes": 76,
  "response_status": 200,
  "response_bytes": 198
}
```

**关键信息**:
- ✅ 只有`DmSegMobile`请求（分段弹幕）
- ❌ **没有`DmView`请求到达服务器**
- ✅ Resin代理工作正常

---

## 根本原因

### 1. 客户端使用MOSS内部处理

从日志`source=moss`可以看出，客户端没有将DmView请求发送到解析服务器，而是在MOSS模块内部处理。

### 2. DmView响应没有字幕数据

客户端代码 `BangumiDmViewFallback.kt` 第114行：
```kotlin
if (methodName == "dmView" && !attempt.hasSubtitle) return@repeat
```

**这行代码会拒绝没有字幕的DmView响应**，继续等待其他区域的响应，直到超时。

### 3. 字幕数据的判断逻辑

`BangumiSubtitleModel.kt` 第12-15行：
```kotlin
fun hasSubtitleTrack(raw: ByteArray): Boolean = runCatching {
    val reply = DmViewReply.parseFrom(raw)
    reply.hasSubtitle() && reply.subtitle.subtitlesCount > 0
}.getOrDefault(false)
```

检查protobuf `DmViewReply`中是否包含`subtitle`字段和字幕轨道。

### 4. DmView type字段要求

`BangumiDmViewFallback.kt` 第140-146行注释：
```kotlin
// DmView silently omits the subtitle block when type is absent. The
// host often sends a zero-valued request for unlocked PGC playback,
// so normalize it before forwarding to the regional endpoint.
if (request.type == 0) {
    builder.setType(1)  // ← 关键：必须设置type=1才会返回字幕
    changed = true
}
```

**关键发现**：Bilibili的DmView API只有在`request.type=1`时才会返回字幕数据！

---

## 问题定位

### 可能的原因

1. **MOSS发送的请求type字段不正确**
   - 虽然客户端代码会将`type=0`修正为`type=1`（第143-145行）
   - 但如果MOSS直接发送到Bilibili而不经过这个修正，字幕就会丢失

2. **服务器gRPC透传没有修正type字段**
   - 服务器代码只是简单透传gRPC请求
   - 没有检查或修正`type`字段
   - 如果客户端发送的请求`type=0`，服务器不会修正

3. **Bilibili API本身不返回字幕**
   - 即使type=1，某些区域或内容可能确实没有字幕
   - 需要验证Bilibili官方API是否真的有字幕数据

---

## 调试步骤

### 1. 验证MOSS是否发送请求到服务器

从日志来看，**DmView请求没有到达服务器**。这说明MOSS在本地处理了DmView请求。

问题：MOSS是直接请求Bilibili API，还是应该通过解析服务器？

### 2. 检查DmView请求的type字段

需要抓包或添加日志，查看：
- 客户端发送的DmView请求中`type`字段的值
- 服务器收到的请求中`type`字段的值（如果有）
- Bilibili返回的响应中是否包含subtitle字段

### 3. 验证服务器gRPC端点

服务器有gRPC透传功能：
- 路径：`/api/legacy/grpc/dm/DmView`
- 代码：`pages/api/legacy/grpc/[...path].ts` 第90-124行
- 使用`resinGrpcRequest`通过Resin代理

注释说明（第92-94行）：
```typescript
// Bilibili only includes the regional subtitle tracks for authenticated
// DmView requests. Keep the app identity and gRPC metadata intact; Resin
// proxy credentials are handled separately by resinGrpcRequest.
```

**服务器代码知道需要认证的DmView请求才会有字幕**。

---

## 可能的解决方案

### 方案1: 修改客户端MOSS逻辑（推荐）

**问题**：MOSS本地处理DmView时没有正确获取字幕

**解决**：
1. 确保MOSS将DmView请求发送到解析服务器而不是直接请求Bilibili
2. 或者让MOSS在请求Bilibili时正确设置type=1并包含认证信息

### 方案2: 服务器端修正type字段

如果客户端发送请求到服务器，服务器可以在转发前修正type字段：

修改 `pages/api/legacy/grpc/[...path].ts`:

```typescript
if (routeKey === "dm" && routeName === "DmView") {
  // Ensure type=1 for subtitle support
  const augmentedBody = augmentDmViewRequest(body);
  const finalBody = augmentedBody || body;
  
  const response = await resinGrpcRequest(
    target,
    req.method || "POST",
    upstreamHeaders,
    finalBody,
  );
  // ... rest of code
}

function augmentDmViewRequest(body: Uint8Array): Uint8Array | null {
  // Parse protobuf and set type=1 if type=0
  // Similar to client's augmentDmViewRequest logic
}
```

### 方案3: 使用独立字幕API

服务器有独立的字幕API端点：
- 路径：`/api/legacy/intl/gateway/v2/app/subtitle`
- 代码：`pages/api/legacy/intl/gateway/v2/app/subtitle.ts`

客户端可以：
1. 先获取DmView（不要求字幕）
2. 单独请求字幕API
3. 使用`BangumiSubtitleModel.mergeSubtitleTrack`合并字幕到DmView响应

---

## 关键代码位置

### 客户端
- **DmView fallback逻辑**: `app/src/main/java/io/github/bbzq/feats/bangumi/BangumiDmViewFallback.kt`
  - 第114行：拒绝没有字幕的响应
  - 第140-146行：修正type字段
  - 第94-95行：检查是否有字幕

- **字幕模型**: `app/src/main/java/io/github/bbzq/feats/bangumi/BangumiSubtitleModel.kt`
  - 第12-15行：hasSubtitleTrack检查
  - 第17-26行：mergeSubtitleTrack合并字幕

### 服务器
- **gRPC透传**: `pages/api/legacy/grpc/[...path].ts`
  - 第90-124行：dm路由处理
  - 第92-94行：认证DmView注释

- **gRPC请求**: `src/utils/resin-grpc.ts`
  - 第90-136行：resinGrpcRequest实现
  - 通过Resin建立HTTP/2连接

- **字幕API**: `pages/api/legacy/intl/gateway/v2/app/subtitle.ts`
  - 第14-30行：独立字幕获取

---

## 下一步行动

### 立即验证

1. **添加服务器日志**：在gRPC透传代码中添加日志，打印DmView请求的详细信息
   ```typescript
   if (routeKey === "dm" && routeName === "DmView") {
     console.log("DmView request:", Buffer.from(body).toString('base64'));
   }
   ```

2. **抓包验证**：使用Wireshark或mitmproxy抓取gRPC请求，查看：
   - 请求是否到达服务器
   - type字段的值
   - 响应中是否有subtitle字段

3. **测试独立字幕API**：手动请求字幕API，验证服务器能否获取字幕：
   ```bash
   curl "http://YOUR_SERVER:3102/api/legacy/intl/gateway/v2/app/subtitle?ep_id=679957&cid=859778302"
   ```

### 修复优先级

1. **高优先级**：确认MOSS是否应该发送DmView到服务器
   - 如果应该：修复MOSS路由逻辑
   - 如果不应该：修复MOSS本地DmView请求的type字段

2. **中优先级**：服务器端添加type字段修正
   - 防御性编程，即使客户端发送type=0也能正常获取字幕

3. **低优先级**：使用独立字幕API
   - 作为fallback方案
   - 需要客户端代码修改

---

## 参考资料

- Bilibili DmView protobuf定义
- BiliRoaming字幕注入实现
- BBZQ MOSS架构文档

## 时间线

- 14:40 - 验证播放恢复正常
- 15:00 - 发现字幕不显示问题
- 15:10 - 分析客户端和服务器日志
- 15:30 - 定位到MOSS fallback逻辑
- 15:45 - 发现type字段要求
- 16:00 - 编写分析文档
