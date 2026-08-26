# 国际服搜索修复文档

**日期**: 2026-08-20  
**问题**: 国际版番剧搜索不全，例如"死神 千年血战篇-祸进谭"等国际版独占内容搜索不到  
**修复版本**: 20260820-151700_bbzq-intl-search-fix-v2

## 问题分析

### 问题根源

在 `/pages/api/legacy/intl/gateway/v2/app/search/type.ts` 中，搜索路由逻辑存在问题：

**修复前的逻辑**：
```typescript
const upstream = query.get("type") === "7" || query.get("type") === "8"
  ? `${mainApi}/x/v2/search/type${...}`  // 主站API
  : intlApi + req.url;                     // 国际版API
```

这导致：
- type=7（番剧港澳台）→ 主站API ✅ 正确
- type=8（影视国际）→ 主站API ❌ **错误**

**问题**：type=8 被强制路由到主站API（`app.bilibili.com`），但主站API不包含国际版独占内容。国际版独占番剧只能通过国际版API（`app.biliintl.com`）搜索到。

### 客户端搜索类型

BBZQ Android应用添加了两个区域搜索分类：
- **番剧（港澳台）** - 客户端type `1919` → 服务器type `7`
- **影视（国际）** - 客户端type `1920` → 服务器type `8`

## 修复方案

### 新的路由逻辑

```typescript
const main = async (req: NextApiRequest, res: NextApiResponse) => {
  const query = new URL(req.url || "/", "http://bbzq.invalid").searchParams;
  const type = query.get("type");

  // Route search requests based on type and server region:
  // - type=7 (HK/TW bangumi): Always use main API (HK/TW content is on main site)
  // - type=8 (intl movie): Use intl API for intl region, main API for HK/TW regions
  // - other types: Use intl API
  const shouldUseMainApi =
    type === "7" ||
    (type === "8" && env.bbzq_region !== "intl");

  const upstream = shouldUseMainApi
    ? `${mainApi}/x/v2/search/type${new URL(req.url || "/", "http://bbzq.invalid").search}`
    : intlApi + req.url;
  // ... rest of code
}
```

### 路由决策表

| 搜索类型 | 服务器区域 | 使用API | 原因 |
|---------|----------|---------|------|
| type=7 番剧（港澳台） | 任何 | **主站API** | 港澳台内容在主站 |
| type=8 影视（国际） | `intl` | **国际版API** | 国际版独占内容 ✅ **修复** |
| type=8 影视（国际） | `hk`/`tw` | **主站API** | 港澳台服务器访问主站 |
| 其他类型 | 任何 | **国际版API** | 默认行为 |

## 部署信息

### 服务器实例

阿里云杭州服务器：`47.98.174.251`

| 实例 | 端口 | 区域 | Resin平台 | BBZQ区域 |
|------|------|------|----------|----------|
| bilihk | 3101 | 香港 | BiliHK | hk |
| bilitw | 3102 | 台湾 | BiliTW | tw |
| biliintl | 3103 | 国际 | BiliIntl | intl |

### 部署步骤

1. 基于运行版本创建新release：
```bash
sudo cp -a /opt/biliroaming-ts-server-vercel/releases/20260819-103108_aliyun-hangzhou_biliroaming-search-fix \
  /opt/biliroaming-ts-server-vercel/releases/20260820-151700_bbzq-intl-search-fix-v2
```

2. 应用代码修复补丁

3. 重新构建：
```bash
sudo -u biliroaming env NODE_OPTIONS=--max-old-space-size=512 pnpm build
```

4. 切换版本并重启：
```bash
sudo ln -sfn /opt/biliroaming-ts-server-vercel/releases/20260820-151700_bbzq-intl-search-fix-v2 \
  /opt/biliroaming-ts-server-vercel/current
sudo systemctl restart biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl
```

## 验证方法

### 1. 检查服务状态

```bash
ssh aliyun-hangzhou "sudo systemctl status biliroaming@biliintl --no-pager"
```

预期输出：
- Active: active (running)
- 正常启动日志

### 2. 测试兼容接口

```bash
curl -k "https://47.98.174.251:3103/api/bbzq/compat"
```

预期返回：
```json
{
  "code": 0,
  "data": {
    "protocol": "bbzq-bangumi/1",
    "region": "intl",
    "capabilities": ["search", "season", "playurl", "subtitle", ...]
  }
}
```

### 3. 监控搜索日志

在Android应用中进行以下测试：

#### 测试1: 搜索国际版独占番剧
1. 打开BBZQ应用
2. 选择"影视（国际）"搜索分类
3. 搜索"Bleach"或"死神 千年血战篇"

**服务器日志验证**：
```bash
ssh aliyun-hangzhou "journalctl -u biliroaming@biliintl -f"
```

**修复前日志**（错误）：
```json
{"action":"搜索(国际影视-主站上游)","method":"GET","url":"/intl/gateway/v2/app/search/type?...&type=8..."}
```

**修复后日志**（正确）：
```json
{"action":"搜索(国际版)","method":"GET","url":"/intl/gateway/v2/app/search/type?...&type=8..."}
```

#### 测试2: 搜索港澳台番剧（确保不影响）
1. 选择"番剧（港澳台）"搜索分类
2. 搜索任意港澳台番剧

**预期**：仍然使用主站API，行为不变

### 4. 功能验证

**成功标准**：
- ✅ 国际服实例搜索type=8时，日志显示"搜索(国际版)"
- ✅ 能够搜索到国际版独占番剧（如"死神 千年血战篇-祸进谭"）
- ✅ 港澳台番剧搜索功能不受影响
- ✅ 所有三个实例正常运行

## 预期效果

修复后：
- ✅ **国际服实例**（port 3103）搜索"影视（国际）"时，使用国际版API，可以搜到国际版独占番剧
- ✅ **港澳台实例**（port 3101/3102）搜索"番剧（港澳台）"时，仍使用主站API，功能正常
- ✅ 保持向后兼容，不影响其他搜索功能

## 回滚方案

如果出现问题，立即回滚到上一个版本：

```bash
ssh aliyun-hangzhou "sudo systemctl stop biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl"
ssh aliyun-hangzhou "sudo ln -sfn /opt/biliroaming-ts-server-vercel/releases/20260819-103108_aliyun-hangzhou_biliroaming-search-fix /opt/biliroaming-ts-server-vercel/current"
ssh aliyun-hangzhou "sudo systemctl start biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl"
```

## 相关文件

- **修改文件**: `pages/api/legacy/intl/gateway/v2/app/search/type.ts`
- **客户端Hook**: `app/src/main/java/io/github/bbzq/feats/hook/BangumiSearchMossHook.kt`
- **客户端Model**: `app/src/main/java/io/github/bbzq/feats/hook/BangumiSearchMossModel.kt`

## 注意事项

1. **环境变量**：修复依赖于 `BILI_REGION` 环境变量正确设置
   - biliintl 实例必须设置 `BILI_REGION=intl`
   - bilihk 实例设置 `BILI_REGION=hk`
   - bilitw 实例设置 `BILI_REGION=tw`

2. **Resin代理**：所有请求通过Resin代理，确保Resin服务正常运行

3. **客户端兼容**：需要BBZQ客户端版本支持区域搜索功能

## 后续工作

- [ ] 在实际使用中收集用户反馈
- [ ] 监控国际版搜索API的响应速度和可用性
- [ ] 考虑添加回退机制：如果国际版API失败，尝试主站API
