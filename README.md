# TongCraft Sync

[![Release](https://img.shields.io/github/v/release/TongCraft/tongcraft-sync?style=flat&color=9C89B8&label=Release&sort=semver&cacheSeconds=300)](https://github.com/TongCraft/tongcraft-sync/releases/latest)
[![CI](https://img.shields.io/github/actions/workflow/status/TongCraft/tongcraft-sync/ci.yml?branch=main&style=flat&label=CI&logo=githubactions)](https://github.com/TongCraft/tongcraft-sync/actions/workflows/ci.yml)
[![Downloads](https://img.shields.io/github/downloads/TongCraft/tongcraft-sync/total?style=flat&color=F4A7B9&label=Downloads)](https://github.com/TongCraft/tongcraft-sync/releases)
[![License](https://img.shields.io/github/license/TongCraft/tongcraft-sync?style=flat&color=8AC6D1)](LICENSE)

![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62B47A?style=flat)
![Fabric](https://img.shields.io/badge/Fabric-Client-DBB88D?style=flat)
![Java](https://img.shields.io/badge/Java-25%2B-E8A87C?style=flat)
![Node.js](https://img.shields.io/badge/Node.js-24.13%2B-93C572?style=flat&logo=nodedotjs)

在游戏里共享 Litematica 投影，通过独立服务同步，**游戏服务器无需安装模组或插件**。

- 共享蓝图、坐标、旋转、镜像和子区域设置，兼容投影打印机。
- 每个人独立控制显示、隐藏和个人别名。
- 正版账号验证、一次性邀请，游戏内管理成员和投影。
- 游戏内浏览 TongCraft 投影素材库、校验下载蓝图；已加入成员可领取一次性网页上传链接。

## 开始使用

从 [Releases](https://github.com/TongCraft/tongcraft-sync/releases/latest) 下载不带 `-sources` 的 JAR，放进客户端 `mods`。

依赖：Fabric Loader **0.19.5+**、Fabric API、Litematica **0.28.8+**、MaLiLib **0.29.6+**，均需适配 **26.2**。Mod Menu 和 Litematica Printer 可选。

进入服务器后按 **H** → 连接设置 → 填写同步服务地址 → 邀请码登录。已注册成员和初始管理员无需邀请码。

**导入蓝图**新建共享投影；**发布选中投影**共享你在 Litematica 中摆好的投影。打印机需自行开启。

## 部署同步服务

复制 `.env.example` 为 `.env`，填写管理员正版 UUID 和同步域名；域名指向主机并开放 80 / 443：

```sh
docker compose up -d --build
```

Windows 本机测试：`./start-sync-local.ps1`，游戏内同步地址填 `http://127.0.0.1:8787`。多人使用需部署可访问的 HTTPS 服务。

## 开发与发布

```sh
./gradlew :client:build
npm --prefix service ci
npm --prefix service test
```

Windows 可用 `./build-client.ps1` 构建。代码、依赖或部署文件更新后，CI 通过才自动发布；仅改文档不发 tag。提交信息加 `[skip release]` 可跳过发布，也可在 Actions 手动勾选发布。Release 附有 JAR 与 SHA-256 校验文件。

[详细指南](docs/GUIDE.md) · [下载模组](https://github.com/TongCraft/tongcraft-sync/releases/latest) · [反馈问题](https://github.com/TongCraft/tongcraft-sync/issues)
