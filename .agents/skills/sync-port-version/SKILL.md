---
name: sync-port-version
description: "将 TongCraft Sync 客户端适配到指定 Minecraft 或依赖 API 版本，确认依赖可用性、修复兼容性并验证 JAR 和游戏行为。用于跨版本移植；普通界面改动或 Cloudflare 自建部署不走此流程。"
---

# Sync 版本移植

以用户指定的目标版本完成客户端适配，交付可定位的 JAR 与实际验证结果；依赖或环境不具备时，交付有证据的阻塞原因和已完成部分。

从仓库根目录执行命令。先读 [AGENTS.md](../../../AGENTS.md) 的代码地图和行为约束，再读 [本地检查指南](../../../docs/LOCAL_CHECKS.md) 中版本、产物与游戏检查的说明。

## 确认目标和基线

- 从 `client/build.gradle`、模组元数据和用户请求确定原版本、目标 Minecraft 版本、依赖适配范围，以及用户是否要保留原版本支持。目标不明确时只询问影响实现的部分，不自行替换为“最新版本”。
- 查看当前分支与工作区改动，保留已有工作。对本次将修改的客户端运行 doctor 和原版本的 client 检查；已有同一代码的有效结果可作为基线。基线失败时区分原有问题、环境问题和移植产生的问题，不重置工作区来制造干净基线。

```sh
node scripts/doctor.mjs --profile client
node scripts/check.mjs --profile client
```

## 确认依赖再适配

查询目标版本的官方发布元数据、适配文档和依赖维护仓库，记录实际可用的 Minecraft、Fabric Loader / API、Loom / Gradle、Java、Litematica、MaLiLib 版本与来源。Mod Menu 和打印机按用户需求检查，不把旧版本号套进新版本坐标。

当前游戏测试直接依赖固定的打印机测试版本。打印机尚未支持目标版本时，区分 Sync 主模组适配和打印机测试阻塞；保留已完成的主模组构建证据。若需要拆分测试依赖，在任务范围内实施并保留基础测试，不能删除打印机测试后宣称整套兼容验证通过。

优先通过最小兼容性修改恢复构建和实际行为：

- 构建与加载：`client/build.gradle`、必要的 Gradle 配置、`client/src/main/resources/fabric.mod.json`、JAR 名称、安装说明和发布版本文案。
- Litematica 接入：`LitematicaBridge.java` 的放置构造、选择、移除、子区域数据和不写入本地保存记录的行为。
- 游戏 API：入口和 Screen 类、维度标识、会话 API、游戏主线程调度及测试代码。
- 同步数据：若必须改变线上字段或语义，同时检查 `Wire.java`、`SyncClient.java` 和两个服务实现。普通游戏 API 移植无需改服务协议。

版本检查器目前读取 Gradle 字面量声明。改变声明方式时同步更新解析器与相关回归测试，保留版本不一致的失败检测；不要关闭检查器来接受无法识别的配置。

## 验证和交付

依赖配置或检查器变化先运行 quick；客户端适配完成后运行 port。可用 `--version` 指定实际交付的模组版本，命令细节以本地检查指南为准。

```sh
node scripts/check.mjs --profile quick
node scripts/check.mjs --profile port
```

`port` 通过表示当前客户端构建、JAR 内容检查和已有游戏测试通过。根据这次 API 变化补充有检出能力的回归场景，特别关注非对称多子区域蓝图的旋转、镜像、偏移和维度行为。查看本次截图；新增测试若需要独立命令，记录该命令和结果。

依赖解析失败时保留坐标、来源与错误；游戏启动或行为失败时保留失败场景及报告路径。缺图形环境、缺目标依赖或尚未执行的联调应明确报告，继续完成不依赖阻塞条件的工作，不能把编译成功写成游戏兼容成功。

完成实现后使用 [sync-change-review](../sync-change-review/SKILL.md) 检查本次改动，再使用 [sync-pre-submit](../sync-pre-submit/SKILL.md) 整理交付证据；复用仍然有效的检查结果。

交付说明应包含目标版本、已确认的依赖及来源、关键兼容性修改、实际检查结果、JAR 版本与路径，以及具体未验证项。移植流程本身不要求部署 Cloudflare 或发布 Release。
