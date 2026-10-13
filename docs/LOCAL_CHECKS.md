# 本地开发检查

这些脚本在本地检查开发环境、构建配置、测试行为和安装产物，不会发布 JAR、创建 Cloudflare 资源或部署 Worker。需要 Node.js 24.13+；客户端检查还需要 JDK 25+。doctor 可以在较旧的 Node 上运行并报告版本不符。

## 开始开发

在仓库根目录执行：

```sh
node scripts/doctor.mjs --profile client
node scripts/check.mjs --profile quick
node scripts/check.mjs --profile client
```

先确认原版能通过相关检查，再开始修改。doctor 不安装依赖、不修改系统环境变量；它会指出缺失项及修复命令。Java 选择与 Windows 构建脚本一致：优先使用适合的 JAVA_HOME，否则尝试 PATH 对应的 JDK，仅对构建子进程设置 JAVA_HOME。

检查 Cloudflare 本地环境和安装依赖：

```sh
npm --prefix cloudflare ci --ignore-scripts
node scripts/doctor.mjs --profile cloudflare
node scripts/check.mjs --profile cloudflare
```

检查两个服务实现：

```sh
npm --prefix service ci --ignore-scripts
npm --prefix cloudflare ci --ignore-scripts
node scripts/check.mjs --profile services
```

自动测试使用临时数据、模拟会话和本地 Worker / Durable Object / R2，不需要 Cloudflare 登录、线上 bucket 或正版账号。首次安装依赖、Gradle 构建及下载游戏资源仍可能需要网络；手动进行真实登录时需要 Mojang 服务。Cloudflare 本地配置的素材库链接使用保留的 .invalid 测试域名，不会提供可访问的网页上传页面。

## 按任务选择检查

| Profile | 执行内容 | 尚不能证明 |
| --- | --- | --- |
| `quick` | Node 版本、版本配置、检查脚本和发布策略的回归测试 | 客户端能编译或启动 |
| `client` | 客户端 doctor、版本配置、Gradle 构建、生产 JAR 检查 | 游戏运行行为 |
| `cloudflare` | Cloudflare doctor、本地 Vitest 测试、Worker dry-run 打包 | 线上部署和真实账号联调 |
| `services` | 两个服务的 doctor、Node / Cloudflare 测试、Worker dry-run 打包 | 两个服务与 Java 客户端完整互通 |
| `game` | 客户端 doctor、版本配置、现有真实客户端测试 | 完整几何适配和两正版账号联调 |
| `port` | `client` 加 `game`，客户端构建失败时阻止游戏检查 | 所有蓝图、版本和可选模组组合的兼容性 |
| `all` | `quick`、`client`、`services`、`game` | 线上环境与多人真实账号验证 |

统一入口：

```sh
node scripts/check.mjs --profile port
node scripts/check.mjs --profile all
node scripts/check.mjs --profile client --version 0.1.10
```

默认构建版本为 0.1.0，`--version` 传给 Gradle 的 modVersion 并作为 JAR 验收版本。检查只接受本次成功构建后指定版本的 JAR，不会挑选目录中“最新”的旧文件。Gradle 可以复用有效的增量构建结果。

独立脚本也可以运行：

```sh
node scripts/verify-versions.mjs
node scripts/check-client.mjs
node scripts/check-services.mjs --target node
node scripts/check-services.mjs --target cloudflare
node scripts/check-game.mjs
node scripts/verify-artifact.mjs --file client/build/libs/tongcraft-sync-26.2-0.1.10.jar --version 0.1.10
```

所有脚本支持 `--help`。它们以脚本位置定位仓库，命令可从其他工作目录执行；`--file` 和 `--config` 的相对路径以仓库根目录为准。

## 版本与产物检查

版本检查读取当前 `client/build.gradle` 的字面量声明，核对 fabric.mod.json 的 Minecraft 版本、运行依赖最低版本和入口、Fabric API / Litematica / MaLiLib 的目标版本、JAR 名称，以及已有发布流程的 Minecraft 文案。它不联网猜测目标版本的依赖号；依赖是否能解析由真实构建验证。若改为另一种 Gradle 声明方式，检查会明确失败，需要同步更新解析器及回归测试。

产物检查在内存中读取 JAR，不解压到用户目录。它检查实际模组版本、依赖与入口、入口 class、语言文件、许可证、Java 字节码目标以及已知测试内容泄漏。源码 JAR 不能代替可安装 JAR。独立 `verify-artifact` 只验证指定文件的内容，不证明该文件来自当前源码；使用 `client` 或 `port` profile 才会先执行当前源码构建。

## 游戏检查

`game` / `port` / `all` 会启动专用的离线测试 Minecraft 窗口，使用 Gradle 的 `clientGameTest` 目录和临时世界。不会使用日常游戏目录或添加生产认证绕过。Linux 需要图形显示环境，必要时可使用 `xvfb-run -a node scripts/check.mjs --profile port`。

当前测试覆盖界面流程、单石块蓝图、原点、隐藏和别名、主动打印机放置、HTTP 下载与断线后的迟到响应。测试仍依赖固定的 Litematica Printer 测试版本，尚未拆分基础测试与打印机测试；多子区域旋转、镜像、损坏缓存、维度切换和两正版客户端联调需要继续补充。

检查入口为每次运行提供独立标识，只有本次测试执行到结束并且 Gradle 成功退出才算通过。游戏任务会重新执行，并将本次新生成的截图复制到报告目录。成功生成截图不等于布局正确，界面改动仍需查看截图。

## 结果与失败处理

每次执行生成 `.checks/<时间>-<进程号>/report.json`，记录代码提交、工作区状态、已跟踪改动摘要、未跟踪文件哈希、命令、退出状态、日志和截图。这些文件已忽略，不提交到 Git。

状态为 `passed`、`failed`、`blocked` 或 `not-required`。任何失败、环境阻塞或必要检查未完成都会以非零退出码结束。`not-required` 表示该项不属于所选检查的证明范围；不能将其写成已验证。零测试、全跳过测试和旧游戏完成标识不能使检查通过。构建、游戏和服务子进程有超时，超时或中断会停止其子进程树。

检查结束时会再次核对提交、改动摘要和未跟踪文件哈希；检查期间源码变化会将结果标记为阻塞，要求重新运行。修改后运行受影响的检查；检查通过后再次修改相关代码，应重新验证。向维护者提交 PR 或失败报告时附上失败场景、日志路径和已执行的检查，不把未执行项写成成功。不需要把失败日志中的所有输出贴入聊天。

## 自建 Cloudflare 部署

本地开发使用 `cloudflare/wrangler.local.jsonc`。现有 `cloudflare/wrangler.jsonc` 保留维护者的线上部署配置；不要用它部署到自己的账号。

复制 `cloudflare/wrangler.deploy.example.jsonc` 为 `cloudflare/wrangler.deploy.jsonc`，填写自己的 account_id、管理员 Minecraft UUID、R2 bucket 名和素材库地址。该个人配置已忽略。`workers_dev=true` 可以使用账号的 workers.dev 地址；使用自定义域名时自行配置 routes。素材库地址需要指向与此同步服务对接的素材库，相关仓库联调不属于本地检查。

只有显式运行以下命令才会查询 Cloudflare 账号和 R2，均为只读：

```sh
node scripts/doctor.mjs --profile deploy --config cloudflare/wrangler.deploy.jsonc
```

部署 doctor 不创建 bucket、不登录、不部署，也不验证域名所有权或线上运行行为。账号、R2 权限和资源可访问性通过后，仍需自行完成部署和上线验证。凭据仅在本地配置，脚本不打印原始账号查询输出或 token。
