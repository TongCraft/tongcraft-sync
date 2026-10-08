# TongCraft Sync 的 Cloudflare 部署

这个版本与客户端的现有协议兼容：正版会话证明、一次性邀请、成员管理、共享放置、个人偏好、WebSocket 失效通知，以及投影素材库的一次性网页登录票据。Worker 在 `sync.weiuou.top` 提供 HTTPS；SQLite Durable Object 保存同步数据并支持 WebSocket 休眠；已有的 `tongcraft-schematics` R2 bucket 以 `sync/` 前缀保存游戏内共享蓝图。

初始管理员为 Minecraft `Wei_uou`（UUID `288772a1e4a54741ac4caa7b4ceceddd`）。初次在游戏内连接 `https://sync.weiuou.top` 时，管理员邀请码留空；后续成员需要管理员创建的邀请码。这里是独立的数据空间，原来 Node 服务里的成员和共享放置不会自动迁入。

## 本地开发

```sh
cd cloudflare
npm ci --ignore-scripts
npm test
npm run check
npm run dev
```

测试使用 Cloudflare 的本地 Worker、SQLite Durable Object 和 R2 模拟器；正版认证测试会在测试对象内注入已验证会话，线上代码仍调用 Mojang `hasJoined`。正式上线后还要用两个正版客户端完成联调。

## 部署

Cloudflare 账号中先创建 `tongcraft-schematics` R2 bucket，并激活 `weiuou.top` zone。然后用 Wrangler 登录并运行：

```sh
cd cloudflare
npm ci --ignore-scripts
npm run deploy
```

Custom Domain 自动为 `sync.weiuou.top` 配置 DNS 和证书。检查 `https://sync.weiuou.top/health` 返回 `{"ok":true,"protocol":1}`。设置 `TongCraft/tongcraft-sync` 的 GitHub Actions 变量 `SYNC_DEPLOY_ENABLED=true`、`CLOUDFLARE_ACCOUNT_ID` 和密钥 `CLOUDFLARE_API_TOKEN` 后，主分支每次通过测试会自动部署。同步服务与素材库可以使用同一账号的 Cloudflare token；不要把 token 放进仓库或聊天消息。

素材库仓库还要把变量 `SYNC_API_URL` 设为 `https://sync.weiuou.top`，将 `ALLOW_PUBLIC_PREVIEW` 设为 `false` 并重新部署。网页上传链接由已登录 Sync 模组领取；浏览和下载保持公开。

## 免费额度保护

游戏内共享的蓝图最多占 **4 GB**，压缩文件最大 **16 MiB**、解压后最大 **64 MiB**。Sync 每 UTC 日最多预留 500 次 R2 写入和 10,000 次读取。素材库另限 5 GB；两个服务合计预留容量不超过 9 GB，给 R2 的 10 GB 月免费存储留出余量。失败的 R2 写入可能继续占用预留容量，避免不确定的写入结果造成超额，需管理员调查后修复。两个服务共用账号额度；控制台和其他应用的 R2 操作不受站内计数约束，应查看账号总用量。

SQLite Durable Objects 可以用于 Workers Free 计划。免费计划超出 Durable Objects 每日额度时，额外请求会失败；WebSocket 使用休眠 API，空闲连接不持续占用计费时长。客户端重连和可用性仍需从玩家的实际网络测试。

Cloudflare 全球网络不保证中国大陆线路质量；实际可达性、延迟和上传速度应在国内运营商网络实测。如果不符合需要，保留同一 API 协议，将服务迁到国内或香港节点。

参考：[Durable Objects 定价](https://developers.cloudflare.com/durable-objects/platform/pricing/)、[WebSocket 休眠](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)、[R2 定价](https://developers.cloudflare.com/r2/pricing/)、[Cloudflare 中国网络](https://developers.cloudflare.com/china-network/)。
