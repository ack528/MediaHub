package api

import (
	"runtime/debug"
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"image"
	"image/draw"
	"image/jpeg"
	"mediahub/internal/logx"
	"mediahub/internal/proc"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/jellyfin"
	"mediahub/internal/thumbhash"
)

// Poster 按需生成视频封面(640px WebP),缓存在视频所在盘的 .mediahub\posters。
// 并发控制:全局 2 个、每个根(≈每块物理盘)1 个;同一视频的并发请求合并。
type Poster struct {
	db    *sql.DB
	cfg   *config.Config
	cache *cache.Manager
	// JF 为 nil 表示没有配置 Jellyfin,只能自己生成
	JF *jellyfin.Client

	global chan struct{}
	mu     sync.Mutex
	perRt  map[int64]chan struct{}
	flight map[int64]*call
}

type call struct {
	done chan struct{}
	path string
	err  error
}

func NewPoster(db *sql.DB, cfg *config.Config, c *cache.Manager) *Poster {
	return &Poster{db: db, cfg: cfg, cache: c, global: make(chan struct{}, 2),
		perRt: map[int64]chan struct{}{}, flight: map[int64]*call{}}
}

func (p *Poster) rootSem(id int64) chan struct{} {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.perRt[id]
	if s == nil {
		s = make(chan struct{}, 1)
		p.perRt[id] = s
	}
	return s
}

// PosterResult 是一次封面解析的结果:要么是我们缓存里的文件,要么是 Jellyfin 已生成的图(直接转发,不落我们的缓存)。
type PosterResult struct {
	Path        string // 来源 = own 时有值
	Data        []byte // 来源 = jellyfin 时有值
	ContentType string
	Source      string // own | jellyfin
}

// Resolve 封面来源链:
//  1. 我们自己的缓存里已有 → 直接用;
//  2. posterSource=auto 且 Jellyfin 里这个视频已经有主图 → 转发 Jellyfin 的图(不再存第二份),
//     并顺手补上 ThumbHash;
//  3. 否则自己用 ffmpeg 按需生成并缓存。
func (p *Poster) Resolve(ctx context.Context, mediaID int64) (*PosterResult, error) {
	var rootID int64
	var typ int
	var jfID, jfTag sql.NullString
	var state int
	var size int64
	if err := p.db.QueryRow(`SELECT root_id, type, jf_item_id, jf_img_tag, state, size FROM media WHERE id=?`, mediaID).Scan(&rootID, &typ, &jfID, &jfTag, &state, &size); err != nil {
		return nil, err
	}
	if typ != 1 {
		return nil, errors.New("poster: 不是视频")
	}
	if path, ok := p.cache.Exists(rootID, "posters", mediaID, "webp"); ok {
		return &PosterResult{Path: path, ContentType: "image/webp", Source: "own"}, nil
	}
	if p.JF != nil && p.cfg.Video.PosterSource != "own" && jfID.String != "" && jfTag.String != "" {
		data, ct, err := p.JF.PrimaryImage(ctx, jfID.String, jfTag.String, 640, "Webp")
		if err == nil && len(data) > 0 {
			p.ensureThumbhashFromJellyfin(ctx, mediaID, jfID.String, jfTag.String)
			return &PosterResult{Data: data, ContentType: ct, Source: "jellyfin"}, nil
		}
		// Jellyfin 暂时取不到图(离线、条目被移除等),回落到自己生成
	}
	if state == 2 || size == 0 { // 解析失败 / 空文件:ffmpeg 也生成不了封面,不必每次请求都白跑一次
		return nil, errors.New("poster: 文件损坏或为空")
	}
	path, err := p.Get(ctx, mediaID)
	if err != nil {
		return nil, err
	}
	return &PosterResult{Path: path, ContentType: "image/webp", Source: "own"}, nil
}

// ensureThumbhashFromJellyfin 向 Jellyfin 要一张 32 像素的小图,算出 ThumbHash 存库(只多存约 28 字节)。
func (p *Poster) ensureThumbhashFromJellyfin(ctx context.Context, mediaID int64, jfID, tag string) {
	var th []byte
	if err := p.db.QueryRow(`SELECT thumbhash FROM media WHERE id=?`, mediaID).Scan(&th); err != nil || th != nil {
		return
	}
	data, _, err := p.JF.PrimaryImage(ctx, jfID, tag, 32, "Jpg")
	if err != nil {
		return
	}
	img, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		return
	}
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w == 0 || h == 0 || w > 100 || h > 100 {
		return
	}
	rgba := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(rgba, rgba.Bounds(), img, b.Min, draw.Src)
	_, _ = p.db.Exec(`UPDATE media SET thumbhash=? WHERE id=? AND thumbhash IS NULL`, thumbhash.Encode(w, h, rgba.Pix), mediaID)
}

// Get 返回封面文件路径,必要时生成。调用方取消不会中断生成(结果仍会缓存)。
func (p *Poster) Get(ctx context.Context, mediaID int64) (string, error) {
	var rootID, durationMs int64
	var typ int
	if err := p.db.QueryRow(`SELECT root_id, type, COALESCE(duration_ms,0) FROM media WHERE id=?`, mediaID).Scan(&rootID, &typ, &durationMs); err != nil {
		return "", err
	}
	if typ != 1 {
		return "", errors.New("poster: 不是视频")
	}
	if path, ok := p.cache.Exists(rootID, "posters", mediaID, "webp"); ok {
		return path, nil
	}

	p.mu.Lock()
	c, running := p.flight[mediaID]
	if !running {
		c = &call{done: make(chan struct{})}
		p.flight[mediaID] = c
		go func() {
			defer func() { // 封面生成里 panic 不能让等待它的请求永远卡住
				if rec := recover(); rec != nil {
					c.err = fmt.Errorf("封面生成 panic: %v", rec)
					if lm := logx.Default(); lm != nil {
						lm.Log.Error("封面生成 panic", "media", mediaID, "panic", fmt.Sprint(rec))
						lm.WriteCrash("封面生成", rec, debug.Stack())
					}
					p.mu.Lock()
					delete(p.flight, mediaID)
					p.mu.Unlock()
					close(c.done)
				}
			}()
			c.path, c.err = p.generate(mediaID, rootID, durationMs)
			p.mu.Lock()
			delete(p.flight, mediaID)
			p.mu.Unlock()
			close(c.done)
		}()
	}
	p.mu.Unlock()

	select {
	case <-c.done:
		return c.path, c.err
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

func (p *Poster) generate(mediaID, rootID, durationMs int64) (string, error) {
	rs := p.rootSem(rootID)
	rs <- struct{}{}
	defer func() { <-rs }()
	p.global <- struct{}{}
	defer func() { <-p.global }()

	src, err := MediaPath(p.db, mediaID)
	if err != nil {
		return "", err
	}
	ts := 0.0
	if durationMs > 2000 {
		ts = float64(durationMs) / 1000 * 0.10
		if ts > 120 {
			ts = 120
		}
	}
	// 占位图用的小图尺寸(显示宽高比,长边 32)
	var w, h int
	_ = p.db.QueryRow(`SELECT COALESCE(w,0), COALESCE(h,0) FROM media WHERE id=?`, mediaID).Scan(&w, &h)
	tw, th := 32, 18
	if w > 0 && h > 0 {
		if w >= h {
			tw, th = 32, max(1, (32*h+w/2)/w)
		} else {
			tw, th = max(1, (32*w+h/2)/h), 32
		}
	}

	var raw []byte
	path, err := p.cache.Put(rootID, "posters", mediaID, "webp", func(tmp string) error {
		for _, seek := range []float64{ts, 0} {
			ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
			// 一次解码,两路输出:640px WebP 封面 + 32px 原始 RGBA(用来算 ThumbHash)
			fc := fmt.Sprintf("[0:v:0]split=2[a][b];[a]scale=640:-2:force_original_aspect_ratio=decrease[pa];[b]scale=%d:%d,format=rgba[pb]", tw, th)
			cmd := exec.CommandContext(ctx, p.cfg.Tools.FFmpeg, "-hide_banner", "-v", "error", "-y",
				"-ss", fmt.Sprintf("%.3f", seek), "-i", src, "-filter_complex", fc,
				"-map", "[pa]", "-frames:v", "1", "-c:v", "libwebp", "-quality", "75", "-f", "webp", tmp,
				"-map", "[pb]", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1")
			proc.Hide(cmd)
			var out, errb bytes.Buffer
			cmd.Stdout, cmd.Stderr = &out, &errb
			err := cmd.Run()
			cancel()
			if st, e := os.Stat(tmp); err == nil && e == nil && st.Size() > 0 {
				raw = out.Bytes()
				return nil
			}
			if seek == 0 {
				return fmt.Errorf("ffmpeg poster: %v: %s", err, strings.TrimSpace(errb.String()))
			}
		}
		return errors.New("poster: unreachable")
	})
	if err != nil {
		return "", err
	}
	if len(raw) == tw*th*4 {
		_, _ = p.db.Exec(`UPDATE media SET thumbhash=? WHERE id=?`, thumbhash.Encode(tw, th, raw), mediaID)
	}
	return path, nil
}

// Warm 后台预热:为某个根下尚无 ThumbHash 的视频生成封面。失败的置空串,不再重试。
func (p *Poster) Warm(ctx context.Context, rootID int64) int {
	done := 0
	for {
		rows, err := p.db.Query(`SELECT id FROM media WHERE root_id=? AND type=1 AND state=1 AND thumbhash IS NULL ORDER BY id LIMIT 100`, rootID)
		if err != nil {
			return done
		}
		var ids []int64
		for rows.Next() {
			var id int64
			if rows.Scan(&id) == nil {
				ids = append(ids, id)
			}
		}
		rows.Close()
		if len(ids) == 0 {
			return done
		}
		for _, id := range ids {
			if ctx.Err() != nil {
				return done
			}
			if _, err := p.Resolve(ctx, id); err != nil {
				_, _ = p.db.Exec(`UPDATE media SET thumbhash=x'' WHERE id=? AND thumbhash IS NULL`, id)
				continue
			}
			// 封面早已缓存但库里没有 ThumbHash 的情况:避免死循环
			_, _ = p.db.Exec(`UPDATE media SET thumbhash=x'' WHERE id=? AND thumbhash IS NULL`, id)
			done++
		}
	}
}

// MediaPath 返回媒体文件的绝对路径,并校验仍位于根目录内。
func MediaPath(db *sql.DB, mediaID int64) (string, error) {
	var root, rel, name string
	err := db.QueryRow(`SELECT ro.path, m.dir_rel, m.name FROM media m
		JOIN dialogs d ON d.id=m.dialog_id JOIN roots ro ON ro.id=m.root_id WHERE m.id=?`, mediaID).Scan(&root, &rel, &name)
	if err != nil {
		return "", err
	}
	full := filepath.Join(root, rel, name)
	r, err := filepath.Rel(root, full)
	if err != nil || r == ".." || strings.HasPrefix(r, ".."+string(filepath.Separator)) {
		return "", errors.New("path escapes root")
	}
	return full, nil
}
