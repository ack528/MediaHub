# 07 · 服务端桌面管理程序(Tauri 2)

> 目标:给服务端一个像 EcoPaste 那样的图形化设置界面——启动/停止服务、修改常用配置、管理账号、看日志,不用手改 `config.json`。

## 1. 架构

```
┌────────────── MediaHub 管理程序(Tauri 2)──────────────┐
│ 前端:React 19 + Vite + TypeScript(自绘组件,无 UI 库)       │
│   页面 = 概览 / 媒体库 / 缓存与图片 / 视频转码 / 账号安全 / 网络 / 日志 / 关于 │
│   设置界面由 schema.ts 生成(同时驱动"搜索配置项"和"N 项设置") │
│ Rust 后端(src-tauri/src/lib.rs)                                 │
│   读写 config.json(原子写 + .bak) · 启动/停止服务进程           │
│   经"本机管理密钥"查询状态 · 调用 mediahub user … 管理账号       │
│   读日志 · 托盘(关闭窗口=隐藏,服务继续运行)                      │
└──────────┬─────────────────────────────────────────┘
           │ 启动(DETACHED_PROCESS) / HTTP(127.0.0.1 + X-Admin-Key)
┌──────────▼────────────┐
│ mediahub.exe serve(Go,独立进程)│  ←→ 手机
└───────────────────────┘
```
关键设计:
- **服务与界面解耦**:管理程序用 `DETACHED_PROCESS` 启动服务,关闭或退出管理程序不影响服务。服务也可以由任务计划程序开机自启,管理程序随时"接管"查看和修改。
- **配置的唯一来源是 `runtime\mediahub\config.json`**。界面改动 500ms 防抖后原子写入(先写 `.tmp` 再重命名,并保留 `.bak`)。修改后提示"重启服务后生效"(目前所有设置都需重启)。
- **本机管理密钥**:服务每次启动生成随机密钥写入 `<dataDir>\admin.key`;管理程序读取后,以 `X-Admin-Key` 访问 `/api/v1/admin/*`。服务端**只在请求来自回环地址时**接受该密钥(有测试覆盖),手机和局域网其它设备不能使用。
- **账号管理走 CLI**:`mediahub user list/add/passwd/delete`(密码从标准输入读取,不出现在命令行)。服务未运行时也能管理账号。
- **浏览器调试模式**:在普通浏览器打开 Vite 开发服务器时自动使用 mock 后端(`bridge.ts`),可以只调界面。

## 2. 界面(参照 EcoPaste)

| 区域 | 说明 |
|---|---|
| 左侧栏 224px | 品牌区(图标 + 名称 + 版本)、导航(选中项灰底 + 右侧蓝色指示条)、底部"缓存占用"卡片(各盘缓存已用/配额合计 + 进度条) |
| 页头 | 页面图标 + 标题;右侧"搜索配置项"框;下方标签页(蓝色下划线)和右侧"N 项设置"胶囊 |
| 内容 | 圆角卡片;卡片头 = 图标 + 标题 + "N 项";每行 = 图标 + 标题 + 说明 + 右侧控件(开关 / 数值+单位 / 下拉 / 路径+浏览… / 标签) |
| 搜索 | 在所有设置项的标题、说明、id 中匹配,结果页直接可编辑 |
| 横幅 | 保存后且服务在运行时出现"设置已保存,重启服务后生效 [立即重启服务]" |

## 3. 已加入的常用配置参数

| 页面 / 标签 | 设置项 | 对应 config.json |
|---|---|---|
| 概览 | 服务状态与启停、手机连接地址(自动列出本机 IPv4,可复制)、索引进度、重新扫描 | — |
| 媒体库 / 根目录 | 根目录列表(文件夹选择器添加,可改标签) | `roots` |
| 媒体库 / 扫描与过滤 | 索引非媒体文件;排除名称(标签式编辑) | `indexOtherFiles` `exclude` |
| 缓存与图片 / 缓存 | 每盘缓存配额(GB)、盘上保留空间(GB)、备用缓存目录、数据目录;各盘缓存占用与回退告警 | `cache.*` `dataDir` |
| 缓存与图片 / 图片 | 缩略图模式(关闭 / 内嵌缩略图)、转换手机无法显示的格式 | `images.*` |
| 视频转码 / 转码 | 引擎、硬件加速(QSV / 无)、同时转码路数、失败回退软编 | `video.*` |
| 视频转码 / Jellyfin | 地址、API 密钥(密码框) | `jellyfin.*` |
| 视频转码 / 工具路径 | ffmpeg、ffprobe、ExifTool、libvips 路径(留空用项目内默认) | `tools.*` |
| 账号安全 / 账号 | 列表、添加、重置密码、删除 | `mediahub user …` |
| 账号安全 / 登录 | 登录有效期(天) | `auth.tokenDays` |
| 网络 | 监听范围(局域网 / 仅本机)、端口、访问地址、防火墙放行命令 | `listen` |
| 日志 | 最近 300 行、自动刷新、打开日志文件夹 | — |
| 关于 | 版本、目录、配置文件位置 | — |

## 4. 构建与运行

工具链:Rust(复用本机已装的 stable + MSVC 生成工具)、Node 22;依赖缓存全部放进项目(`tools\cargo-home`、`tools\cargo-target`、`tools\npm-cache`)。
```powershell
. .\env.ps1
cd desktop
npm install                 # 首次
npx tauri dev               # 开发(热更新)
npx tauri build --debug     # 打包 exe(bundle 已关闭,产物在 tools\cargo-target\debug)
```
纯界面调试:`npm run dev` 后在浏览器打开 http://127.0.0.1:1420(mock 后端)。

## 5. 验证记录(2026-10-05)
- 前端:`tsc --noEmit` 无错误;浏览器(mock)里逐页检查布局、搜索、控件。
- Rust:`cargo build` 通过;`tauri build --debug --no-bundle` 产出 15MB 的独立 exe,不依赖开发服务器。
- **真实窗口里逐个调用 Rust 命令**(通过 WebView2 远程调试端口,脚本 `testdata/scripts/cdp.mjs`):`get_env`、`read_config`、`write_config`(含 .bak 备份)、`local_ips`、`service_status`、`service_start`(服务以脱离进程启动)、`service_stop`、`admin_rescan`、`list_users` / `add_user` / `set_password` / `delete_user`(含重复账号、过短密码两条错误路径)、`read_log` 全部正常;状态页读到了真实的媒体数、索引进度、各盘缓存占用。
- 在独立 exe 的界面上点"启动"按钮,服务正常拉起,状态由"已停止"变为"运行中"。
- 运行入口:`tools\desktop.ps1 dev|build|run|web`。

## 6. 待办
- 托盘菜单增加"启动 / 停止服务";开机自启开关(任务计划程序)。
- 设置热更新(配额、转码路数等无需重启)。
- 服务启动失败时在界面里展示最后几行日志。
- 打包成安装程序(目前 `bundle.active=false`)。
