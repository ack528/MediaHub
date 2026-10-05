# 本地浏览(MediaHub + Local-TG)

把 Windows NAS 上"盘符/文件夹/媒体"的目录,在 Android 上呈现成 Telegram 式的"聊天"来浏览。

- 设计文档:[docs/00-总览与决策.md](docs/00-总览与决策.md)(从这里开始)
- 服务端(Go):`server/`  · Android 客户端(Kotlin,待建):`android/`
- 便携工具链:`tools/`(`powershell -File tools\setup.ps1` 安装,`. .\env.ps1` 进入环境)
- 测试:`cd server; go test ./...` · 冒烟:`testdata\scripts\smoke.ps1`(需先 `serve`)
