# TongCraft Sync

[![CI](https://github.com/TongCraft/tongcraft-sync/actions/workflows/ci.yml/badge.svg)](https://github.com/TongCraft/tongcraft-sync/actions/workflows/ci.yml)

Minecraft Java **26.2** 的游戏内投影协作模组与独立同步服务。客户端连接 Carpet 游戏服务器的同时，通过 HTTPS / WebSocket 连接同步服务。**游戏服务器无需安装本项目的模组或插件。**

## 功能

- 正版账号验证：客户端通过 Mojang authlib 提交会话证明，独立服务调用 Mojang `hasJoined` 核对正版 UUID。同步服务不接收微软密码或 Minecraft access token。
- 一次性邀请：48 小时有效，可绑定正版 UUID，可撤销；首次成功注册后按 UUID 自动登录。
- 上传 `.litematic`、发布 Litematica 当前选中的放置实例、复制已有共享投影到新位置。
- 自动同步文件、维度、原点、旋转、镜像、子区域位置和启用状态；文件按 SHA-256 去重和校验。
- 每位成员的隐藏状态、个人别名独立保存到服务，换电脑仍保留。公共投影名称在发布后保持不变，重命名只作用于个人。
- 创建者和管理员可更新或删除共享放置；使用版本号避免覆盖别人的修改。
- 管理员在游戏内创建／撤销邀请、停用／恢复成员。停用成员立即撤销会话、关闭同步连接。
- 本地缓存、断线重试、WebSocket 更新通知和 30 秒补偿刷新。离开指定游戏服务器时移除本模组创建的投影。

投影是 **Litematica 的正常 `SchematicPlacement`**，打印机能够读取其蓝图。已用 Litematica Printer **26.2-3.2.2** 在真实客户端的测试世界中验证：同步本身不修改世界方块，主动开启打印机后能放置石块，且游戏服务端接受放置。复杂方块、红石和 TongCraft 的具体 Carpet 配置仍需联调。模组不会自动开启打印机，也不会代替玩家执行方块放置。

## 客户端安装与使用

需要 Minecraft 26.2、Java 25 或更新版本，以及：

| 依赖 | 本项目编译／运行验证版本 |
| --- | --- |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.158.0+26.2 |
| Litematica | 0.28.8 |
| MaLiLib | 0.29.6 |
| Mod Menu | 可选，用于菜单入口 |
| Litematica Printer | 可选，已验证 26.2-3.2.2 的基础放置 |

将 `client/build/libs/tongcraft-sync-26.2-0.1.0.jar` 和依赖放入客户端的 `mods` 目录。**不要放入游戏服务器的 mods 目录。**

推荐从 [Releases](https://github.com/TongCraft/tongcraft-sync/releases) 下载不带 `-sources` 后缀的 JAR；依赖仍需另外安装。每个 Release 附有源码 JAR 和 `SHA256SUMS.txt`。也可从仓库 [Actions](https://github.com/TongCraft/tongcraft-sync/actions/workflows/ci.yml) 中成功的 CI 运行下载 `tongcraft-sync-client` 构建产物。

1. 按 **H** 打开投影管理（可以在 Minecraft 按键设置中修改），或从 Mod Menu 打开。
2. 打开“连接设置”，填写同步服务地址，例如 `https://sync.example.com`。游戏服务器地址默认是 `mc.tongcraft.cn`，如果使用其他连接地址，可以在此修改。游戏地址用于选择共享空间，不是身份或在线状态的证明。
3. 加入对应游戏服务器，在“邀请码 / 登录”输入管理员给的邀请码并验证。已注册成员和初始管理员留空即可。
4. “发布选中投影”会共享 Litematica 当前选中的蓝图及放置设置；“导入蓝图”打开文件选择器，填写新投影的原点、旋转和镜像后发布。
5. 点击列表名称查看详情；隐藏和别名只影响本人。“选中”把已加载投影设置为 Litematica 当前选中对象，之后可自行操作打印机。
6. 修改子区域时，先选中共享投影，在 Litematica 中解锁并调整，再从详情页“从当前选中投影提交修改”。普通本地调整不会自动广播；服务端接受后才更新其他成员。

跨维度投影保留在列表和文件缓存中，仅在对应维度激活。Litematica 自身的总渲染开关和层模式仍然生效。

## 部署独立同步服务

### Docker Compose + 自动 HTTPS

在有公网域名的部署主机安装 Docker Compose。将本仓库复制到该主机，复制 `.env.example` 为 `.env`，填写：

```dotenv
TONGCRAFT_ADMIN_UUID=你的正版Minecraft账号UUID
SYNC_DOMAIN=sync.example.com
```

域名 DNS 指向部署主机，开放 TCP 80 和 443，然后：

```sh
docker compose up -d --build
docker compose logs -f sync
```

初始管理员必须实际通过正版会话验证，不能仅靠上报 UUID 登录。首次登录不需要邀请码，在游戏内“成员与邀请”创建其他成员的邀请。后台创建邀请码后会将完整码复制到剪贴板，数据库只保存哈希，无法再次读取原码。

SQLite 和蓝图保存在 `sync-data` 持久卷。备份整个卷；运行中应使用 SQLite backup API 或先停止服务再复制数据库及蓝图。Caddy 的证书保存在独立持久卷。不要把 SQLite 文件放在网络共享文件系统上，也不要让多个服务实例同时写入同一目录。

### 本机开发

需要 Node.js 24.13 或更新版本：

Windows 可在项目根目录直接运行：

```powershell
.\start-sync-local.ps1
```

脚本读取根目录 `.env` 中的 `TONGCRAFT_ADMIN_UUID`，缺少依赖时自动安装，监听本机 `127.0.0.1:8787`。也可通过 `-AdminUuid` 指定 UUID，或通过 `-Port` 修改端口；按 Ctrl+C 停止。游戏内同步地址填写 `http://127.0.0.1:8787`，然后加入 `mc.tongcraft.cn`。初始管理员邀请码留空即可登录。这个本机地址只能供本机客户端访问，多人联调需要可访问的 HTTPS 同步服务。

其他系统或手动启动：

```powershell
cd service
npm ci
$env:TONGCRAFT_ADMIN_UUID='你的正版Minecraft账号UUID'
npm start
```

默认仅监听 `127.0.0.1:8787`。客户端测试地址填写 `http://127.0.0.1:8787`；除 localhost / 环回地址外，客户端要求 HTTPS。生产部署使用反向代理提供 HTTPS 和 WebSocket，不要直接暴露 HTTP 端口。

| 环境变量 | 默认值 |
| --- | --- |
| `TONGCRAFT_ADMIN_UUID` | 必填，初始管理员的正版 UUID |
| `HOST` | `127.0.0.1`（容器中为 `0.0.0.0`） |
| `PORT` | `8787` |
| `TONGCRAFT_DATA_DIR` | 当前工作目录的 `data` |

## 构建与验证

确保 `JAVA_HOME` 指向 JDK 25 或更新版本；系统已有 JDK 26 也可以，输出字节码目标为 Java 25。

```powershell
.\build-client.ps1
cd service
npm ci
npm test
```

Windows 构建脚本会检查 `JAVA_HOME`；如果它指向旧 JDK，会尝试使用 PATH 中的新版 JDK，不修改系统环境变量。其他系统运行 `./gradlew :client:build`。

运行真实 Minecraft 客户端测试（会启动专用测试窗口和临时单人世界，不使用你的正版凭证）：

```powershell
.\gradlew.bat :client:runClientGameTest
```

测试截图位于 `client/build/run/clientGameTest/screenshots`。测试模组是单独的 `gametest` 源集，不会打包进发布 JAR，也不会给生产认证添加绕过入口。

服务测试通过可注入的会话校验器模拟 Mojang 响应，覆盖身份不匹配、挑战重放、邀请绑定／过期／撤销／并发兑换、投影权限、版本冲突、个人偏好隔离、文件去重／下载与持久化。真实客户端测试验证游戏 UI、Litematica 放置和别名／隐藏行为、打印机实际放置、HTTP 蓝图下载，以及断线后的迟到响应不会恢复投影。**这些测试不等同于两个正版客户端的线上联调。**

## GitHub CI

推送 `main`、提交 Pull Request 或手动触发 Actions 时，自动构建客户端 JAR、运行服务端测试、检查运行依赖漏洞，并构建与启动独立服务容器做健康检查。Actions 固定到完整提交 SHA。

每次推送到 `main` 且全部检查通过后，自动为该提交创建 tag 和 GitHub Release，上传模组 JAR、源码 JAR 和 SHA-256 校验文件。版本使用 `0.1.<CI运行编号>`，例如第 3 次 CI 对应 `v0.1.3`；运行编号可能因失败或 PR 检查而跳号。JAR 文件名及模组内版本号与 tag 一致。重跑同一轮 CI 使用同一 tag，修复缺失的上传文件；PR 和手动检查不会发布 Release。并行更新会各自执行，不取消旧更新的发布。

构建和测试仅有读取仓库权限，发布任务单独使用 GitHub 自动提供的 `GITHUB_TOKEN` 写入 tag 和 Release，无需配置个人令牌。普通本机构建仍为 `0.1.0`；重现发布版本可运行 `./gradlew :client:build -PmodVersion=0.1.3`。

客户端图形界面和打印机测试通过上面的 `runClientGameTest` 在本机运行；CI 不替代真实正版账号和多人游戏联调。当前没有自动部署：同步服务部署位置确定后，可再为正式版本加入部署流程。

## 约束与联调

- 正版认证证明账号身份；独立服务不能证明客户端当前确实在线于 TongCraft。访问授权来自管理员发出的邀请。
- 移除成员只能停止后续访问，不能收回其已下载的蓝图文件。
- 当前服务对应一个共享空间；客户端通过指定游戏地址启用它。游戏服务器重置世界时应更换同步服务的数据空间，避免加载旧世界投影。
- 上传最大 32 MiB，解压最大 128 MiB，最多 1024 子区域、3200 万方块；限制用于避免异常蓝图拖垮服务或客户端。
- 共享放置总数最多 5000，公共放置元数据总量限制为 4 MiB，单个成员最多首次上传 500 份不同蓝图。最多 2000 个成员，最多保留 1000 条邀请记录（到期超过七天的记录会在创建邀请时清理）。大量大型蓝图仍需要按实际客户端硬件评估内存占用。
- 没有实现蓝图垃圾回收；删除放置后文件仍保留，管理员应监控同步服务磁盘空间。
- 正式上线前需要两个正版客户端验证：注册、发布、同步位置／子区域、个人隐藏与别名隔离、退出重进、切换维度、服务重启、成员停用，以及具体打印机的生存建造行为。

参考依赖：[Litematica](https://github.com/sakura-ryoko/litematica/tree/LTS/26.2)、[Fabric 文档](https://docs.fabricmc.net/develop/)、[Litematica Printer](https://github.com/aria1th/litematica-printer)。
