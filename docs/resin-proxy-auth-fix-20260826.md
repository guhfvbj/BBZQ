# Resin代理认证问题修复 - 最终解决方案

**日期**: 2026-08-26  
**问题**: 番剧解析服务器通过Resin代理访问Bilibili API失败  
**错误**: `Proxy response (404) !== 200 when HTTP Tunneling`

---

## 问题根源

### 发现过程

1. **初步诊断**：服务器返回`-10403`（地区限制）错误
2. **验证Resin运行**：Resin服务正常，平台配置正确，节点可路由
3. **代码审查**：发现`resin-fetch.ts`中的认证格式问题

### 根本原因

**Proxy-Authorization头格式错误**

代码中的逻辑：
```typescript
// ❌ 错误：token为空时省略了冒号
const identity = token ? `${platform}.${account}:${token}` : `${platform}.${account}`;
// 结果: "BiliTW.BiliTW" (没有冒号)
```

但Resin期望的格式：
```
platform.account:token
```

**即使token为空，冒号也必须保留！**

### 验证发现

使用curl测试成功：
```bash
curl -x http://127.0.0.1:2260 -U 'BiliTW.BiliTW:' https://www.bilibili.com
# Proxy-Authorization: Basic QmlsaVRXLkJpbGlUVzo=
# 解码: "BiliTW.BiliTW:" ← 注意末尾的冒号
```

undici发送的（错误）：
```
Proxy-Authorization: Basic QmlsaVRXLkJpbGlUVw==
# 解码: "BiliTW.BiliTW" ← 缺少冒号
```

Resin拒绝了没有冒号的格式，返回404。

---

## 解决方案

### 代码修复

文件：`src/utils/resin-fetch.ts`

```typescript
// ✅ 正确：始终保留冒号
const identity = `${platform}.${account}:${token}`;
proxyAgent = new ProxyAgent({
  uri: parsed.origin,
  token: `Basic ${Buffer.from(identity).toString("base64")}`,
});
```

### 修复后的认证流程

1. **环境变量**（`/etc/biliroaming/bilihk.env`）：
   ```bash
   RESIN_PLATFORM=BiliHK
   RESIN_ACCOUNT=BiliHK
   RESIN_PROXY_TOKEN=        # 空token
   ```

2. **生成identity**：
   ```javascript
   const identity = `BiliHK.BiliHK:`;  // ← 末尾冒号必须保留
   ```

3. **Base64编码**：
   ```javascript
   Buffer.from('BiliHK.BiliHK:').toString('base64')
   // 结果: "QmlsaUhLLkJpbGlISzo="
   ```

4. **设置Proxy-Authorization头**：
   ```
   Proxy-Authorization: Basic QmlsaUhLLkJpbGlISzo=
   ```

5. **Resin解析**：
   - 解码base64 → `BiliHK.BiliHK:`
   - 按冒号分割 → platform.account = `BiliHK.BiliHK`, token = ``
   - 匹配平台配置 → 找到BiliHK平台
   - 分配节点 → 选择香港区域节点
   - 建立连接 → 返回200 Connection Established

---

## 部署步骤

### 1. 修复代码

```bash
# 在本地工作树修改
cd /data/projects/BBZQ/.server-worktree-1787063902
vim src/utils/resin-fetch.ts

# 部署到阿里云
scp src/utils/resin-fetch.ts aliyun-hangzhou:/opt/biliroaming-ts-server-vercel/current/src/utils/
```

### 2. 重新构建

```bash
ssh aliyun-hangzhou
cd /opt/biliroaming-ts-server-vercel/current
sudo -u biliroaming pnpm build
```

### 3. 重启服务

```bash
sudo systemctl restart biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl
```

### 4. 验证

```bash
# 检查服务状态
systemctl status biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl

# 测试API端点
curl -s http://127.0.0.1:13101/api/bbzq/compat | jq
curl -s http://127.0.0.1:13102/api/bbzq/compat | jq
curl -s http://127.0.0.1:13103/api/bbzq/compat | jq

# 查看日志
journalctl -u biliroaming@bilihk -f
```

---

## 验证结果

### 测试脚本验证

创建测试脚本验证修复：

```javascript
const { ProxyAgent } = require('undici');
const identity = `BiliTW.BiliTW:`;  // 保留冒号
const proxyAgent = new ProxyAgent({
  uri: 'http://127.0.0.1:2260',
  token: `Basic ${Buffer.from(identity).toString('base64')}`,
});

fetch('https://api.bilibili.com/x/web-interface/nav', {
  dispatcher: proxyAgent,
  signal: AbortSignal.timeout(10000)
})
```

**结果**：
```
✅ Success! Code: -101
Response: {"code":-101,"message":"账号未登录",...}
```

说明：
- ✅ Resin代理连接成功
- ✅ 请求成功到达Bilibili API
- ✅ 收到正常API响应（-101是预期的，因为没有登录凭据）

### 服务状态

```bash
$ systemctl is-active biliroaming@bilihk biliroaming@bilitw biliroaming@biliintl
active
active
active
```

### API端点测试

```bash
$ curl http://127.0.0.1:13101/api/bbzq/compat | jq
{
  "code": 0,
  "data": {
    "protocol": "bbzq-bangumi/1",
    "region": "hk",
    ...
  }
}
```

---

## 关键要点

### 1. HTTP代理认证格式严格

Resin期望的Proxy-Authorization格式：
```
Proxy-Authorization: Basic base64(platform.account:token)
```

**冒号是分隔符，必须保留，即使token为空！**

### 2. Base64编码的差异

```javascript
// 没有冒号
Buffer.from('BiliTW.BiliTW').toString('base64')
// → "QmlsaVRXLkJpbGlUVw==" ❌

// 有冒号
Buffer.from('BiliTW.BiliTW:').toString('base64')
// → "QmlsaVRXLkJpbGlUVzo=" ✅
```

看起来只差一个字符，但Resin完全无法识别没有冒号的格式。

### 3. curl vs undici

curl的`-U`参数会自动添加冒号：
```bash
curl -U 'user'        # 实际发送: "user:"
curl -U 'user:'       # 实际发送: "user:"
curl -U 'user:pass'   # 实际发送: "user:pass"
```

但undici的ProxyAgent不会，需要手动构造完整格式。

### 4. 调试技巧

对比成功和失败的base64：
```bash
# 成功的（curl）
echo "QmlsaVRXLkJpbGlUVzo=" | base64 -d
# → BiliTW.BiliTW:

# 失败的（undici错误格式）
echo "QmlsaVRXLkJpbGlUVw==" | base64 -d
# → BiliTW.BiliTW
```

---

## 后续工作

### 待验证项

- [ ] 使用BBZQ客户端测试真实番剧播放
- [ ] 验证HK/TW/INTL三个区域都正常工作
- [ ] 检查字幕功能是否正常
- [ ] 监控服务器日志确认无错误

### 优化建议

1. **添加单元测试**：
   ```typescript
   describe('resinFetch', () => {
     it('should format identity with colon even when token is empty', () => {
       const identity = formatIdentity('BiliHK', 'BiliHK', '');
       expect(identity).toBe('BiliHK.BiliHK:');
     });
   });
   ```

2. **添加日志**：
   ```typescript
   console.log(`Resin proxy: ${platform}.${account} via ${proxyUrl}`);
   ```

3. **健康检查**：
   定期验证Resin连接：
   ```bash
   curl -x http://127.0.0.1:2260 \
     -U 'BiliHK.BiliHK:' \
     -I https://api.bilibili.com/x/web-interface/nav
   ```

---

## 参考资料

- Resin配置文档: `docs/resin-proxy-issue-20260826.md`
- undici ProxyAgent文档: https://undici.nodejs.org/#/docs/api/ProxyAgent
- HTTP代理认证RFC: https://tools.ietf.org/html/rfc7235#section-4.3

## 修复时间线

- 13:15 - 发现Resin代理404错误
- 13:30 - 验证Resin服务正常运行
- 14:00 - 使用curl测试成功，发现格式差异
- 14:10 - 定位到代码中的认证格式错误
- 14:20 - 修复代码，重新构建
- 14:30 - 部署到阿里云服务器
- 14:35 - 验证测试脚本成功
- 14:40 - 三个服务全部重启成功

**总计修复时间**: ~85分钟
