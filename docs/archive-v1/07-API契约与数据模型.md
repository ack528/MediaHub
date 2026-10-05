# 07 · 网关 API 契约与数据模型

> 适用于方案 B 和方案 C 的服务端("MediaHub 网关")。方案 A 不用本文档,客户端直接调 Jellyfin API。
> 设计原则:**客户端只知道"对话(Dialog)"和"消息(Item)"**,不知道磁盘路径,也不知道后端是 Jellyfin 还是自研转码。

## 1. 概念映射

| Telegram | 本系统 | 说明 |
|---|---|---|
| 聊天分组(Chat Folder 标签页) | **盘符/根目录**(Root) | 顶部标签:全部 / D: / E: …… |
| 群聊 / 超级群 | **文件夹(Dialog)** | 默认:直接含媒体文件的文件夹成为对话 |
| 话题(Forum Topic) | **子文件夹** | 父文件夹下的对话入口,可选"递归合并"模式 |
| 消息 | **媒体文件(Item)** | 一个文件一条消息 |
| 消息时间 | 拍摄时间 `taken_at`(无则修改时间) | 可切换为 `modified` / `created` |
| 共享媒体(图片/视频/文件/音乐 标签) | `types` 过滤 | photo / video / gif / audio / file |
| 未读数 | 该对话中晚于 `last_read` 的条目数 | 新增文件自动成为"新消息" |
| 置顶 / 归档 / 静音 | `pinned` / `archived` / `muted` | 每个用户一份 |
| 消息 ID 稳定 | `id` 由卷 GUID + NTFS FileID 派生 | 改名、移动后仍稳定(见 §4) |

## 2. 通用约定

- Base:`/api/v1`,JSON(UTF-8);HTTP/2;TLS 必选(自签证书由客户端 TOFU 固定指纹,见 06 号文档)。
- 认证:`Authorization: Bearer <access_token>`。**不接受 URL 里的 `api_key`**(会进日志、代理和 Referer)。
- ID:64 位整数,JSON 里一律用**字符串**。
- 时间:RFC 3339 UTC;`taken_at_local` 另给出无时区的本地墙钟时间用于分组显示。
- 错误:`{"code":"auth.expired","message":"...","retryAfterMs":0}`;`401` 触发客户端用 refresh token 静默续期一次。
- 缓存:所有媒体 URL 带 `v=<version>`(由条目指纹派生),响应 `Cache-Control: public, max-age=31536000, immutable`,并带 `ETag`。
- 游标:不透明 base64,内含 `(sortKeyValue, id, dir)`。**客户端不得解析**。

## 3. 接口

### 3.1 服务器与认证
```
GET  /server/info                  (无需认证) → {name, version, apiVersion, authRequired, features[]}
POST /auth/login                   {username, password, device:{id,name,model,os}} →
                                   {accessToken, expiresIn, refreshToken, user:{id,name,role}}
POST /auth/refresh                 {refreshToken} → {accessToken, expiresIn, refreshToken}   (轮换,旧的立即失效)
POST /auth/logout                  使该设备的 refresh token 失效
GET  /me/devices   DELETE /me/devices/{id}      设备管理,可远程踢出
```
- 登录限流:同 IP + 用户名 5 次失败后指数退避。密码哈希:argon2id。
- `/server/info` 用于首次启动时校验地址是否正确、是否为本系统。

### 3.2 对话
```
GET /dialogs?group={all|pinned|archived|root:<rootId>}&sort={last|name}&limit=50&cursor=
 → {dialogs:[Dialog], nextCursor}

GET /dialogs/{id}                  → Dialog
GET /dialogs/{id}/topics           → 子文件夹列表(Dialog[])
```
`Dialog`:
```json
{
  "id":"7301...", "parentId":"7300...", "rootId":"3",
  "title":"2023-京都", "pathDisplay":"D:\\旅行\\2023-京都",
  "counts":{"photo":1832,"video":41,"gif":3,"audio":0,"file":12},
  "last":{"id":"...","type":"photo","name":"IMG_9021.heic","takenAt":"...","thumbhash":"3OcRJYB4d3h/iIeHeEh3eIhw+j3A","w":4032,"h":3024},
  "cover":["<itemId>","<itemId>","<itemId>","<itemId>"],
  "topicsCount":6,
  "state":{"pinned":false,"archived":false,"unread":12,"lastReadTakenAt":"..."},
  "version":"a1b2c3"
}
```
- `last` 与 `cover` 内联,**列表页零额外请求**(方案 A 里这是 N+1,是我们要避免的)。

### 3.3 历史(核心)
```
GET /dialogs/{id}/history
    ?sort={taken|modified|created|name|size|type}   默认 taken
    &dir={desc|asc}                                 默认 desc(最新在前;UI 倒置后最新在底部)
    &types=photo,video,gif,audio,file               默认 photo,video,gif
    &limit=60                                       上限 200
    &cursor=<next|prev>                             续翻
    &around=<itemId>  或 &aroundDate=2023-05-01     围绕某条/某天取一页,并返回 rank
    &recursive=false                                是否包含子文件夹
 → {
     "items":[Item], "nextCursor":"...", "prevCursor":"...",
     "total": 1888,            // 当前过滤条件下总数,用于滚动条
     "firstRank": 940          // items[0] 在整个有序集中的序号(0 起),用于定位与占位
   }
```
- 排序键稳定性:`name` 用**自然排序键**(预计算 `name_sort`,数字按位补零,`2.jpg < 10.jpg`);所有排序都以 `id` 作次键,保证游标无重复无遗漏。
- 语义对照 Telegram `getHistory`:`cursor` ≈ `offset_id`,`around` ≈ `add_offset` 为负数的"围绕"取法。

`Item`:
```json
{
  "id":"...", "dialogId":"...", "name":"IMG_9021.heic", "ext":"heic",
  "type":"photo", "mime":"image/heic", "size":2481923,
  "w":4032, "h":3024, "rotation":0,
  "takenAt":"...", "modifiedAt":"...", "createdAt":"...",
  "durationMs":null,
  "thumbhash":"3OcRJYB4d3h/iIeHeEh3eIhw+j3A",
  "v":"9f3c1a",
  "video":{"container":"mkv","vcodec":"hevc","profile":"Main 10","bitrateKbps":8200,"hdr":"HDR10","acodecs":["dts","aac"],"subs":2},
  "flags":{"animated":false,"live":false,"corrupt":false}
}
```
- **`w`/`h` 必须返回**(已含 EXIF 旋转后的显示尺寸):客户端据此在图片到达前就占好位置,滚动不抖。
- `thumbhash`:约 25 字节的 base64,约 35 字符。

### 3.4 日期桶与跳转
```
GET /dialogs/{id}/buckets?by={month|day|year}&sort=taken&types=...
 → [{"key":"2023-05","count":412,"offset":1230}, ...]
```
- 客户端据此画"快速滚动条 + 日期气泡"和"跳转到某日"。`offset` 是该桶首项的 rank,配合 `history?aroundDate=` 使用。
- 参考 Immich 的 timeline bucket 与 Memories 的按日分组。

### 3.5 搜索
```
GET /search?q=关键词&dialog=<id|空=全局>&types=&limit=50&cursor=
```
SQLite FTS5(trigram 分词器)处理文件名子串,含中文;结果按相关度后按时间。

### 3.6 媒体字节
```
GET /media/{id}/thumb?w={128|256|512|1024|2048}&fmt=webp&v=
    宽度只允许固定档位(提高缓存命中)。服务端生成完才返回(最长阻塞 30s),
    超时返回 503 + Retry-After。请求被客户端取消时,若任务尚未开始则从队列移除。
GET /media/{id}/render?max=4096&fmt={webp|jpeg}&v=
    大图转码版本:用于原格式客户端解不了的图(RAW/HEIC/JXL/TIFF/PSD...)。
GET /media/{id}/file            原文件,支持 Range / If-Range;Content-Type 准确
GET /media/{id}/poster?w=       视频封面帧(同 thumb 的缓存与优先级规则)
GET /media/{id}/preview.mp4     可选:3 秒 480p 无声循环预览,用于可见时自动播放
GET /media/{id}/sprite.json     {interval, tileW, tileH, cols, rows, count, urls[]}
GET /media/{id}/sprite/{n}.jpg  进度条预览雪碧图
```

### 3.7 播放
```
POST /media/{id}/playback
 请求: {
   "caps": {
     "containers": ["mp4","mkv","webm","ts"],
     "video": [{"codec":"h264","maxProfile":"High","maxLevel":"5.1","maxW":3840,"maxH":2160,"maxFps":60,"hdr":["hdr10"]},
               {"codec":"hevc","maxW":3840,"maxH":2160},{"codec":"av1","maxW":1920,"maxH":1080}],
     "audio": ["aac","opus","ac3","eac3","mp3","flac"],
     "subtitles": ["vtt","srt","ass"]
   },
   "network": {"type":"wifi|cellular|lan|unknown", "maxBitrateKbps": 4000, "estimatedKbps": 12000},
   "prefer": "auto|direct|lowdata",
   "startMs": 0, "audioIndex": null, "subtitleIndex": null
 }
 响应: {
   "sessionId":"...", "mode":"direct|remux|transcode",
   "url":"/media/123/file" | "/hls/<sess>/master.m3u8",
   "mime":"video/mp4" | "application/vnd.apple.mpegurl",
   "reason":["audio:dts unsupported -> transcode audio","container:mkv -> remux"],
   "bitrateKbps": 2500, "variants":[{"label":"720p","kbps":2500},{"label":"480p","kbps":900}],
   "audioTracks":[...], "subtitles":[{"index":2,"lang":"zh","format":"vtt","url":"..."}],
   "expiresAt":"..."
 }
POST   /playback/{sessionId}/progress   {positionMs, paused, buffering}      (约 10s 一次,也供服务端判断空闲)
POST   /playback/{sessionId}/error      {code, detail}                       (客户端解码失败 → 服务端降级重判)
DELETE /playback/{sessionId}            结束并立即杀掉 ffmpeg 进程
```
- `reason` 数组用于调试,UI 可在"播放信息"里展示。
- 带宽控制:`network.maxBitrateKbps` 与 `prefer=lowdata` 决定起始档位;HLS 提供多档 `variants`,播放器自适应。
- 方案 C 中,`url` 实际指向网关对 Jellyfin 的**反向代理路径**,客户端无需知道 Jellyfin 的存在。

### 3.8 实时事件(SSE)
```
GET /events    text/event-stream
  event: item.added      data: {"dialogId":"...","count":3}
  event: dialog.updated  data: {"id":"...","version":"..."}
  event: index.progress  data: {"root":"D:","scanned":183000,"total":null}
```
- 新文件落盘后几秒内,对应对话出现"新消息"并让未读数增加。体验上与聊天一致。

### 3.9 用户状态与管理
```
PUT  /dialogs/{id}/state   {pinned?, archived?, muted?, lastReadTakenAt?, view?:{sort,dir,mode,types}}
GET  /admin/index          索引状态、各盘队列、缩略图缓存占用
POST /admin/rescan         {rootId?, path?}
GET/PUT /admin/roots       根目录配置(启用、IO 分组、排除规则)
GET/POST/DELETE /admin/users
```

## 4. 稳定 ID 与路径安全

- **卷标识**:用 Windows **卷 GUID**(`\\?\Volume{...}\`)登记根目录,盘符只是显示名。盘符重新分配后不会错位。
- **条目 ID**:`hash64(volumeGuid, ntfsFileId)`。NTFS 文件 ID(FileReferenceNumber)在重命名、移动后不变,因此用户的"已读位置、置顶"和缓存都不会因整理文件夹而丢失。非 NTFS 或取不到文件 ID 时回退为 `hash64(volumeGuid, relPath)`。
- **对话 ID**:同理对目录取文件 ID。
- **API 不接受任何路径参数**,只接受 ID。服务端在打开文件前把 `relPath` 规范化并校验仍位于根目录内(防 `..`、符号链接与联接点逃逸),文件句柄以只读、共享读模式打开。

## 5. SQLite 数据模型

存放位置:`I:\MediaHub\db\hub.sqlite`(WAL 模式)。**不放 C:(仅 33.5GB 可用),不放任何媒体盘。**

```sql
CREATE TABLE roots(
  id INTEGER PRIMARY KEY, volume_guid TEXT NOT NULL, mount_hint TEXT, label TEXT,
  enabled INTEGER DEFAULT 1, io_group TEXT,            -- io_group:同一物理盘的根归一组,限流用
  usn_cursor INTEGER, last_full_scan INTEGER
);

CREATE TABLE dialogs(                                  -- 文件夹
  id INTEGER PRIMARY KEY, root_id INTEGER, parent_id INTEGER, file_id INTEGER,
  rel_path TEXT, title TEXT, title_sort TEXT,
  cnt_photo INTEGER, cnt_video INTEGER, cnt_gif INTEGER, cnt_audio INTEGER, cnt_file INTEGER,
  cnt_recursive INTEGER, last_item_id INTEGER, last_taken INTEGER, version INTEGER
);

CREATE TABLE media(
  id INTEGER PRIMARY KEY, dialog_id INTEGER, root_id INTEGER, file_id INTEGER,
  name TEXT, name_sort TEXT, ext TEXT, type INTEGER,   -- 0 photo 1 video 2 gif 3 audio 4 file
  size INTEGER, mtime INTEGER, ctime INTEGER, taken_at INTEGER,   -- 毫秒,UTC
  w INTEGER, h INTEGER, rot INTEGER, duration_ms INTEGER,
  container TEXT, vcodec TEXT, acodecs TEXT, bitrate INTEGER, hdr TEXT,
  thumbhash BLOB, fingerprint TEXT,                    -- size+mtime+头 64KB 哈希,用来判定是否需要重算
  state INTEGER, err TEXT, probe_json TEXT,
  jf_item_id TEXT                                      -- 方案 C:对应的 Jellyfin Item ID,可空
);
CREATE INDEX ix_m_taken ON media(dialog_id, type, taken_at DESC, id DESC);
CREATE INDEX ix_m_name  ON media(dialog_id, type, name_sort, id);
CREATE INDEX ix_m_size  ON media(dialog_id, type, size, id);
CREATE INDEX ix_m_ext   ON media(dialog_id, ext, id);
CREATE VIRTUAL TABLE media_fts USING fts5(name, content='media', content_rowid='id', tokenize='trigram');

CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT UNIQUE, pw_hash TEXT, role TEXT, created INTEGER);
CREATE TABLE devices(id TEXT PRIMARY KEY, user_id INTEGER, name TEXT, refresh_hash TEXT, last_seen INTEGER);
CREATE TABLE acl(user_id INTEGER, root_id INTEGER, dialog_id INTEGER);  -- 可选:限制可见子树
CREATE TABLE user_dialog(user_id INTEGER, dialog_id INTEGER, pinned INTEGER, archived INTEGER,
  muted INTEGER, last_read_taken INTEGER, view_json TEXT, PRIMARY KEY(user_id, dialog_id));

CREATE TABLE jobs(id INTEGER PRIMARY KEY, kind TEXT, media_id INTEGER, size INTEGER,
  prio INTEGER, state INTEGER, attempts INTEGER, err TEXT, not_before INTEGER);
CREATE INDEX ix_jobs ON jobs(state, prio DESC, id);
```

### 容量估算(需实测校正)
| 项 | 估算 | 说明 |
|---|---|---|
| media 行 | 约 300–500 B/行,500 万行 ≈ 2–2.5 GB | 含索引更大,I: 盘放得下 |
| thumbhash | 约 28 B/项 | 500 万 ≈ 140 MB |
| 256px WebP 缩略图 | 约 8–15 KB | 100 万张 ≈ 10–15 GB |
| 512px WebP | 约 25–45 KB | **不全量预生成**,按需 + LRU,上限如 60 GB |
| 视频封面 | 约 15–30 KB | 按需 |
| HLS 片段缓存 | 上限 50–100 GB,放 K: | LRU,会话结束即删 |

> 如果对 500 万张全量预生成 256 + 512 两档,约 200 GB,会占满 I: 的剩余空间(200GB)。所以策略是:**ThumbHash 全量预生成(便宜),256 档对"最近浏览/最近新增"的对话预热,其余按需生成。**

## 6. 作业队列与优先级

| 优先级 | 来源 | 例子 |
|---|---|---|
| P0 | 客户端正在等待的请求(可见项) | `thumb` 按需 |
| P1 | 客户端预取(屏幕前后各一屏) | 取消即出队 |
| P2 | 新增文件(SSE 事件触发) | 新照片的 thumbhash + 256 |
| P3 | 夜间预热 | 最近浏览对话的 256 档、视频封面 |
| P4 | 全库补全 | thumbhash、探测元数据 |

约束:
- 每个 `io_group`(同一物理盘)同时只允许 1–2 个读 IO 型作业,避免机械盘(2.72TB 级 HDD)随机读被打爆。
- 单飞(single-flight):同一 `(media_id, size)` 的并发请求合并为一个作业。
- 重试:最多 3 次,指数退避;持续失败写入 `err` 并在 UI 上显示占位图标,而不是无限转圈。
- 作业的 ffmpeg/vips 子进程以**低权限账户 + 只读媒体盘 ACL** 运行(见 03 号文档)。

## 7. 版本与兼容

- `GET /server/info` 返回 `apiVersion`(整数)。客户端声明支持范围,不兼容时给出"请升级服务器/应用"。
- 新增字段向后兼容;删除字段先走一个版本的弃用期。
