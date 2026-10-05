# 04 · 方案 C:Jellyfin 做视频后端 + 自研网关做"文件夹=群聊"(推荐)

> **一句话**:自研网关(MediaHub)负责**索引、对话模型、所有图片格式、统一 API 与认证**;视频的**remux/转码交给 Jellyfin**(它的 ffmpeg 经验最成熟)。客户端只连网关一个地址、一套账号。
> **定位**:在"满足需求的完整度"与"工作量/风险"之间最平衡。API 与客户端与方案 B 完全相同(07、06 号文档),所以以后可以把 Jellyfin 换成自研转码,**客户端不用改**。

## 1. 为什么是混合

| 需求 | A(纯 Jellyfin) | B(全自研) | **C(混合)** |
|---|---|---|---|
| 文件夹=群聊,列表零 N+1 | ✗ | ✓ | ✓ 网关自有索引 |
| HEIC/AVIF/RAW/JXL 等图片 | ✗ | ✓ | ✓ 网关 libvips 管线 |
| 日期桶、跳转、自然排序、ThumbHash | ✗ | ✓ | ✓ |
| 视频转码稳定性 | ✓ 成熟 | ✗ 自己趟坑 | ✓ 复用 Jellyfin |
| 工作量 | 最小 | 最大 | 中 |

## 2. 总体架构

```
┌──── Android(06 号文档,HubBackend)────┐
│ 只连网关;不知道 Jellyfin 存在         │
└─────────────┬──────────────────────┘
              │ HTTPS/HTTP2(唯一对外入口)
┌─────────────▼──────────────────────┐
│ MediaHub 网关(Windows 服务)               │
│  认证 / 对话 / 历史 / 桶 / 搜索 / SSE            │
│  索引器 + 图片管线 + 封面/sprite/预览(03 §3–4)    │
│  播放决策器:direct 自己出;remux/transcode 委托   │
│        ┌──────────────┴───────────────┐    │
│        │ VideoEngine(接口)                      │    │
│        │  ├ JellyfinEngine  ← 本方案使用         │    │
│        │  └ FfmpegEngine    ← 方案 B 的视频管线   │    │
└────────┼────────────────────────────┘
         │ 仅回环地址 127.0.0.1:8096,带 API Key
┌────────▼────────────────────────────┐
│ Jellyfin Server(只当转码器用)                       │
│  库:指向与网关相同的媒体目录(只读)                    │
│  关闭:trickplay、章节图、NFO、网络元数据、实时监控      │
└─────────────────────────────────────┘
```
- **Jellyfin 只监听回环地址**,不对外。管理界面只在服务器本机访问。
- 网关持有一个 Jellyfin **API Key**(只存在服务端配置里,客户端永远拿不到)。

## 3. 职责划分

| 功能 | 谁做 | 备注 |
|---|---|---|
| 登录、令牌、设备管理 | 网关 | 网关自有账号体系,与 Jellyfin 账号无关 |
| 对话列表/历史/桶/搜索 | 网关 | 见 07 号文档 |
| 全部图片缩略图、`render` 大图 | 网关 | 所有格式,见 03 §4 |
| 视频封面、ThumbHash、sprite、3 秒预览 | 网关 | 一律网关自己生成,保证风格统一、可控;**不用 Jellyfin 的 trickplay** |
| 视频**直放** | 网关 | Range 读原文件,不经过 Jellyfin |
| 视频**remux / 转码**(HLS) | **Jellyfin** | 网关转译参数、反向代理 |
| 字幕(文本/图形) | Jellyfin | 经网关代理取 WebVTT |
| 播放进度、"继续观看" | 网关 | 存入 `user_dialog` / 进度表 |

## 4. 网关 ↔ Jellyfin 桥接

### 4.1 条目映射
- Jellyfin 库只配成**视频类**内容的来源(与网关相同的物理目录)。网关定期(及收到变更事件时)调用
  `GET /Items?Recursive=true&IncludeItemTypes=Video&Fields=Path&StartIndex=..&Limit=500`,把 `Path → jfItemId` 写入 `media.jf_item_id`。
- 路径匹配需处理盘符大小写、反斜杠、UNC 与长路径前缀(`\\?\`)的差异;匹配不到的视频标记为"仅直放",UI 仍可播放。
- 重命名/移动:网关靠 NTFS 文件 ID 保持稳定 ID,仅需重新同步 `jf_item_id`;Jellyfin 侧靠其自身扫描感知(是否能通过 Webhook 插件实时通知,待验证,否则靠定时同步)。

### 4.2 播放委托流程
```
客户端 POST /media/{id}/playback {caps, net, prefer}
  1. 网关用自己的 ffprobe 结果 + caps 判定:direct / remux / transcode(规则见 03 §5.1)
  2. direct   → 返回 /media/{id}/file(网关自己 Range 服务)
  3. 其它     → 网关把 caps 转成 Jellyfin DeviceProfile,调用
               POST /Items/{jfId}/PlaybackInfo { DeviceProfile, MaxStreamingBitrate, StartTimeTicks, AudioStreamIndex, ... }
               得到 TranscodingUrl(相对 Jellyfin 的 HLS 路径)
  4. 网关把该 URL 改写为 /jf/{sessionToken}/... 的代理地址返回给客户端
客户端 → 网关 /jf/... → (带 API Key)Jellyfin → HLS 播放列表与分片 → 原样回传
```
- **代理必须是流式透传**:支持 `Range`、`Transfer-Encoding`,不整段缓冲到内存,尽早刷新;HTTP/2 下多路复用分片请求。
- Jellyfin 的 HLS 播放列表使用**相对路径**(待验证),这样加前缀代理即可;若含绝对地址或 `api_key` 参数,网关在代理时重写并剥离。
- `sessionToken` 是网关签发的短期、一次性绑定到某个 `playback session` 的随机串,不暴露 Jellyfin API Key。
- 会话结束:`DELETE /playback/{id}` → 网关向 Jellyfin 调用 `DELETE /Videos/ActiveEncodings`(停止对应转码),释放资源。
- 回退:Jellyfin 不可用或该视频映射缺失 → 返回降级结果或错误,UI 显示"当前只能直放/下载"。

### 4.3 带宽与档位
- 把 `net.maxBitrateKbps` 作为 Jellyfin 的 `MaxStreamingBitrate`;`lowdata` 档位设更低的码率与分辨率上限。
- Jellyfin 的 HLS 档位生成能力弱于自建 master playlist(待验证);若 ABR 效果不满意,这是**切换到 FfmpegEngine** 的触发条件之一。

## 5. Jellyfin 配置清单(只当转码器)

| 设置 | 值 | 原因 |
|---|---|---|
| 数据/缓存目录 | `I:\Jellyfin\data` / `I:\Jellyfin\cache` | C: 仅 33.5GB 可用 |
| 转码临时目录 | `K:\Jellyfin\transcode`,并设定清理 | K: 有 339GB 可用 |
| 库监听 | 关闭实时监控;扫描限定在夜间计划任务 | 避免 HDD 被持续访问 |
| trickplay、章节图提取 | **关闭** | 网关自己做;也避开 jellyfin#18152 |
| 保存图片/NFO 到媒体目录 | **关闭** | F:、G:、H: 基本写不进去 |
| 元数据下载器 | 全部关闭 | 私人媒体,无需联网抓取 |
| 硬件加速 | 按显卡启用 NVENC / QSV / AMF | 型号待确认 |
| 同时转码数 | 先限制为 2 | 保护 HDD 与 CPU |
| 监听 | 仅 `127.0.0.1` | 只有网关能访问 |
| 服务账户 | 对媒体盘**只读 ACL** | 与网关一致 |

> **照片不会被 Jellyfin 管理。**Jellyfin 库可以只放视频文件所在目录;即使同一目录里有 HEIC 等被 Jellyfin 忽略的图片也没有影响,因为图片全走网关。(Jellyfin 无法按扩展名排除文件,若库里混入了图片,它只是多扫一些条目,PoC 里实测额外开销。)

## 6. 与方案 B 的关系(迁移路径)
`VideoEngine` 接口:
```
interface VideoEngine {
  Task<PlaybackPlan> Plan(MediaItem item, DeviceCaps caps, NetworkHint net, PlayPrefs prefs);
  Task Stop(string sessionId);
  Task<Stream> Proxy(string sessionPath, HttpRequest req);   // HLS 播放列表、分片、字幕
}
```
- Phase 1:`JellyfinEngine`。
- Phase 3(可选):实现 `FfmpegEngine`(03 §5),与 `JellyfinEngine` 并存,以功能开关按条目灰度;稳定后可撤掉 Jellyfin。
- **客户端与 API 全程不变。**

**变体 C-lite**:若不想维护 Jellyfin,可改用 **go-vod / Memories 的转码思路**做一个更轻的 `FfmpegEngine`,无需库和扫描。代价是自己处理编码、字幕、HDR 细节,即方案 B 的视频部分。

## 7. 风险与对策
| 风险 | 对策 |
|---|---|
| Jellyfin 与网关**双重扫描**同一批 HDD | Jellyfin 只在夜间扫描,关闭监控与生成器;网关的索引已是主入口 |
| 路径映射漂移(改名、盘符变化) | 网关用卷 GUID 与文件 ID;`jf_item_id` 定时校正;映射缺失可降级为直放 |
| Jellyfin 升级改变 API 或 HLS 路径 | 桥接层集中在 `JellyfinEngine`,固定在已验证的 Jellyfin 版本,升级前跑回归样本 |
| 反向代理成为带宽瓶颈 | 透传不缓冲;分片较小;网关与 Jellyfin 同机回环,开销极小 |
| 两个服务的运维负担 | 统一用 Windows 服务 + 开机自启 + 一份运维清单 |
| 许可证 | 网关是独立进程,通过 HTTP 调 Jellyfin(GPL-2.0),不构成链接;分发网关时无需因此采用 GPL |

## 8. 里程碑与工作量(单人)

| 阶段 | 内容 | 人周 | 可交付 |
|---|---|---|---|
| **M0** | PoC(见 00 号文档清单) | 1–1.5 | 关键假设验证 |
| **M1** | 网关:索引器、稳定 ID、认证、对话/历史/桶 API、`thumb`(JPEG/PNG/WebP/HEIC)、`/server/info` | 4 | 用 curl/Postman 能完整浏览 |
| **M2** | 客户端:引导页+登录、对话列表、聊天流/网格、分页缓存、ThumbHash、查看器(图片) | 5 | **第一个可日常使用的版本(图片 + 直放视频)** |
| **M3** | 视频:决策器、直放、JellyfinEngine 桥接与代理、ExoPlayer + 回退 mpv、带宽策略、封面/sprite/预览 | 4.5 | 视频转码与省流量可用 |
| **M4** | 其余格式(RAW、JXL、PSD…)、排序维度、日期跳转、搜索、SSE 新消息、"文件/音频"标签 | 3.5 | 功能完整 |
| **M5** | 性能打磨、压测、安全加固、监控、文档、备份 | 3 | 稳定版 |
| 合计 | | **约 21–22** | 其中客户端约 12 人周 |

> 两人并行(一人网关,一人客户端)可压缩到约 12–14 周日历时间。M2 后的任一时间点,软件都已可用。

## 9. 优缺点
**优点**:满足全部需求点;视频稳定;API 与客户端和长期方案 B 一致,可演进;网关对磁盘行为完全可控。
**缺点**:要维护两个服务;网关与 Jellyfin 的映射需保持同步;Jellyfin 的 ABR 能力受限。
**适用**:**推荐的落地路线**——先用 C 在 3–4 个月内得到可用成品,再视情况演进到 B。
