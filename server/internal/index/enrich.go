package index

import (
	"context"
	"encoding/json"
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
			pe.path = filepath.Join(r.Path, rel, name)
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
	_, _ = ix.DB.Exec(`UPDATE media SET state=?, err=? WHERE id=? AND fingerprint=?`, state, nullStr(errMsg), pe.id, pe.fp)
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
		ix.markDone(pe, 2, err.Error())
		return
	}
	pj, _ := json.Marshal(v)
	var taken any
	if !v.CreatedAt.IsZero() && v.CreatedAt.Before(time.Now().Add(24*time.Hour)) {
		taken = v.CreatedAt.UnixMilli()
	}
	_, _ = ix.DB.Exec(`UPDATE media SET w=?, h=?, rot=?, duration_ms=?, container=?, vcodec=?, acodecs=?, bitrate=?, hdr=?,
		probe_json=?, taken_at=?, taken_eff=COALESCE(?, taken_eff), state=1 WHERE id=? AND fingerprint=?`,
		nz(v.W), nz(v.H), nz(v.Rotation), v.DurationMs, v.Container, v.VCodec, strings.Join(v.ACodecs, ","), v.Bitrate, nullStr(v.HDR),
		string(pj), taken, taken, pe.id, pe.fp)
}
