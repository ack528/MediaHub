# 03 · Android 客户端设计(Local-TG,v2)

> 个人自用、不分发、局域网。原则:**怎么简单怎么来,先跑通再优化。**

## 1. 技术选型

| 层 | 选择 | 说明 |
|---|---|---|
| 语言/UI | Kotlin + Jetpack Compose | `LazyColumn`/`LazyVerticalGrid` + Paging 3 |
| 工程结构 | **单模块**,按包划分(`ui/`、`data/`、`player/`、`image/`) | 不做多模块,构建快 |
| 依赖注入 | 手写 `AppContainer`(单例持有) | 不用 Hilt/KSP |
| 网络 | OkHttp(HTTP/1.1 或 2)+ kotlinx.serialization(Retrofit 可选) | 局域网,不需要证书固定 |
| 分页 | Paging 3 `PagingSource`(游标 key) | v1 **不用 Room / RemoteMediator** |
| 图片 | Coil 3 + 自定义本地缩略图缓存 | 见 §4 |
| 大图查看 | Telephoto(`ZoomableImage`,子采样) | 原图可能很大 |
| 播放 | Media3 ExoPlayer(主)+ libmpv(`dev.jdtech.mpv:libmpv`,兜底) | GPL 组件可随意用 |
| 音频软解 | `org.jellyfin.media3:media3-ffmpeg-decoder` | DTS/AC3/TrueHD |
| 持久化 | DataStore(服务器地址、令牌、各对话视图偏好、阅读位置) | 令牌用 Keystore 包装的 AES-GCM 加密 |
| 参考实现 | Findroid(登录流程、ExoPlayer/mpv 双后端)、Telegram 源码(只看行为) | 个人使用可直接借鉴代码 |

`minSdk 26`,`targetSdk` 取最新。测试在**真机**(USB 或无线 adb)进行,模拟器仅作 UI 烟测(视频解码能力与真机不同)。

## 2. 首次启动与登录

```
启动 → 本地没有保存的服务器? ──是──▶ 服务器页
                                      地址输入框(例 192.168.1.20:8480,自动补 http://)
                                      [连接] → GET /server/info
                                      成功 → 显示服务器名与版本 → 账号/密码页 → POST /auth/login → 对话列表
                                      失败 → 明确提示:无法连接 / 不是 MediaHub / 版本不兼容
```
- 保存:服务器地址 + 令牌(加密)。之后启动直接进入对话列表;令牌失效(401)时回到账号页并保留地址。
- 密码只用于登录请求,**不落盘**。
- 清单:`networkSecurityConfig` 允许明文 HTTP(应用只面向局域网)。
- 设置页可"更换服务器/退出登录"。

## 3. 界面(与 Telegram 的对应)

### 3.1 对话列表
- 顶部标签 = 盘符(全部 / D: / E: …)。
- 行:头像 = 最新条目图(视频用封面;照片用本地缩略图缓存或灰块)、标题 = 文件夹名、副标题 = `🖼 IMG_9021.jpg` / `🎞 clip.mp4 · 02:13`、右侧时间、"新增"徽标(晚于上次阅读的条目数)。
- 长按:置顶、归档。顶部搜索:全局文件名。
- 子文件夹以"话题"形式进入。

### 3.2 对话内容(三种视图)
1. **聊天流**(默认):气泡纵向排列,日期分隔条,最新在底部,上滑加载更早;媒体气泡按宽高比占位(有 `w/h` 时)。
2. **网格**:3–5 列方格。
3. **共享媒体标签**:图片 / 视频 / GIF / 音频 / 文件。
- 顶栏菜单:**排序**(拍摄时间·修改时间·文件名·大小·类型,升/降序)、**过滤**、**跳转到日期**、视图切换。每个对话单独记住排序/视图/阅读位置。
- 右侧快速滚动条,拖动时显示日期(时间排序)或首字符(名称排序)气泡,数据来自 `buckets`。

### 3.3 查看器
- 横向 Pager,在当前排序/过滤下左右滑动;相邻项预取。
- 图片:显示本地缩略图缓存 → 加载原图 → Telephoto 缩放(分块解码)。
- 视频:封面立即显示 → 拿到播放方案后挂载播放器 → 首帧后淡出。进度条、双击快进/退、上下滑关闭;清晰度菜单(自动/原画/1080p/720p/480p);播放器切换(ExoPlayer ⇄ mpv)。

## 4. 图片只用原图:怎么保证不卡

### 4.1 请求规则
- 所有图片请求 `GET /media/{id}/file`(原图,带 `v=` 缓存键)。服务端判断手机不能解的格式时(客户端启动时上报 `imageFormats`),**客户端改请求 `render`**,无需用户感知。
- 一律通过 OkHttp 拦截器带令牌头,不放 URL。

### 4.2 降采样解码
Coil 根据格子的实际像素尺寸解码(`ImageRequest.size`),网格里不会把 12MP 全尺寸放进内存。

### 4.3 三层缓存
| 层 | 内容 | 大小 |
|---|---|---|
| 内存 | 已解码 Bitmap | 可用堆的 ~15% |
| **本地缩略图缓存**(自建) | 首次解码后存成 256px WebP(q70) | 默认 2GB,LRU,可在设置里清理/关闭 |
| Coil 磁盘缓存 | 原图字节 | 默认 512MB(原图大,只缓存查看器最近看过的) |
- 第一次滑过一个对话较慢(要下载原图);**第二次开始只读本地缩略图,零网络**。这是"图片只用原图"决定下的主要补偿手段。
- 可选:服务端 `thumbMode=exif` 开启时,先请求 `/thumb`(10KB 级内嵌缩略图),命中就不下载原图。

### 4.4 滚动策略
| 规则 | 做法 |
|---|---|
| 只加载可见 | 离开组合时 Coil 自动取消请求 |
| 快速滑动不加载 | 监听滚动速度,超过阈值(如 > 3 屏/秒)只显示灰块/ThumbHash;减速后再请求 |
| 预取 | 当前可见区后方一屏,低优先级;滚动快时关闭 |
| 同盘限流 | 同时在途的原图请求上限 4(OkHttp `Dispatcher.maxRequestsPerHost`),避免打爆机械盘和 Wi-Fi |
| 防布局抖动 | 有 `w/h` 就按比例占位;网格用方形裁剪,不依赖尺寸 |
| 分页 | `pageSize=60`、`prefetchDistance=30`、`maxSize=600` |

### 4.5 视频格子
封面图(640px WebP,约 25KB)+ 时长徽标 + `thumbhash` 先行占位;**不下载视频本体**,点开才请求播放方案。

## 5. 播放管线

```
点击视频 → POST /media/{id}/playback {caps, quality}  (≤100ms)
          → mode = direct | remux | transcode,返回 url
          → ExoPlayer.prepare(url + 令牌头)
          → 首帧后淡出封面(目标:局域网 < 1.5s;转码 < 4s)
```
- **caps**:启动时用 `MediaCodecList` 实测(H.264/HEVC/VP9/AV1 的最大档次、分辨率;HDR 支持),缓存,系统更新后刷新。
- **缓冲**:`bufferForPlaybackMs≈1000`、`bufferForPlaybackAfterRebufferMs≈2500`、`maxBufferMs≈30000`;局域网不需要大缓冲。
- **失败回退**:ExoPlayer 报错 → `POST /playback/{id}/error`,服务端降级重判;仍失败 → 切 mpv;再失败 → 提示"用外部播放器打开"(把 URL 交给系统)。
- **清晰度**:菜单选择后以当前进度重开会话。设置里可设"默认清晰度"和"限制码率"。
- **后台/画中画/投屏**:放到 M5 之后。

## 6. 数据与状态
- DataStore 键:`serverUrl`、`tokenEnc`、`imageFormats`、`deviceCaps`、`dialogView[dialogId] = {sort,dir,mode,types}`、`readAnchor[dialogId] = {itemId, offsetPx}`。
- 对话/条目不落库:每次进入用接口取(局域网快);已读位置只存锚点。若以后觉得冷启动慢,再引入 Room。

## 7. 测试与性能目标

| 指标 | 目标 | 方法 |
|---|---|---|
| 网格滚动 | p95 帧耗时 < 16ms | Macrobenchmark(真机) |
| 冷启动到对话列表 | < 1.5s | Macrobenchmark |
| 滚动内存 | 峰值堆 < 256MB(10 万条目的对话) | Android Profiler |
| 首屏网格(第二次) | < 300ms(全部命中本地缓存) | 手测 + 埋点 |
| 起播 | direct < 1.5s;transcode < 4s | 播放埋点 |
- 单元测试:游标边界(升/降序/自然排序/跨桶)、令牌失效重登、caps 生成。
- 真机验证:你的手机 + 一台旧手机(验证 HEVC/AV1 回退);Wi-Fi 信号差时的重试与取消。

## 8. 实测补充(Android 17 模拟器,2026-10-05)
- **`android.permission.ACCESS_LOCAL_NETWORK`**:targetSdk 37 的应用访问局域网地址必须声明并运行时授权;未授权时所有请求表现为连接失败。启动时在 `AppRoot` 里申请。
- **解码回退链**:ExoPlayer 报 `DECODING_FAILED / DECODER_INIT_FAILED` → 以"软解优先"的 `MediaCodecSelector` 重建播放器并从当前位置继续;仍失败再显示错误(后续接服务端转码与 mpv)。
- **HEIC/HEIF**:Coil(BitmapFactory)对部分文件旋转或解码不可靠 → 失败时改请求服务端 `render`。
- **IO 线程**:`viewModelScope` 默认在主线程,读 OkHttp 响应体必须 `withContext(Dispatchers.IO)`,否则 `NetworkOnMainThreadException`。
- 根节点必须有 `Surface`,否则深色模式下文字默认黑色。

