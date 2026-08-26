# 番剧解析失败根本原因：Resin代理服务未运行

**日期**: 2026-08-26  
**问题**: 所有番剧解析请求返回地区限制错误  
**根本原因**: Resin代理服务未启动

## 问题诊断

### 1. LSPosed日志分析

日志显示客户端功能正常：
- ✅ 番剧MOSS hook正常工作
- ✅ 区域回退机制正常触发
- ✅ TW区域请求发送成功

但服务器端返回地区限制错误。

### 2. 服务器端问题发现

**关键发现**：
```bash
$ curl -fsS http://127.0.0.1:2260/health
curl: (7) Failed to connect to 127.0.0.1 port 2260: Connection refused
```

**结论**：Resin代理服务（端口2260）未运行！

### 3. 架构说明

解析服务器使用以下架构：

```
BBZQ客户端
    ↓
Nginx (3101/3102/3103)
    ↓
Next.js实例 (13101/13102/13103)
    ↓
Resin代理 (2260) ← **这里出问题了！**
    ↓
Bilibili API (需要区域节点)
```

**关键设计**：
- 所有Bilibili请求**必须**通过Resin代理
- **没有fallback到直连**（安全设计，防止泄露真实IP）
- Resin不可用 = 服务不可用

### 4. 代码证明

`src/utils/resin-fetch.ts`:
```typescript
export const resinFetch = async (input: RequestInfo | URL, init: ResinFetchInit = {}) => {
  const url = getUrl(input);
  assertBilibiliUrl(url);  // 只允许Bilibili域名
  
  // 必须使用Resin代理，没有fallback
  return await fetch(input, {
    ...init,
    headers,
    dispatcher: getProxyAgent(),  // ← 这里会抛出异常如果Resin配置缺失
  } as RequestInit & { dispatcher: ProxyAgent });
};
```

`getProxyAgent()` 需要环境变量：
- `RESIN_PROXY_URL` - Resin服务地址
- `RESIN_PLATFORM` - 平台名称 (BiliHK/BiliTW/BiliIntl)
- `RESIN_ACCOUNT` - 账户名称
- `RESIN_PROXY_TOKEN` - 可选的认证token

如果这些配置不正确或Resin服务未启动，**所有请求都会失败**。

## 解决方案

### 方案1: 启动Resin代理服务（推荐）

Resin是专门的HTTP代理服务，提供：
- 智能节点选择（低延迟优先）
- 健康检查和熔断
- 区域过滤（HK/TW/INTL）
- 粘性会话

**操作步骤**：

1. **检查Resin是否安装**：
```bash
which resin
# 或
systemctl status resin
```

2. **如果未安装，需要部署Resin**：
   - Resin是独立服务，需要单独部署
   - 参考Resin官方文档配置
   - 创建三个平台：BiliHK, BiliTW, BiliIntl

3. **配置平台**（使用Resin管理API）：

```bash
export RESIN_ADMIN_TOKEN='<your-admin-token>'

# 创建香港平台
curl -X POST http://localhost:2260/api/v1/platforms \
  -H "Authorization: Bearer ${RESIN_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "BiliHK",
    "sticky_ttl": "24h",
    "region_filters": ["hk", "mo"],
    "allocation_policy": "PREFER_LOW_LATENCY",
    "reverse_proxy_miss_action": "REJECT",
    "passive_circuit_breaker_disabled": false
  }'

# 创建台湾平台
curl -X POST http://localhost:2260/api/v1/platforms \
  -H "Authorization: Bearer ${RESIN_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "BiliTW",
    "sticky_ttl": "24h",
    "region_filters": ["tw"],
    "allocation_policy": "PREFER_LOW_LATENCY",
    "reverse_proxy_miss_action": "REJECT",
    "passive_circuit_breaker_disabled": false
  }'

# 创建国际平台
curl -X POST http://localhost:2260/api/v1/platforms \
  -H "Authorization: Bearer ${RESIN_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "BiliIntl",
    "sticky_ttl": "24h",
    "region_filters": ["sg", "th", "jp", "us"],
    "allocation_policy": "PREFER_LOW_LATENCY",
    "reverse_proxy_miss_action": "REJECT",
    "passive_circuit_breaker_disabled": false
  }'
```

4. **配置环境文件**：

编辑 `/etc/biliroaming/bilihk.env`:
```bash
PORT=13101
BIND_HOST=127.0.0.1
BILI_REGION=hk
RESIN_PROXY_URL=http://127.0.0.1:2260
RESIN_PLATFORM=BiliHK
RESIN_ACCOUNT=BiliHK
RESIN_PROXY_TOKEN=
```

对 `bilitw.env` 和 `biliintl.env` 做相应修改。

5. **重启服务**：
```bash
sudo systemctl restart biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl
```

6. **验证**：
```bash
curl -kfsS https://127.0.0.1:3101/api/bbzq/compat
curl -kfsS https://127.0.0.1:3102/api/bbzq/compat
curl -kfsS https://127.0.0.1:3103/api/bbzq/compat
```

### 方案2: 临时fallback到直连（不推荐）

如果无法立即部署Resin，可以临时修改代码添加直连fallback：

**警告**：此方案会暴露服务器真实IP给Bilibili，可能导致IP被封禁！

修改 `src/utils/resin-fetch.ts`:

```typescript
export const resinFetch = async (
  input: RequestInfo | URL,
  init: ResinFetchInit = {}
) => {
  const url = getUrl(input);
  assertBilibiliUrl(url);

  const headers = new Headers(init.headers);
  headers.delete("proxy-authorization");

  const method = requestMethod(input, init);
  
  // 尝试使用Resin
  try {
    const attempts = RETRYABLE_METHODS.has(method) ? MAX_RESIN_ATTEMPTS : 1;
    for (let attempt = 1; attempt <= attempts; attempt += 1) {
      try {
        return await fetch(input, {
          ...init,
          headers,
          dispatcher: getProxyAgent(),
        } as RequestInit & { dispatcher: ProxyAgent });
      } catch (error) {
        if (attempt === attempts) throw error;
        await retryDelay(attempt);
      }
    }
  } catch (resinError) {
    // Fallback到直连（仅用于紧急情况）
    console.error('Resin proxy failed, falling back to direct connection:', resinError);
    return await fetch(input, { ...init, headers });
  }

  throw new Error("Resin retry loop exited unexpectedly");
};
```

修改后需要重新构建：
```bash
cd /opt/biliroaming-ts-server-vercel/current
sudo -u biliroaming env NODE_OPTIONS=--max-old-space-size=512 pnpm build
sudo systemctl restart biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl
```

## 验证清单

完成修复后，按顺序验证：

### 1. Resin服务状态
```bash
curl -fsS http://127.0.0.1:2260/health
# 预期：返回健康状态或平台列表
```

### 2. 平台配置
```bash
curl -H "Authorization: Bearer ${RESIN_ADMIN_TOKEN}" \
  http://127.0.0.1:2260/api/v1/platforms
# 预期：返回BiliHK, BiliTW, BiliIntl三个平台
```

### 3. Next.js实例
```bash
curl -kfsS https://127.0.0.1:3101/api/bbzq/compat
# 预期：{"code":0,"data":{"protocol":"bbzq-bangumi/1","region":"hk",...}}

curl -kfsS https://127.0.0.1:3102/api/bbzq/compat
# 预期：region=tw

curl -kfsS https://127.0.0.1:3103/api/bbzq/compat
# 预期：region=intl
```

### 4. 实际播放测试
```bash
# 使用BBZQ客户端测试播放一个港区番剧
# 检查LSPosed日志，应该看到：
# "Bangumi MOSS parser result: region=HK, status=200"
# "Bangumi MOSS fallback succeeded: region=HK"
```

### 5. 查看服务器日志
```bash
journalctl -u biliroaming@bilihk -f
journalctl -u biliroaming@bilitw -f
journalctl -u biliroaming@biliintl -f
```

预期日志：
```json
{
  "action": "Bilibili playurl upstream",
  "region": "hk",
  "code": 0,
  "dash_video_count": 10
}
```

## 关键要点

1. **Resin是必需的**
   - 不是可选组件
   - 提供区域节点选择和IP保护
   - 没有Resin = 服务不可用

2. **三个独立实例**
   - 每个实例对应一个Resin平台
   - 必须正确配置BILI_REGION环境变量
   - 端口映射：13101→3101(HK), 13102→3102(TW), 13103→3103(INTL)

3. **客户端代码是正常的**
   - 问题出在服务器端基础设施
   - 客户端的回退机制、字幕注入都工作正常
   - LSPosed日志显示的"thread interrupted"是正常行为

4. **安全考虑**
   - 直连fallback会暴露服务器IP
   - Bilibili可能会封禁直连IP
   - 生产环境必须使用Resin

## 后续工作

- [ ] 部署或修复Resin代理服务
- [ ] 配置三个Resin平台（BiliHK/BiliTW/BiliIntl）
- [ ] 验证所有三个区域正常工作
- [ ] 设置Resin监控和告警
- [ ] 考虑Resin的高可用部署

## 参考资料

- 服务器部署文档：`.server-worktree-1787063902/deploy/README.md`
- 客户端配置文档：`docs/bangumi-parser-server-bbzq.md`
- Resin配置示例：`.server-worktree-1787063902/deploy/env/*.env.example`
