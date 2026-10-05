# 02 · 方案 A:Jellyfin 原样部署 + 自研 Android 客户端

> **一句话**:服务端完全不写代码,装 Jellyfin;只写 Android 客户端,用 Jellyfin 的 REST API,把"文件夹"呈现成"群聊"。
> **定位**:最快出成果的验证方案,也是 Phase 0 的 PoC 路线。**照片格式覆盖是硬伤。**

## 1. 总体架构

```
┌──────── Android(Kotlin + Compose)────────┐
│ onboarding / dialogs / chat / viewer         │
│ MediaBackend = JellyfinBackend               │
│ Coil · Paging3 · Room · Media3 · libmpv      │
└───────────────┬──────────────────────────┘
                │ HTTPS(经反向代理或 VPN)
┌───────────────▼──────────────────────────┐
│ Jellyfin Server(Windows,服务方式运行)       │
│  库:Home Videos and Photos ×N(按盘或顶层目录) │
│  图片:SkiaSharp 即时缩放 + 磁盘缓存            │
│  视频:jellyfin-ffmpeg 按需 HLS 转码 / 直放      │
│  trickplay、字幕、播放状态                      │
└───────────────┬──────────────────────────┘
                │ 只读
        C: D: E: F: G: H: I: K: L: M:   (媒体盘)
```

## 2. 服务端配置

### 2.1 安装
- Jellyfin Server for Windows(安装包),**以 Windows 服务运行**;数据目录与缓存目录指向 **I:\Jellyfin\data**、**I:\Jellyfin\cache**;**转码临时目录**指向 **K:\Jellyfin\transcode**。
- **不要用 C: 默认目录**(仅 33.5GB 可用;Jellyfin 的元数据、图片缓存、trickplay 很容易吃满)。

### 2.2 库规划
| 做法 | 说明 |
|---|---|
| 库类型 | **Home Videos and Photos**(库里含视频与图片,不抓取网络元数据) |
| 库划分 | 每个盘一个库,或每个顶层文件夹一个库;每个库可挂多个路径 |
| 浏览 | 使用库的**文件夹视图**,即 `ParentId` 逐级进入 |
| 关闭写入 | 关闭"将图片保存到媒体文件夹"、"NFO 保存"、**"trickplay 与媒体放同一目录"**;关闭元数据下载器与实时监控的非必要项 |
| 媒体盘权限 | 给 Jellyfin 服务账户在所有媒体盘**只读 ACL**;F:、G:、H: 本来就几乎写不进去,Jellyfin 若尝试写会报错并重试 |

> **为什么必须关写入**:F:(180KB 可用)、G:(0 字节)、H:(652KB)基本满盘;并且 jellyfin#18152 显示 Jellyfin 的 trickplay 会**先完成 ffmpeg 编码再检查写入权限**,目标不可写时会反复占满 CPU。把 trickplay 存放位置设为 Jellyfin 数据目录而不是媒体旁,是这一方案能稳定运行的前提。

### 2.3 硬件加速
按显卡类型在 Jellyfin 里启用 NVENC / QSV / AMF,启用硬件解码并限制同时转码数(见后文待确认问题:显卡型号未知)。

## 3. 客户端实现要点

### 3.1 认证
```
POST /Users/AuthenticateByName   {"Username":"...","Pw":"..."}
 → {AccessToken, User:{Id,...}, ServerId}
后续请求头:Authorization: MediaBrowser Client="MyTG", Device="Pixel", DeviceId="...", Version="1.0", Token="..."
```
- 一律用 `Authorization` 头;不使用 `api_key` URL 参数。
- 首次启动的地址校验用 `GET /System/Info/Public`(无需认证)。
- `Quick Connect`(官方扫码式登录)可以作为后续增强。

### 3.2 对话列表
- 把"库的根文件夹"映射为聊天分组(盘符标签)。
- 用 `GET /Items?ParentId=<id>&IncludeItemTypes=Folder&Recursive=false&Fields=ChildCount,Path,DateCreated` 逐级列出文件夹;首次同步用 `Recursive=true` 分页(每页 500)拉取全部文件夹,存入 Room。
- **最新一条与封面**:Jellyfin 没有"文件夹的最新媒体"字段,需要对每个可见文件夹再调一次 `GET /Items?ParentId=<id>&Recursive=true&IncludeItemTypes=Photo,Video&SortBy=DateCreated&SortOrder=Descending&Limit=4`。客户端只对**屏幕内可见的行**发起,并写入 Room 缓存(N+1,但被可见性约束)。
- 每类数量:`...&Limit=0` 读 `TotalRecordCount`,按需延迟获取。

### 3.3 对话内容
```
GET /Items?ParentId=<folderId>
          &IncludeItemTypes=Photo,Video
          &SortBy=PremiereDate|DateCreated|SortName|Size?   // SortBy 可选值需实测,Size/Type 能否排序待验证
          &SortOrder=Descending
          &StartIndex=<offset>&Limit=60
          &Fields=Width,Height,DateCreated,MediaSources,Path
```
- **分页是 offset 式(StartIndex)**,不是游标;百万量级的文件夹深翻页性能待验证。Paging 3 的 `PagingSource` 以 offset 为 key 即可。
- `TotalRecordCount` 用于滚动条与占位项。
- **日期跳转**:Jellyfin 无按月分桶接口。可行做法:二分查找 `StartIndex`(逐次取一条看日期),或让用户只在小文件夹里用;这是方案 A 在体验上的一个降级点。
- 文件名排序:`SortBy=SortName`,Jellyfin 的排序键对数字不一定是自然排序(待验证)。

### 3.4 缩略图
```
GET /Items/{id}/Images/Primary?fillWidth=256&fillHeight=256&quality=70&format=Webp&tag=<ImageTag>
```
- `tag` 使内容可长期缓存;`format` 参数选择输出格式(待验证 Webp 在你的 Jellyfin 版本是否可用,否则用 Jpg)。
- 视频封面:Jellyfin 为视频生成 Primary 图(截帧);是否对所有 Home Videos 默认生成取决于库设置里的"提取章节图片/视频图片"任务(待验证),且生成物存入数据目录。
- **没有 ThumbHash**:占位图只能用主色或灰块,或客户端对已下载缩略图本地计算。体验略逊于方案 B/C。

### 3.5 视频播放
```
POST /Items/{id}/PlaybackInfo
  body: { DeviceProfile:{ DirectPlayProfiles, TranscodingProfiles, CodecProfiles ... },
          MaxStreamingBitrate: 4000000, AutoOpenLiveStream:false, ... }
 → MediaSources[0]:{ SupportsDirectPlay, SupportsDirectStream, SupportsTranscoding, TranscodingUrl?, ... }
```
- `DeviceProfile` 由 `MediaCodecList` 生成(与 06 号文档 `DeviceCaps` 同源)。
- 需要转码时 `TranscodingUrl` 指向 `/videos/{id}/main.m3u8?...`(HLS);ExoPlayer 直接吃。
- **带宽控制**:`MaxStreamingBitrate` 即是上限;Jellyfin 的 HLS 档位由服务端按请求生成,ABR 能力弱于方案 B 自建的多档 master playlist(待验证)。
- 进度条预览:`/Videos/{id}/Trickplay/{width}/{index}.jpg`(需 Jellyfin 10.9+,且库启用 trickplay)。
- 上报播放进度 `POST /Sessions/Playing/Progress`,可顺带实现"从上次位置继续"。
- 回退:同 06 号文档 §6.3,切 mpv。

### 3.6 SDK 还是自写
- `jellyfin-sdk-kotlin`(LGPL-3.0)可直接用,省去模型类;但 Android 上相当于静态链接,若将来分发需遵守 LGPL。
- 个人使用不分发:直接用 SDK。要保留分发空间:用 OpenAPI 生成器自行生成最小客户端,只留用到的十几个接口。

## 4. 格式覆盖与已知缺陷(待验证项)

| 项 | 现状(来自调研) | 影响 |
|---|---|---|
| HEIC | **不被"Home Videos and Photos"库索引**(jellyfin#17312,2026-07 提出,未见维护者回复) | iPhone 照片整批缺失 |
| AVIF | 论坛有"不显示"的报告 | 同上 |
| RAW(ARW 等) | 不显示 | 相机原片缺失 |
| 其它冷门格式(TIFF/PSD/JXL…) | 依赖 SkiaSharp 支持,未知 | 需实测 |
| 文档/音频混放 | 照片库不展示非媒体文件 | "文件"标签页做不出来 |
| 插件补救 | `heic-for-jelly` 等第三方插件 | 无保障,升级易失效 |

**所以方案 A 不满足"尽量支持较多的图片格式"。**可以在 PoC 阶段用 Jellyfin 打通视频链路,图片部分转向方案 C 的网关。

## 5. 性能与规模风险(待验证)
- 数百万文件、HDD 阵列:首次扫描耗时、数据库体积、扫描期间对 HDD 的占用。
- `StartIndex` 深翻页(如 50 万)的响应时间。
- Jellyfin 对每个视频提取封面和 trickplay,会长时间占用磁盘 IO(可通过计划任务限制到夜间)。
- 一个文件夹含数万文件时,Android 端的一次性首屏取数。

## 6. 工作量(单人,粗估)
| 模块 | 人周 |
|---|---|
| Jellyfin 部署与调优、PoC | 1 |
| 客户端骨架、登录、模块 | 2 |
| 对话列表 + 聊天流/网格 + 分页缓存 | 3 |
| 查看器(图片 + ExoPlayer) | 2 |
| mpv 回退、带宽策略、设置 | 1.5 |
| 测试与打磨 | 1.5 |
| **合计** | **约 11** |

## 7. 优缺点
**优点**:服务端零开发;视频能力成熟;转码、字幕、播放状态现成;最快见效。
**缺点**:HEIC/AVIF/RAW 缺失;对话列表 N+1;无日期分桶与跳转;排序维度受限;无占位图;不展示"文件"类型;被 Jellyfin 的数据模型与版本变更牵制。
**适用**:只想先把视频链路和 Android 端滚动体验跑通;或确认自己的照片全是 JPEG/PNG。
