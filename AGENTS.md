# TongCraft Sync 开发指引

先阅读 [README.md](README.md) 与 [本地检查指南](docs/LOCAL_CHECKS.md)。命令与 Git 操作在本仓库根目录执行；任务默认聚焦本仓库。

## 流程指派

仓库 Skill 位于 `.agents/skills/`。按任务读取对应 SKILL.md；工具没有自动发现 Skill 时直接读取下表链接即可。只加载本次需要的流程。

| 用户任务或阶段 | 使用流程 | 对应本地检查 |
| --- | --- | --- |
| 适配指定 Minecraft 或跨版本依赖 API | [sync-port-version](.agents/skills/sync-port-version/SKILL.md)；实现后审查并整理交付 | 基线 client；依赖/检查器变化 quick；完成后 port |
| 修改一般客户端行为或界面 | 实现后 [sync-change-review](.agents/skills/sync-change-review/SKILL.md)，交付前 [sync-pre-submit](.agents/skills/sync-pre-submit/SKILL.md) | client；游戏行为或界面变化还需相关游戏检查与截图 |
| 修改 Litematica 桥接、几何或客户端连接生命周期 | 实现后改动审查，交付前提交检查 | port；补充现有测试未覆盖的关键场景 |
| 修改 Node 服务 | 实现后改动审查，交付前提交检查 | `node scripts/check-services.mjs --target node` |
| 修改 Worker 或本地 Worker 配置 | 实现后改动审查，交付前提交检查 | cloudflare；检查入口变化还需 quick |
| 修改共用验证或服务协议 | 实现后改动审查，交付前提交检查 | services；影响客户端时追加 client/port |
| 请求审查代码或定位改动风险 | [sync-change-review](.agents/skills/sync-change-review/SKILL.md) | 按疑点选择复现或测试；只读审查不默认修复 |
| 准备交付、提交、PR 或声明检查通过 | [sync-pre-submit](.agents/skills/sync-pre-submit/SKILL.md) | 按完整改动范围选择并复用仍有效的证据 |
| 修改检查脚本、构建或版本配置 | 实现后改动审查，交付前提交检查 | quick 加受影响 profile |
| 仅修改文档、Skill 或本文件 | 核对引用、命令、触发条件及路由；交付前提交检查 | Skill frontmatter 与链接校验；不要求客户端/游戏全量检查 |

实现任务采用“按需移植 → 改动审查 → 提交前检查”的流程。普通功能实现直接从审查和提交前检查收尾；纯问答、阅读和解释不要求进入交付检查。流程指派不代表启动子代理，也不扩大用户授权范围。

开始开发前对受影响模块运行 doctor 并确认原版的相关检查能通过；已有有效基线可以复用。默认选能覆盖改动的检查并集，广泛改动或用户要求时使用 all。具体命令、环境要求与覆盖限制以本地检查指南为准。

只报告实际执行的检查。环境阻塞、失败或未覆盖场景应明确记录，不能用旧 JAR、旧截图或跳过测试代替证据。检查结果保存在被忽略的 `.checks/`；相关源码变化使证据失效时重新验证。现有客户端测试不替代完整几何验证或两正版客户端联调。

## 代码地图

| 入口 | 职责 |
| --- | --- |
| `client/.../TongCraftClient.java` | Fabric 入口、按键和客户端事件 |
| `client/.../SyncClient.java` | 认证、HTTP/WebSocket、缓存、重连及连接 generation |
| `client/.../LitematicaBridge.java` | Litematica 放置构造、更新、移除和选择 |
| `client/.../Wire.java` | JSON 字段与共享放置的发布数据 |
| `client/.../*Screen.java`、`client/src/main/resources/` | 游戏界面和模组资源 |
| `client/src/gametest/` | 离线真实客户端测试，不打包到生产 JAR |
| `service/src/server.js`、`service/src/validation.js` | Node 服务、权限、持久化和文件/放置验证 |
| `cloudflare/src/index.js` | Worker、SQLite Durable Object 和 R2；复用 Node 验证模块 |
| `scripts/check*.mjs`、`scripts/doctor.mjs`、`scripts/verify-*.mjs` | 本地检查入口与结果收集 |

表中的 `client/.../` 为 `client/src/main/java/org/tongcraft/sync/`。`service/src/validation.js` 同时被 Node 和 Worker 使用，修改它需要检查两个服务。

## 行为与配置

- 匹配现有两空格缩进、命名与局部风格。Java 包使用 org.tongcraft.sync。
- 网络和磁盘操作不得阻塞游戏主线程；连接结束或切换后的迟到响应不得恢复投影。
- 保留原点、旋转、镜像、子区域和维度语义。个人别名和隐藏不得修改公共放置或其他成员偏好。
- 保留正版身份校验、权限隔离、版本冲突处理、文件哈希校验和资源限制。测试会话仅注入测试对象，不加入生产认证绕过。
- 本地 Worker 使用 cloudflare/wrangler.local.jsonc，不需要 CF 登录；个人部署从 example 配置开始，显式指定自己的配置。
- 不提交凭据、.env、.dev.vars、个人部署配置、游戏数据、生成产物或检查报告。检查脚本不能默认部署或创建远程资源。

## 提交

保持改动范围清晰；行为变化添加有检出能力的回归测试。按用户已经授权的操作交付、提交或推送；Skill 不自动要求创建提交、推送或部署。提交说明写明行为变化、复现或验收方式、实际检查结果及未验证项。界面改动附本次截图。
