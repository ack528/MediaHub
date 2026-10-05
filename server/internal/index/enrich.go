package index

import (
	"context"
	"encoding/json"
	"mediahub/internal/winfs"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"mediahub/internal/classify"
	"mediahub/internal/meta"
)

type pending struct {
	id    int64
	typ   classify.Type
	path  string
	fp    string
	mtime int64
}

// Enrich 补全元数据(阶段 2):照片读 EXIF,视频跑 ffprobe。按对话分组读取,尽量顺序访问磁盘。
func (ix *Indexer) Enrich(ctx context.Context, r Root) error {
	p := ix.prog(r)
	ix.setState(p, "enriching", "")
	atomic.StoreInt64(&p.Enriched, 0)
	var total int64
	_ = ix.DB.QueryRow(`SELECT count(*) FROM media WHERE root_id=? AND state=0`, r.ID).Scan(&total)
	atomic.StoreInt64(&p.EnrichTotal, total) // 用来算进度条和预计剩余时间
	ix.checkIntegrity(ctx, r)                // 已补全过的视频:检查是不是下载中断的不完整文件
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		rows, err := ix.DB.Query(`SELECT m.id, m.type, m.dir_rel, m.name, m.fingerprint, m.mtime
			FROM media m JOIN dialogs d ON d.id=m.dialog_id
			WHERE m.root_id=? AND m.state=0 ORDER BY m.dialog_id, m.id LIMIT 300`, r.ID)
		if err != nil {
			return err
		}
		var batch []pending
		for rows.Next() {
			var pe pending
			var rel, name string
			var t int
			if err := rows.Scan(&pe.id, &t, &rel, &name, &pe.fp, &pe.mtime); err != nil {
				rows.Close()
				return err
			}
			pe.typ = classify.Type(t)
			pe.path = winfs.Path(filepath.Join(r.Path, rel, name))
			batch = append(batch, pe)
		}
		rows.Close()
		if len(batch) == 0 {
			break
		}

		var photos []pending
		for _, pe := range batch {
			switch pe.typ {
			case classify.Photo, classify.GIF:
				photos = append(photos, pe)
			case classify.Video:
				ix.enrichVideo(ctx, pe)
			default:
				ix.markDone(pe, 1, "")
			}
		}
		if len(photos) > 0 {
			ix.enrichPhotos(ctx, photos)
		}
		atomic.AddInt64(&p.Enriched, int64(len(batch)))
	}
	if err := ix.Finalize(r.ID); err != nil {
		return err
	}
	ix.mu.Lock()
	p.State, p.Finished = "idle", time.Now()
	ix.mu.Unlock()
	return nil
}

func (ix *Indexer) markDone(pe pending, state int, errMsg string) {
	_, _ = ix.DB.Exec(`UPDATE media SET state=?, err=?, chk=1 WHERE id=? AND fingerprint=?`, state, nullStr(errMsg), pe.id, pe.fp)
}

// 提示文字(手机端直接显示给用户)
const (
	problemEmpty      = "文件大小为 0 字节(多半是下载失败留下的空文件)"
	problemIncomplete = "文件不完整(下载可能中断),播放到被截断处会停止"
	problemUnreadable = "无法解析,文件可能已损坏"
)

// failReason 视频解析失败时给出更明确的原因:空文件 / 不完整(下载中断) / 其它损坏。
func failReason(path string) string {
	st, err := winfs.Stat(path)
	if err != nil {
		return "文件无法读取(可能已被移走,等待重新扫描)"
	}
	if st.Size() == 0 {
		return problemEmpty
	}
	if bad, _ := meta.MP4Incomplete(path); bad {
		return problemIncomplete
	}
	return problemUnreadable
}

// checkIntegrity 对还没检查过的 MP4 / MOV 做一次廉价的完整性检查(只读盒子头),发现不完整的写上提示。
func (ix *Indexer) checkIntegrity(ctx context.Context, r Root) {
	_, _ = ix.DB.Exec(`UPDATE media SET chk=1 WHERE root_id=? AND chk=0 AND NOT (type=1 AND ext IN ('mp4','m4v','mov','3gp','3g2'))`, r.ID)
	rows, err := ix.DB.Query(`SELECT id, dir_rel, name, state, fingerprint FROM media WHERE root_id=? AND chk=0`, r.ID)
	if err != nil {
		return
	}
	type row struct {
		id    int64
		path  string
		state int
		fp    string
	}
	var todo []row
	for rows.Next() {
		var x row
		var rel, name string
		if rows.Scan(&x.id, &rel, &name, &x.state, &x.fp) == nil {
			x.path = winfs.Path(filepath.Join(r.Path, rel, name))
			todo = append(todo, x)
		}
	}
	rows.Close()
	bad := 0
	for i, x := range todo {
		if i%200 == 0 && ctx.Err() != nil {
			return
		}
		if x.state == 0 { // 还没补全元数据的,等 enrichVideo 里一起检查
			continue
		}
		msg := ""
		if inc, _ := meta.MP4Incomplete(x.path); inc && x.state == 1 {
			msg, bad = problemIncomplete, bad+1
		}
		_, _ = ix.DB.Exec(`UPDATE media SET chk=1, err=COALESCE(?, err) WHERE id=? AND fingerprint=?`, nullStr(msg), x.id, x.fp)
	}
	if bad > 0 {
		ix.Log.Warn("发现不完整的视频文件(下载可能中断过)", "root", r.Path, "count", bad)
	}
}

func nullStr(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (ix *Indexer) enrichPhotos(ctx context.Context, ps []pending) {
	paths := make([]string, len(ps))
	for i, pe := range ps {
		paths[i] = pe.path
	}
	res, err := meta.ExifBatch(ctx, ix.Cfg.Tools.ExifTool, paths)
	if err != nil {
		ix.Log.Warn("exiftool 批处理失败", "err", err)
		for _, pe := range ps {
			ix.markDone(pe, 2, err.Error())
		}
		return
	}
	now := time.Now().Add(24 * time.Hour)
	for _, pe := range ps {
		m := res[pe.path]
		if m == nil {
			ix.markDone(pe, 1, "") // 没有 EXIF 的格式(例如 SVG),保持用文件时间
			continue
		}
		var taken any
		if !m.Taken.IsZero() && m.Taken.Before(now) {
			taken = m.Taken.UnixMilli()
		}
		_, _ = ix.DB.Exec(`UPDATE media SET w=?, h=?, rot=?, taken_at=?, taken_eff=COALESCE(?, taken_eff), state=1
			WHERE id=? AND fingerprint=?`, nz(m.W), nz(m.H), nz(m.Orientation), taken, taken, pe.id, pe.fp)
	}
}

func nz(n int) any {
	if n == 0 {
		return nil
	}
	return n
}

func (ix *Indexer) enrichVideo(ctx context.Context, pe pending) {
	v, err := meta.Probe(ctx, ix.Cfg.Tools.FFprobe, pe.path)
	if err != nil {
		ix.markDone(pe, 2, failReason(pe.path))
		return
	}
	incomplete := ""
	if st, e := winfs.Stat(pe.path); e == nil && st.Size() == 0 {
		incomplete = problemEmpty
	} else if inc, _ := meta.MP4Incomplete(pe.path); inc && meta.IsISOBMFF(strings.TrimPrefix(filepath.Ext(pe.path), ".")) {
		incomplete = problemIncomplete
	}
	pj, _ := json.Marshal(v)
	var taken any
	if !v.CreatedAt.IsZero() && v.CreatedAt.Before(time.Now().Add(24*time.Hour)) {
		taken = v.CreatedAt.UnixMilli()
	}
	_, _ = ix.DB.Exec(`UPDATE media SET w=?, h=?, rot=?, duration_ms=?, container=?, vcodec=?, acodecs=?, bitrate=?, hdr=?,
		probe_json=?, taken_at=?, taken_eff=COALESCE(?, taken_eff), state=1, err=?, chk=1 WHERE id=? AND fingerprint=?`,
		nz(v.W), nz(v.H), nz(v.Rotation), v.DurationMs, v.Container, v.VCodec, strings.Join(v.ACodecs, ","), v.Bitrate, nullStr(v.HDR),
		string(pj), taken, taken, nullStr(incomplete), pe.id, pe.fp)
}
