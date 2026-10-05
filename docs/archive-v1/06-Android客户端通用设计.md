# 06 · Android 客户端通用设计

> 方案 A/B/C 共用同一套客户端架构,区别只在最底层的 `Backend` 实现(直连 Jellyfin,或连网关)。方案 D 另见 05 号文档。

## 1. 技术选型

| 层 | 选择 | 理由 | 备选 |
|---|---|---|---|
| 语言/UI | **Kotlin + Jetpack Compose** | 现代、可测试;`LazyVerticalGrid`/`LazyColumn` 配合 Paging 3 足以支撑十万级列表 | 若实测网格掉帧:改 RecyclerView + Glide(Telegram 自己也是自研 View 体系) |
| 架构 | 单 Activity + MVVM/UDF,多模块 | 便于替换 Backend、独立测试 | — |
| 网络 | OkHttp(HTTP/2)+ kotlinx.serialization + Retrofit | 连接复用;请求可取消 | Ktor client |
| 分页/缓存 | **Paging 3 + RemoteMediator + Room** | 本地缓存页,秒开已看过的对话;占位项让滚动条稳定 | — |
| 图片 | **Coil 3** | Compose 友好、协程取消、磁盘/内存缓存 | Glide |
| 占位图 | ThumbHash(参考实现为 Java,自己移植约 100 行 Kotlin) | 内联于列表响应 | BlurHash |
| 大图查看 | **Telephoto** `ZoomableImage` | 子采样,避免大图 OOM | SubsamplingScaleImageView |
| 主播放器 | **Media3 ExoPlayer** + SimpleCache + PreloadManager | 官方维护,HLS、MP4、MKV 直放,硬解 | — |
| 兜底播放器 | **libmpv**(`dev.jdtech.mpv:libmpv`) | ExoPlayer 解不了的格式、ASS 字幕、奇异音轨 | libVLC |
| 音频软解 | `org.jellyfin.media3:media3-ffmpeg-decoder`(GPL-3.0) | DTS/TrueHD/AC3 | 让服务端转音频 |
| 依赖注入 | Hilt 或 Koin | — | — |
| 凭据存储 | Android Keystore 包装的 AES-GCM + DataStore | `EncryptedSharedPreferences` 已被弃用 | — |

- `minSdk 26`(Android 8.0)。`targetSdk` 取最新。
- **客户端只解 JPEG/WebP**:其它格式由服务端转(见 07 号文档 `render`)。这样不依赖机型对 HEIC/AVIF 的支持(AVIF 需 Android 14+)。
- 视频是否能直放,由 `MediaCodecList` 实测得到的 `caps` 决定,而不是写死。

## 2. 模块划分

```
:app                       导航、DI 装配、主题
:core:model                Dialog / Item / PlaybackPlan 等纯数据类
:core:backend-api          interface MediaBackend(登录、对话、历史、桶、播放决策、图片 URL)
:core:backend-hub          网关实现(方案 B/C)
:core:backend-jellyfin     Jellyfin 直连实现(方案 A)
:core:network              OkHttp、认证拦截器、证书固定、Token 续期 Authenticator
:core:database             Room:servers、dialogs、items、remote_keys、view_state
:core:image                Coil 配置、ThumbHash 解码、缓存键规则、滚动速度策略
:core:player               PlayerEngine 接口 + ExoEngine + MpvEngine + 预加载/缓存
:feature:onboarding        首次启动:服务器地址 + 账号密码
:feature:dialogs           对话列表(聊天列表)
:feature:chat              对话内容:气泡流 / 网格 / 共享媒体
:feature:viewer            全屏查看器(图片缩放、视频播放、左右滑动)
:feature:settings          带宽策略、缓存、播放器偏好、证书
```
`MediaBackend` 是唯一的"可替换点":

```kotlin
interface MediaBackend {
    suspend fun serverInfo(baseUrl: String): ServerInfo
    suspend fun login(baseUrl: String, user: String, pass: String, device: DeviceInfo): Session
    fun dialogs(group: DialogGroup, sort: DialogSort): PagingSource<String, Dialog>
    fun history(dialogId: Id, q: HistoryQuery): RemoteMediatorFactory   // 含 around/日期跳转
    suspend fun buckets(dialogId: Id, q: HistoryQuery): List<Bucket>
    fun thumbRequest(item: Item, widthPx: Int): ImageRequestSpec        // URL + 头 + 缓存键
    suspend fun planPlayback(item: Item, caps: DeviceCaps, net: NetworkHint): PlaybackPlan
    suspend fun reportPlayback(session: String, ev: PlaybackEvent)
    val events: Flow<ServerEvent>                                      // SSE,Jellyfin 实现可退化为轮询
}
```

## 3. 首次启动与登录

```
┌ 欢迎页 ─────────────────────────────┐
│ 服务器地址  [ https://192.168.1.20:8443 ]      │  ← 自动补全协议;可粘贴;保存最近使用
│            [ 下一步 ]                           │
└─────────────────────────────────────┘
        │  GET {addr}/api/v1/server/info  (方案 A:/System/Info/Public)
        ▼
  成功 → 显示服务器名称与版本 → 账号/密码页 → POST /auth/login → 进入对话列表
  失败 → 具体提示:无法连接 / 证书不受信 / 不是本系统 / 版本不兼容
```
细节:
1. **地址输入**:未写协议时依次尝试 `https://` 与 `http://`,超时各 3 秒;允许自定义端口。
2. **自签名证书**:首次遇到不受信证书时弹窗显示 **SHA-256 指纹**,用户确认后固定(TOFU);指纹变化则警告并拒绝连接。绝不提供"忽略所有证书错误"的全局开关。
3. **明文 HTTP**:仅当地址为内网私有地址(10/172.16-31/192.168/*.local)时允许,并显示"未加密"徽标。公网地址一律要求 HTTPS。
4. **凭据**:密码**只用于登录请求,不落盘**;保存 access/refresh token(Keystore 包装)。过期时静默续期,续期失败才回到登录页。
5. **多服务器**:`servers` 表可存多个,切换时各自独立缓存。
6. 方案 A:调用 `POST /Users/AuthenticateByName`,认证走 `Authorization: MediaBrowser Client=..., Device=..., DeviceId=..., Version=..., Token=...` 头。图片和视频**一律用请求头带 token,不拼 `api_key`**。
7. 登录页文案提示:"本应用只读访问你的媒体,不会修改服务器上的文件。"(与服务端只读 ACL 一致)

## 4. 界面与 Telegram 的对应

### 4.1 对话列表(聊天列表)
- 顶部标签页 = **盘符**(全部 / D: / E: …),对应 Telegram 的聊天分组。
- 行:圆形头像 = 最新条目缩略图(或 4 宫格拼图);标题 = 文件夹名;副标题 = `🖼 IMG_9021.heic` / `🎞 clip.mp4 · 02:13`;右侧时间 + 未读徽标。
- 长按:置顶、归档、静音、打开所在路径(仅显示)。
- 顶部搜索:全局文件名(FTS5)。
- 有子文件夹的对话显示"话题"入口(进入后先列出子文件夹)。

### 4.2 对话内容:三种视图
1. **聊天流**(默认):纵向气泡流,日期分隔条;媒体气泡按 `w/h` 等比占位;文件名作说明文字;最新在底部,向上滚动加载历史(`reverseLayout`)。
2. **网格**:3–5 列方格,点开即查看器;用于快速浏览。
3. **共享媒体标签**:图片 / 视频 / GIF / 音频 / 文件,对应 `types`。

顶栏菜单:**排序**(拍摄时间 · 修改时间 · 文件名 · 大小 · 类型,升/降序)、**过滤**、**跳转到日期**、**视图切换**。每个对话单独记住排序与视图(`view_state`,同步到服务端 `user_dialog.view_json`)。

右侧**快速滚动条**:拖动时显示日期(时间排序)或首字符(文件名排序)气泡,数据来自 `buckets`。

### 4.3 查看器
- `HorizontalPager` 在当前过滤和排序下左右切换;相邻项预加载。
- 图片:先显示已缓存的 1024 档,再叠加 `render`(≤4096)大图;放大使用 Telephoto 分块。
- 视频:封面帧立即显示 → 播放计划返回后挂载播放器 → 首帧到达后淡出封面。手势:双击快进/快退、上下滑关闭、横滑拖进度并显示 sprite 预览。
- GIF:不下发 GIF,服务端转为 MP4/WebP 循环(体积小得多,同 Telegram 的做法)。

## 5. 滚动驱动加载(核心体验)

目标:**滑到哪、哪里的缩略图才开始加载;滑过去的不浪费带宽。**

### 5.1 三层状态机(每个格子)
```
[占位]  ThumbHash 解码(< 1ms,来自本地 Room,无网络)
   │  进入可视区,且滚动速度 < 阈值
   ▼
[缩略图]  请求 thumb?w=<格子像素宽对齐到档位>   优先级=高
   │  用户点开
   ▼
[大图]    render / file / HLS
```

### 5.2 规则
| 规则 | 做法 |
|---|---|
| 只加载可见 | Compose 在离开组合时自动取消 Coil 请求;服务端收到取消后若作业未开始则出队 |
| 快速滑动不加载 | 监听 `LazyListState` 的滚动速度;超过阈值(例如 > 3 屏/秒)只显示 ThumbHash;减速后再发请求 |
| 预取 | 当前可见区前后各 1 屏,低优先级,速度大时关闭 |
| 档位对齐 | 请求宽度向上取整到 {128, 256, 512, 1024, 2048},换列数不会让缓存失效 |
| 不抖动 | 用 `w/h` 先占位,图片到达不改变布局 |
| 内存 | Coil 内存缓存限制在可用堆的 15–20%;网格用 `RGB_565` 可再省一半(不带透明度的格子) |
| 磁盘缓存 | Coil 磁盘缓存 500MB–2GB,可在设置里调整;LRU |
| 分页 | `pageSize=60`、`prefetchDistance=30`、`maxSize=600`(超出丢弃远端页,靠 Room 再取) |
| 跳转 | 日期跳转调用 `history?aroundDate=`,Paging 以返回的 `firstRank` 重建起点 |
| 取消风暴 | 快速来回滑时用 `debounce(60ms)` 合并可见集变化,再发请求 |

### 5.3 视频预览
- 格子上显示**封面帧 + 时长徽标**,不下载视频。
- 可选"自动播放预览"(默认仅 Wi-Fi):格子 **完整可见且静止 300ms** 后,播放 `preview.mp4`(3 秒 480p,约 100–200KB)。同时最多 **2** 个播放器实例,来自共享池,离屏立即释放。
- 点开视频:见 §6。

## 6. 播放管线

```
点击视频
  → planPlayback(item, caps, net)            ≤ 100ms(服务端已缓存 ffprobe 结果)
       mode = direct | remux | transcode
  → PlayerEngine.prepare(plan)               ExoPlayer(默认)/ mpv(用户选或回退)
  → 封面帧淡出,首帧时间目标 < 1.5s(局域网),< 3s(远程)
```
### 6.1 能力探测 `DeviceCaps`
启动时用 `MediaCodecList` 枚举解码器,得出每个编码的最大档次、分辨率、帧率及 HDR 能力;缓存并随系统更新刷新。含 ffmpeg 扩展后额外声明可软解的音频格式。

### 6.2 带宽策略(满足"保持较小带宽")
| 档位 | 行为 |
|---|---|
| 自动(默认) | Wi-Fi 且服务器在局域网 → 直放优先;蜂窝或经 VPN/公网 → 限制到 4 Mbps 以内,HLS 多档自适应 |
| 省流量 | 起始 480p / 约 900 kbps;不预取下一个视频;不自动播放预览 |
| 原画 | 只要能直放就直放 |
- 播放器反馈实测带宽(`TransferListener`),大幅低于计划码率时通过 `error`/`progress` 通知服务端,服务端可切换到更低一档。
- 缓冲:`bufferForPlaybackMs ≈ 1000`,`bufferForPlaybackAfterRebufferMs ≈ 2500`,`maxBufferMs ≈ 30000`,兼顾起播速度与卡顿。
- `SimpleCache` 512MB,让拖回去重看的片段不重复下载。
- `PreloadManager`:在查看器里预载相邻视频的前几秒(省流量模式关闭)。

### 6.3 播放失败回退
```
ExoPlayer 报解码/格式错误
  → POST /playback/{id}/error {code}      服务端排除该编码,重新判定为 remux/transcode
  → 若仍失败 → 切换 mpv 引擎(用户可在设置里设为"首选")
  → 仍失败 → 提示"无法播放",提供"用外部播放器打开"(把 URL 与头交给系统 Intent)
```

## 7. 本地数据库(Room)

```
servers(id, name, baseUrl, certFingerprint, lastUser)
sessions(serverId, userId, accessEnc, refreshEnc, expiresAt)
dialogs(serverId, id, parentId, rootId, title, counts, lastItemJson, cover, version, state)
items(serverId, dialogId, id, sortKeyTaken, sortKeyName, ..., thumbhash, w, h, json)
remote_keys(dialogId, queryHash, itemId, prevKey, nextKey)       -- Paging RemoteMediator
view_state(dialogId, sort, dir, mode, types, anchorItemId, anchorOffsetPx)
```
- 打开已看过的对话:先从 Room 显示,同时用 `version`/ETag 询问是否有变化;SSE 的 `item.added` 增量合入。
- 记住阅读位置(锚点条目 + 像素偏移),再次进入回到上次位置(对应 Telegram 的"从上次读到的位置继续")。

## 8. 性能预算与测试

| 指标 | 目标 | 方法 |
|---|---|---|
| 网格滚动 | p95 帧耗时 < 16ms,卡顿帧 < 1% | Macrobenchmark `FrameTimingMetric` |
| 冷启动到对话列表 | < 1.5s(有缓存) | Macrobenchmark `StartupTimingMetric` |
| 滚动时内存 | 峰值堆 < 256MB(10 万条列表) | Profiler |
| 缩略图首屏 | 局域网 < 400ms | 服务端 + 客户端埋点 |
| 起播 | 见 §6 | 播放事件埋点 |

测试矩阵:至少 3 台设备(低端 2GB、中端、旗舰),Android 8/11/14;网络条件:局域网、限速 5 Mbps、200ms RTT、断网重连。
单元/集成:`MediaBackend` 用假实现回放固定数据;游标边界(升序/降序/名称自然排序/跨桶);Token 并发续期只触发一次。

## 9. 安全与隐私
- 证书固定(TOFU)+ 明文仅限私网;`networkSecurityConfig` 禁止 cleartext 到公网。
- 截屏保护、最近任务模糊可设为可选("隐私模式")。
- 缓存目录放应用私有目录;提供"退出时清理缓存"。
- 日志中过滤 `Authorization` 头与 URL 查询参数。
