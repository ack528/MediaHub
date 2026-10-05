package api

import (
	"bytes"
	"context"
	"fmt"
	"hash/fnv"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"mediahub/internal/config"
	"mediahub/internal/proc"
)

// 内置 HLS 转码(ffmpeg),不依赖 Jellyfin。
//
// 思路与 Jellyfin / Plex 相同:先按视频总时长生成完整的 VOD 播放列表(固定 4 秒一段),
// 段文件在手机请求到时才由 ffmpeg 现转:
//   - 请求的段已经转好 → 直接发;
//   - 请求的段正好在正在转的进度附近 → 等它转出来;
//   - 否则(拖动进度条、从上次位置继续播放、回看)→ 从该段重新启动 ffmpeg(-ss 精确定位)。
//
// 这样拖到任意位置都立刻能播,时间轴与原视频一致,播放器无需知道转码的存在。
const (
	hlsSegSec      = 4  // 每段秒数(与 ffmpeg 的 -hls_time、强制关键帧间隔一致)
	hlsAheadSegs   = 8  // 请求的段在已转进度之后不超过这么多段,就等 ffmpeg 追上,不重启
	hlsLeadSegs    = 45 // 转码进度领先播放位置超过这么多段(≈3 分钟)就暂停 ffmpeg,播放追上来再继续
	hlsKeepBehind  = 200
	hlsIdleSeconds = 90
	hlsWaitSeconds = 45
)

type hlsParams struct {
	MediaID int64
	SID     string
	Height  int // 目标高度上限,0 = 保持原尺寸
	Bitrate int // 视频码率 bps,0 = 默认
	Audio   int // 音频流在源文件里的序号,0 = 第一条音轨
}

func (p hlsParams) key() string {
	return fmt.Sprintf("%d|%s|%d|%d|%d", p.MediaID, p.SID, p.Height, p.Bitrate, p.Audio)
}

func parseHLSParams(id int64, r *http.Request) hlsParams {
	q := r.URL.Query()
	p := hlsParams{MediaID: id, SID: q.Get("sid")}
	if p.SID == "" {
		p.SID = "x"
	}
	p.Height, _ = strconv.Atoi(q.Get("maxHeight"))
	p.Bitrate, _ = strconv.Atoi(q.Get("maxBitrate"))
	p.Audio, _ = strconv.Atoi(q.Get("audio"))
	if p.Height < 0 || p.Height > 4320 {
		p.Height = 0
	}
	if p.Bitrate < 0 {
		p.Bitrate = 0
	}
	if p.Audio < 0 {
		p.Audio = 0
	}
	return p
}

// HLS 管理所有内置转码会话。
type HLS struct {
	cfg *config.Config
	log *slog.Logger
	dir string

	runMu sync.Mutex // 串行化"启动 / 重启 ffmpeg",避免两个会话互相抢路数时死锁
	mu    sync.Mutex
	sess  map[string]*hlsSess

	qsvOnce sync.Once
	qsvOK   bool        // ffmpeg 带 h264_qsv 编码器
	qsvBad  atomic.Bool // 实际使用中 QSV 失败过,以后改用软编
}

type hlsSess struct {
	h     *HLS
	p     hlsParams
	dir   string
	path  string // 源文件
	durMs int64
	segs  int

	mu       sync.Mutex
	cmd      *exec.Cmd
	cancel   context.CancelFunc
	runStart int
	lastErr  string

	last    atomic.Int64 // 最近一次被请求的时间(unix 秒)
	lastReq atomic.Int64 // 最近一次请求的段号
}

// NewHLS 创建管理器并清掉上次运行残留的临时文件。
func NewHLS(cfg *config.Config, log *slog.Logger) *HLS {
	h := &HLS{cfg: cfg, log: log, sess: map[string]*hlsSess{}, dir: filepath.Join(cfg.Cache.FallbackDir, "hls")}
	_ = os.RemoveAll(h.dir)
	_ = os.MkdirAll(h.dir, 0o755)
	go h.janitor()
	return h
}

func (h *HLS) hwEnabled() bool {
	if h.cfg.Video.HWAccel != "qsv" || h.qsvBad.Load() {
		return false
	}
	h.qsvOnce.Do(func() {
		out, err := proc.Hide(exec.Command(h.cfg.Tools.FFmpeg, "-hide_banner", "-encoders")).Output()
		h.qsvOK = err == nil && bytes.Contains(out, []byte("h264_qsv"))
	})
	return h.qsvOK
}

// session 取得(或创建)会话。同一个 sid 切换了画质 / 音轨,旧参数的会话立即结束。
func (h *HLS) session(p hlsParams, path string, durMs int64) *hlsSess {
	key := p.key()
	h.mu.Lock()
	defer h.mu.Unlock()
	if s := h.sess[key]; s != nil {
		return s
	}
	var stale []*hlsSess
	for k, o := range h.sess {
		if o.p.MediaID == p.MediaID && o.p.SID == p.SID {
			stale = append(stale, o)
			delete(h.sess, k)
		}
	}
	for _, o := range stale {
		go o.destroy()
	}
	f := fnv.New64a()
	f.Write([]byte(key))
	segs := int((durMs + hlsSegSec*1000 - 1) / (hlsSegSec * 1000))
	if segs < 1 {
		segs = 1
	}
	s := &hlsSess{h: h, p: p, path: path, durMs: durMs, segs: segs, dir: filepath.Join(h.dir, fmt.Sprintf("%d-%x", p.MediaID, f.Sum64()))}
	_ = os.MkdirAll(s.dir, 0o755)
	s.last.Store(time.Now().Unix())
	h.sess[key] = s
	return s
}

// Stop 结束某个播放(同一个 sid)的全部转码并清理临时文件。
func (h *HLS) Stop(mediaID int64, sid string) {
	h.mu.Lock()
	var del []*hlsSess
	for k, o := range h.sess {
		if o.p.MediaID == mediaID && o.p.SID == sid {
			del = append(del, o)
			delete(h.sess, k)
		}
	}
	h.mu.Unlock()
	for _, o := range del {
		o.destroy()
	}
}

func (s *hlsSess) destroy() {
	s.mu.Lock()
	if s.cancel != nil {
		s.cancel()
	}
	s.cmd, s.cancel = nil, nil
	s.mu.Unlock()
	time.Sleep(300 * time.Millisecond) // 等 ffmpeg 释放文件
	_ = os.RemoveAll(s.dir)
}

func (s *hlsSess) segFile(i int) string { return filepath.Join(s.dir, fmt.Sprintf("seg%05d.ts", i)) }

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// produced 从起点 from 开始连续已转好的段之后的下一段号。
func (s *hlsSess) next(from int) int {
	i := from
	for i < s.segs && fileExists(s.segFile(i)) {
		i++
	}
	return i
}

// janitor 回收空闲会话、暂停领先太多的转码、清理很久以前的段。
func (h *HLS) janitor() {
	t := time.NewTicker(3 * time.Second)
	defer t.Stop()
	for range t.C {
		func() {
			defer func() { _ = recover() }()
			now := time.Now().Unix()
			h.mu.Lock()
			var all []*hlsSess
			for k, s := range h.sess {
				if now-s.last.Load() > hlsIdleSeconds {
					delete(h.sess, k)
					go s.destroy()
					continue
				}
				all = append(all, s)
			}
			h.mu.Unlock()
			for _, s := range all {
				s.mu.Lock()
				if s.cmd != nil && s.next(s.runStart)-int(s.lastReq.Load()) > hlsLeadSegs {
					s.cancel()
					s.cmd, s.cancel = nil, nil
				}
				s.mu.Unlock()
				if beh := int(s.lastReq.Load()) - hlsKeepBehind; beh > 0 {
					for i := beh; i > 0 && i > beh-40; i-- { // 每次最多清 40 段,慢慢清
						_ = os.Remove(s.segFile(i))
					}
				}
			}
		}()
	}
}

// args 组装 ffmpeg 参数(工作目录是会话目录,输出用相对名)。
func (s *hlsSess) args(idx int, hw bool) []string {
	a := []string{"-hide_banner", "-loglevel", "error", "-nostdin"}
	if idx > 0 {
		a = append(a, "-ss", strconv.Itoa(idx*hlsSegSec))
	}
	a = append(a, "-i", s.path, "-map", "0:v:0")
	if s.p.Audio > 0 {
		a = append(a, "-map", "0:"+strconv.Itoa(s.p.Audio))
	} else {
		a = append(a, "-map", "0:a:0?")
	}
	a = append(a, "-sn", "-dn", "-map_metadata", "-1")
	pix := "yuv420p"
	if hw {
		pix = "nv12"
	}
	vf := "format=" + pix
	if s.p.Height > 0 {
		vf = fmt.Sprintf("scale=-2:'min(%d,ih)',format=%s", s.p.Height, pix)
	}
	br := s.p.Bitrate
	if br <= 0 {
		br = 5_000_000
	}
	rate := strconv.Itoa(br)
	if hw {
		a = append(a, "-vf", vf, "-c:v", "h264_qsv", "-preset", "veryfast", "-profile:v", "high",
			"-b:v", rate, "-maxrate", rate, "-g", "250", "-bf", "2",
			"-forced_idr", "1") // 必须:否则 QSV 不会在强制关键帧处插入 IDR,分段时长就和播放列表对不上
	} else {
		a = append(a, "-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "high", "-level", "4.1",
			"-b:v", rate, "-maxrate", rate, "-bufsize", strconv.Itoa(br*2), "-sc_threshold", "0")
	}
	a = append(a,
		"-force_key_frames", fmt.Sprintf("expr:gte(t,n_forced*%d)", hlsSegSec),
		"-c:a", "aac", "-b:a", "160k", "-ac", "2", "-af", "aresample=async=1",
		"-output_ts_offset", strconv.Itoa(idx*hlsSegSec),
		"-f", "hls", "-hls_time", strconv.Itoa(hlsSegSec), "-hls_list_size", "0", "-hls_segment_type", "mpegts",
		"-start_number", strconv.Itoa(idx), "-hls_flags", "independent_segments+temp_file",
		"-hls_segment_filename", "seg%05d.ts", "run.m3u8")
	return a
}

type tailBuf struct {
	mu sync.Mutex
	b  []byte
}

func (t *tailBuf) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.b = append(t.b, p...)
	if len(t.b) > 4096 {
		t.b = t.b[len(t.b)-4096:]
	}
	return len(p), nil
}

func (t *tailBuf) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return strings.TrimSpace(string(t.b))
}

// launchLocked 启动(重启)ffmpeg,从第 idx 段开始转。调用时必须持有 s.mu。
func (s *hlsSess) launchLocked(idx int, hw bool) error {
	if s.cancel != nil {
		s.cancel()
		s.cmd, s.cancel = nil, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	cmd := proc.Hide(exec.CommandContext(ctx, s.h.cfg.Tools.FFmpeg, s.args(idx, hw)...))
	cmd.Dir = s.dir
	errBuf := &tailBuf{}
	cmd.Stderr = errBuf
	cmd.Stdout = io.Discard
	if err := cmd.Start(); err != nil {
		cancel()
		return err
	}
	s.cmd, s.cancel, s.runStart = cmd, cancel, idx
	s.h.log.Info("转码开始", "id", s.p.MediaID, "seg", idx, "height", s.p.Height, "bitrate", s.p.Bitrate, "qsv", hw)
	go func() {
		defer func() { _ = recover() }()
		err := cmd.Wait()
		s.mu.Lock()
		if s.cmd == cmd {
			s.cmd, s.cancel = nil, nil
		}
		s.mu.Unlock()
		if err == nil || ctx.Err() != nil {
			return
		}
		msg := errBuf.String()
		s.mu.Lock()
		s.lastErr = msg
		s.mu.Unlock()
		if hw && !fileExists(s.segFile(idx)) { // 硬件编码没跑起来(驱动 / 核显不可用):以后改用软编,并立即重试
			s.h.log.Warn("QSV 转码失败,改用软件编码", "err", msg)
			s.h.qsvBad.Store(true)
			s.mu.Lock()
			if s.cmd == nil {
				_ = s.launchLocked(idx, false)
			}
			s.mu.Unlock()
			return
		}
		s.h.log.Warn("转码进程异常退出", "id", s.p.MediaID, "seg", idx, "err", msg)
	}()
	return nil
}

// ensure 保证第 idx 段"已有或正在被转"。
func (s *hlsSess) ensure(idx int) error {
	s.h.runMu.Lock()
	defer s.h.runMu.Unlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	if fileExists(s.segFile(idx)) {
		return nil
	}
	if s.cmd != nil && idx >= s.runStart && idx <= s.next(s.runStart)+hlsAheadSegs {
		return nil // 在转了,等就行
	}
	// 需要(重新)启动:先看总路数,超了就停掉最久没动静的那一路
	max := s.h.cfg.Video.MaxTranscodes
	if max < 1 {
		max = 2
	}
	s.h.mu.Lock()
	var running []*hlsSess
	for _, o := range s.h.sess {
		if o != s {
			o.mu.Lock()
			if o.cmd != nil {
				running = append(running, o)
			}
			o.mu.Unlock()
		}
	}
	s.h.mu.Unlock()
	for len(running) >= max {
		oldest := 0
		for i, o := range running {
			if o.last.Load() < running[oldest].last.Load() {
				oldest = i
			}
		}
		o := running[oldest]
		o.mu.Lock()
		if o.cancel != nil {
			o.cancel()
			o.cmd, o.cancel = nil, nil
		}
		o.mu.Unlock()
		running = append(running[:oldest], running[oldest+1:]...)
		s.h.log.Info("转码路数已满,停止最久没动静的一路", "max", max)
	}
	return s.launchLocked(idx, s.h.hwEnabled())
}

// waitSeg 等第 idx 段出现。ffmpeg 已经退出而段还没有时返回 false。
func (s *hlsSess) waitSeg(ctx context.Context, idx int) bool {
	deadline := time.Now().Add(hlsWaitSeconds * time.Second)
	for time.Now().Before(deadline) {
		if fileExists(s.segFile(idx)) {
			return true
		}
		s.mu.Lock()
		running := s.cmd != nil
		s.mu.Unlock()
		if !running {
			time.Sleep(150 * time.Millisecond) // 给"硬编失败后立即软编重试"留点时间
			s.mu.Lock()
			running = s.cmd != nil
			s.mu.Unlock()
			if !running {
				return fileExists(s.segFile(idx))
			}
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(100 * time.Millisecond):
		}
	}
	return false
}

func probeDurationMs(ffprobe, path string) int64 {
	out, err := proc.Hide(exec.Command(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path)).Output()
	if err != nil {
		return 0
	}
	f, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil || f <= 0 {
		return 0
	}
	return int64(f * 1000)
}

// builtinHLS 用内置 ffmpeg 响应 /hls/ 下的请求(播放列表与段)。
func (s *Server) builtinHLS(w http.ResponseWriter, r *http.Request, id, durMs int64, rest string) {
	if s.HLS == nil {
		writeErr(w, 503, "transcode.disabled", "服务端没有启用转码")
		return
	}
	p := parseHLSParams(id, r)
	full, err := MediaPath(s.DB, id)
	if err != nil {
		writeErr(w, 404, "notfound", "媒体不存在")
		return
	}
	if st, err := os.Stat(full); err != nil || st.Size() == 0 {
		writeErr(w, 422, "file.empty", "文件不存在或大小为 0 字节,无法转码")
		return
	}
	if durMs <= 0 {
		durMs = probeDurationMs(s.Cfg.Tools.FFprobe, full)
	}
	if durMs <= 0 {
		writeErr(w, 422, "transcode.nodur", "无法读取视频时长(文件可能已损坏),不能转码")
		return
	}
	sess := s.HLS.session(p, full, durMs)
	sess.last.Store(time.Now().Unix())

	if rest == "master.m3u8" || rest == "main.m3u8" {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-store")
		var b strings.Builder
		b.WriteString("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + strconv.Itoa(hlsSegSec) + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
		q := r.URL.RawQuery
		for i := 0; i < sess.segs; i++ {
			d := float64(hlsSegSec)
			if i == sess.segs-1 {
				if rem := float64(durMs)/1000 - float64(i*hlsSegSec); rem > 0.05 && rem < d {
					d = rem
				}
			}
			fmt.Fprintf(&b, "#EXTINF:%.3f,\nseg%05d.ts?%s\n", d, i, q)
		}
		b.WriteString("#EXT-X-ENDLIST\n")
		_, _ = w.Write([]byte(b.String()))
		return
	}

	// seg00012.ts
	if !strings.HasPrefix(rest, "seg") || !strings.HasSuffix(rest, ".ts") {
		writeErr(w, 400, "bad.path", "路径无效")
		return
	}
	idx, err := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(rest, "seg"), ".ts"))
	if err != nil || idx < 0 || idx >= sess.segs {
		writeErr(w, 404, "notfound", "没有这一段")
		return
	}
	sess.lastReq.Store(int64(idx))
	ok := false
	for try := 0; try < 2 && !ok; try++ { // 第一次等不到(例如 ffmpeg 刚好被停掉了)再重启一次
		if err := sess.ensure(idx); err != nil {
			s.Log.Warn("启动转码失败", "id", id, "err", err)
			writeErr(w, 502, "transcode.start", "无法启动 ffmpeg:"+err.Error())
			return
		}
		ok = sess.waitSeg(r.Context(), idx)
		if r.Context().Err() != nil {
			return
		}
	}
	if !ok {
		sess.mu.Lock()
		msg := sess.lastErr
		sess.mu.Unlock()
		writeErr(w, 502, "transcode.failed", "转码失败:"+firstLine(msg))
		return
	}
	f, err := os.Open(sess.segFile(idx))
	if err != nil {
		writeErr(w, 404, "notfound", "没有这一段")
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-store")
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func firstLine(s string) string {
	if s == "" {
		return "ffmpeg 没有输出(可能是源文件已损坏或编码不受支持)"
	}
	if i := strings.IndexByte(s, '\n'); i > 0 {
		s = s[:i]
	}
	if len(s) > 200 {
		s = s[:200]
	}
	return s
}
