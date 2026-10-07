# 本地浏览(MediaHub + LocalBrowse)

把 Windows NAS 上"盘符 / 文件夹 / 媒体"的目录,在 Android 上呈现成 Telegram 式的"聊天"来浏览:一个母文件夹 = 一个群,里面的图片和视频按时间或文件名排成消息流 / 网格。

- **服务端 + 管理程序(MediaHub)**:`server/`(Go,索引 SQLite、封面、转码、账号、HTTPS)和 `desktop/`(Tauri 2 管理程序:媒体库、缓存、转码、账号、日志、软件更新)。
- **手机端(LocalBrowse)**:`android/`(Kotlin + Jetpack Compose),支持多服务器、本地媒体模式、实时超分 / 补帧 / SDR 转 HDR、VLC 式播放器。
- **更新**:服务端每 10 分钟检查 GitHub Releases,发现新版本自动下载、校验、安装并重启服务(新版本起不来会自动回退);手机端在「设置 → 关于 → 检查更新」手动检查并下载安装。更新说明与历史版本见 [CHANGELOG.md](CHANGELOG.md)。
- 设计文档:[docs/00-总览与决策.md](docs/00-总览与决策.md)(从这里开始);安装与打包:[docs/09-安装与打包.md](docs/09-安装与打包.md)。

## 下载

到 [Releases](https://github.com/ack528/MediaHub/releases) 下载:

| 文件 | 说明 |
|---|---|
| `MediaHub-<版本>-portable.zip` | 服务端 + 管理程序便携版(解压到 NAS 任意目录,双击 `MediaHub.exe`) |
| `MediaHub-<版本>-update.zip` | 只含程序本体,给服务端自动更新用(一般不用手动下载) |
| `LocalBrowse-<版本>.apk` | Android 手机端(Android 8.0+) |
| `SHA256SUMS.txt` | 校验和 |

> 发布的 APK **不含** `Lossless.dll`(Lossless Scaling 的版权文件,不能公开分发)。补帧(LSFG)需要你自己有该软件的授权,用 `tools\setup-lsfg.ps1` 自己打包带 DLL 的版本;并且补帧只在高通处理器上启用。

## 开发

- 便携工具链:`tools/`(`powershell -File tools\setup.ps1` 安装,`. .\env.ps1` 进入环境)。
- 测试:`cd server; go test ./...`。
- 打包:`powershell -File tools\package.ps1 -Version <手机端版本>`(自用,APK 带 DLL);发布到 GitHub 用 `-Release`(APK 不带 DLL,另出自动更新包,产物在 `dist\release\`)。服务端 / 管理程序版本取自 `desktop\package.json`。
