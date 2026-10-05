# 04 · API 与数据模型(v2,局域网单人版)

## 1. 概念映射
| Telegram | 本系统 |
|---|---|
| 聊天分组(标签) | 盘符/根目录 |
| 群聊 | 文件夹(Dialog) |
| 话题 | 子文件夹 |
| 消息 | 媒体文件(Item) |
| 消息时间 | 拍摄时间 `takenAt`(无则修改时间),可切换排序 |
| 共享媒体标签 | `types` 过滤:photo / video / gif / audio / file |
| 未读数 | 晚于 `lastReadTakenAt` 的条目数 |

## 2. 约定
- Base `/api/v1`,JSON(UTF-8),明文 HTTP(局域网)。
- 认证:`Authorization: Bearer <token>`(不接受 URL 参数)。长期令牌,401 → 客户端回登录页。
- ID 为 64 位整数,JSON 里用字符串。
- 错误:`{"code":"...","message":"..."}`。
- 游标不透明,客户端不解析。
- 媒体 URL 带 `v=<指纹>`,响应 `Cache-Control: public, max-age=31536000, immutable` + `ETag`。

## 3. 接口

### 3.1 服务器与认证
```
GET  /server/info                 → {name, version, apiVersion}            (无需认证)
POST /auth/login   {username,password,deviceName} → {token, expiresAt, user}
POST /auth/logout
```

### 3.2 对话
```
GET /dialogs?group={all|pinned|archived|root:<id>}&sort={last|name}&limit=50&cursor=
 → {dialogs:[Dialog], nextCursor}
GET /dialogs/{id}             → Dialog
GET /dialogs/{id}/topics      → Dialog[](子文件夹)
PUT /dialogs/{id}/state {pinned?, archived?, lastReadTakenAt?}
```
`Dialog`:`id, parentId, rootId, title, pathDisplay, counts{photo,video,gif,audio,file}, last{id,type,name,takenAt,thumbhash?,w,h}, cover[4], topicsCount, state{pinned,archived,unread}, version`
- `last` 与 `cover` 内联,列表页不产生额外请求。照片没有 thumbhash,客户端用本地缩略图缓存或灰块。

### 3.3 历史
```
GET /dialogs/{id}/history
    ?sort={taken|modified|created|name|size|type}  &dir={desc|asc}
    &types=photo,video,gif,audio,file               默认 photo,video,gif
    &limit=60(≤200)  &cursor=<next|prev>
    &around=<itemId> | aroundDate=2023-05-01        围绕某条/某天取一页
    &recursive=false
 → {items:[Item], nextCursor, prevCursor, total, firstRank}
```
- 排序键稳定:`name` 用预计算的自然排序键(数字补零,`2.jpg < 10.jpg`),所有排序以 `id` 为次键。
- 语义对照 Telegram `getHistory`(`offset_id` / `add_offset` / `limit`)。

`Item`:`id, dialogId, name, ext, type, mime, size, w?, h?, rotation, takenAt, modifiedAt, createdAt, durationMs?, thumbhash?(仅视频), v, video{container,vcodec,profile,bitrateKbps,hdr,acodecs[],subs}?, flags{animated,corrupt}`
- `w/h` 为后台元数据阶段才有,可能暂缺(客户端按方格显示)。

### 3.4 日期桶与搜索
```
GET /dialogs/{id}/buckets?by={month|day|year}&sort=taken&types=…  → [{key,count,offset}]
GET /search?q=关键词&dialog=<id|空>&types=&limit=50&cursor=        (FTS5 trigram,支持中文子串)
```

### 3.5 媒体字节
```
GET /media/{id}/file               原文件,Range / If-Range,Content-Type 准确(图片、视频直放、音频、文档)
GET /media/{id}/render?max=4096    兜底转换(RAW 内嵌预览 / 手机不支持的 HEIC·TIFF·JXL 等),结果缓存
GET /media/{id}/thumb              零存储内嵌缩略图(thumbMode=exif 时可用,否则 404)
GET /media/{id}/poster             视频封面 640px WebP(按需生成)
```

### 3.6 播放
```
POST /media/{id}/playback
 请求: {
   "caps": {
     "containers": ["mp4","mkv","webm","ts"],
     "video": [{"codec":"h264","maxProfile":"High","maxLevel":"5.1","maxW":3840,"maxH":2160,"maxFps":60,"hdr":[]},
               {"codec":"hevc","maxW":3840,"maxH":2160,"hdr":["hdr10"]},{"codec":"av1","maxW":1920,"maxH":1080}],
     "audio": ["aac","opus","ac3","eac3","mp3","flac"],
     "subtitles": ["vtt","srt"]
   },
   "quality": "auto|original|1080p|720p|480p",
   "startMs": 0, "audioIndex": null, "subtitleIndex": null
 }
 响应: {
   "sessionId":"…", "mode":"direct|remux|transcode",
   "url":"/media/123/file" | "/jf/<token>/videos/…/main.m3u8", "mime":"video/mp4"|"application/vnd.apple.mpegurl",
   "reason":["audio:dts → aac","container:mkv → fmp4"], "bitrateKbps":3000,
   "audioTracks":[…], "subtitles":[{"index":2,"lang":"zh","format":"vtt","url":"…"}]
 }
POST   /playback/{sessionId}/progress {positionMs}
POST   /playback/{sessionId}/error    {code, detail}
DELETE /playback/{sessionId}          结束会话并停止转码
```

### 3.7 管理
```
GET  /admin/status    → 索引进度、各盘缓存占用与回退告警、Jellyfin 连通性、QSV 自检
POST /admin/rescan    {rootId?, path?}
```

## 4. SQLite 模型(`C:\MediaHub\data\hub.sqlite`,WAL)

```sql
CREATE TABLE roots(id INTEGER PRIMARY KEY, volume_guid TEXT, path TEXT, label TEXT, enabled INTEGER DEFAULT 1,
                   cache_dir TEXT, cache_mode TEXT);               -- cache_mode: on_drive | fallback

CREATE TABLE dialogs(id INTEGER PRIMARY KEY, root_id INTEGER, parent_id INTEGER, file_id INTEGER,
  rel_path TEXT, title TEXT, title_sort TEXT,
  cnt_photo INTEGER, cnt_video INTEGER, cnt_gif INTEGER, cnt_audio INTEGER, cnt_file INTEGER,
  cnt_recursive INTEGER, last_item_id INTEGER, last_taken INTEGER, version INTEGER);

CREATE TABLE media(id INTEGER PRIMARY KEY, dialog_id INTEGER, root_id INTEGER, file_id INTEGER,
  name TEXT, name_sort TEXT, ext TEXT, type INTEGER,                -- 0 photo 1 video 2 gif 3 audio 4 file
  size INTEGER, mtime INTEGER, ctime INTEGER, taken_at INTEGER,
  w INTEGER, h INTEGER, rot INTEGER, duration_ms INTEGER,
  container TEXT, vcodec TEXT, acodecs TEXT, bitrate INTEGER, hdr TEXT,
  thumbhash BLOB, fingerprint TEXT, state INTEGER, err TEXT, probe_json TEXT, jf_item_id TEXT);
CREATE INDEX ix_m_taken ON media(dialog_id, type, taken_at DESC, id DESC);
CREATE INDEX ix_m_name  ON media(dialog_id, type, name_sort, id);
CREATE INDEX ix_m_size  ON media(dialog_id, type, size, id);
CREATE VIRTUAL TABLE media_fts USING fts5(name, content='media', content_rowid='id', tokenize='trigram');

CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT UNIQUE, pw_hash TEXT, created INTEGER);
CREATE TABLE tokens(hash TEXT PRIMARY KEY, user_id INTEGER, device TEXT, expires INTEGER, last_seen INTEGER);
CREATE TABLE user_dialog(user_id INTEGER, dialog_id INTEGER, pinned INTEGER, archived INTEGER,
  last_read_taken INTEGER, PRIMARY KEY(user_id, dialog_id));

CREATE TABLE cache_entries(kind TEXT, media_id INTEGER, root_id INTEGER, path TEXT, bytes INTEGER,
  last_access INTEGER, PRIMARY KEY(kind, media_id));               -- 配额与 LRU 依据
```

### 容量估算(单人,需实测校正)
| 项 | 估算 |
|---|---|
| `media` 行 | 300–500B/行;500 万行 ≈ 2–2.5GB(含索引更大) |
| 视频封面 | 15–35KB/个;每盘配额 50GB 可存 150 万以上,基本不会触顶 |
| 兜底转换结果 | 偶发,计入同一配额 |
| 手机本地缩略图缓存 | 256px WebP 约 8–15KB;2GB ≈ 15–25 万张 |
