package api

import (
	"bufio"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"mediahub/internal/proc"
	"mediahub/internal/winfs"
)

// 视频批量压缩(管理程序「存储优化 → 视频压缩」):
//
//	enqueue  选好视频、编码(H.265 / H.264)、码率(固定 kbps 或原码率的百分比)、编码预设 → 排进队列
//	worker   单路后台编码(Intel QSV 硬件编码;没有 QSV 时退回软件编码):原文件不动,输出写到 <根目录>\.mediahub\optimize\
//	verify   编完校验:时长一致、视频流编码正确、结尾能完整解码、确实变小了(不到 minGain% 的丢掉)
//	apply    你确认后才替换:原文件【移进回收区】(可还原),压缩版放到原来的位置(同名,容器不变;原来是 avi / wmv 等会变成 .mkv)
//
// 压缩只改视频码率,分辨率 / 帧率 / 音频(直接复制)/ 字幕 / 章节都保留;10-bit 的片源用 10-bit 编码,不会因此出现色带。
// 替换后这个视频在索引里是"新文件":它在手机上的播放进度 / 浏览位置会重置。

type optJob struct {
	ID       int64   `json:"id"`
	MediaID  int64   `json:"mediaId"`
	State    string  `json:"state"` // queued | running | done | failed | cancelled | applied
	Codec    string  `json:"codec"`
	Kbps     int     `json:"kbps"`
	Preset   string  `json:"preset"`
	OutPath  string  `json:"-"`
	Orig     int64   `json:"origSize"`
	New      int64   `json:"newSize"`
	Progress float64 `json:"progress"`
	Err      string  `json:"err,omitempty"`
	Trash    string  `json:"trash,omitempty"`
	Created  int64   `json:"created"`
	Updated  int64   `json:"updated"`
	// 给界面显示
	Name string `json:"name"`
	Dir  string `json:"dir"`
	Root string `json:"root"`
	Path string `json:"path,omitempty"` // 视频的完整路径(管理程序「定位」按钮用);替换后原位置是压缩版,容器变了名字可能对不上,界面会退回打开所在目录
}

type compressState struct {
	mu     sync.Mutex
	paused bool
	cur    int64 // 正在编码的任务 id
	cancel context.CancelFunc
	wake   chan struct{}
	once   sync.Once

	lastRetry, lastPurge time.Time // compressHousekeeping 的计时(只在 worker 协程里读写)
}

var compressPresets = map[string]bool{"veryfast": true, "faster": true, "fast": true, "medium": true, "slow": true, "slower": true, "veryslow": true}

// compressHousekeeping 队列空闲时的杂务:重试没成功的自动替换(每 2 分钟)、清理过期的「压缩替换」回收批次(每小时)。
func (s *Server) compressHousekeeping() {
	c := &s.opt.cp
	if s.Gov.PauseCompress() { // 有客户端连着时不做这些磁盘操作
		return
	}
	now := time.Now()
	if now.Sub(c.lastRetry) > 2*time.Minute {
		c.lastRetry = now
		s.retryAutoApply()
	}
	if now.Sub(c.lastPurge) > time.Hour {
		c.lastPurge = now
		s.purgeOldCompressTrash()
	}
}

// purgeOldCompressTrash 永久删除超过 optimize.trashKeepDays 天的「压缩替换」回收批次(batch 名是 compress-年月日-时分秒)。
// 只动 compress-* 批次,手动删除重复文件产生的批次不会被自动清理。
func (s *Server) purgeOldCompressTrash() {
	keep := s.Cfg.Optimize.TrashKeepDays
	if keep <= 0 {
		return
	}
	cutoff := time.Now().AddDate(0, 0, -keep)
	rows, err := s.DB.Query(`SELECT path FROM roots`)
	if err != nil {
		return
	}
	var roots []string
	for rows.Next() {
		var p string
		if rows.Scan(&p) == nil {
			roots = append(roots, p)
		}
	}
	rows.Close()
	for _, rp := range roots {
		ents, err := os.ReadDir(winfs.Path(trashRoot(rp)))
		if err != nil {
			continue
		}
		for _, e := range ents {
			name := e.Name()
			if !e.IsDir() || !strings.HasPrefix(name, "compress-") {
				continue
			}
			t, err := time.ParseInLocation("20060102-150405", strings.TrimPrefix(name, "compress-"), time.Local)
			if err != nil || t.After(cutoff) {
				continue
			}
			dir := filepath.Join(trashRoot(rp), name)
			if underTrash(rp, dir) != nil {
				continue
			}
			sz, _ := dirSize(dir)
			if os.RemoveAll(winfs.Path(dir)) == nil {
				s.Log.Info("压缩替换的原文件已过保留期,永久删除", "batch", name, "freed", sz, "keepDays", keep)
			}
		}
	}
}

// StartOptimizeWorker 启动压缩队列的后台协程(main 里调用一次)。启动时把上次没编完的任务重新排队。
func (s *Server) StartOptimizeWorker(ctx context.Context) {
	c := &s.opt.cp
	c.once.Do(func() {
		c.wake = make(chan struct{}, 1)
		_, _ = s.DB.Exec(`UPDATE opt_job SET state='queued', progress=0 WHERE state='running'`)
		var pv string
		if s.DB.QueryRow(`SELECT v FROM kv WHERE k='compress.paused'`).Scan(&pv) == nil && pv == "1" {
			c.paused = true
			s.Log.Info("压缩队列上次是暂停状态,保持暂停(在管理程序里点「继续」恢复)")
		}
		// 3.2.0 之前的校验会把「音频带无害警告」的好结果判成失败(原因以「输出的结尾不能完整解码」开头):升级后自动重新排队
		if res, err := s.DB.Exec(`UPDATE opt_job SET state='queued', progress=0, err='', out_path='' WHERE state='failed' AND err LIKE '输出的结尾不能完整解码%'`); err == nil {
			if n, _ := res.RowsAffected(); n > 0 {
				s.Log.Info("旧版校验误判的压缩失败已重新排队", "n", n)
			}
		}
		go func() {
			for {
				select {
				case <-ctx.Done():
					return
				case <-c.wake:
				case <-time.After(3 * time.Second):
				}
				s.compressLoop(ctx)
				s.compressHousekeeping()
			}
		}()
	})
}

func (s *Server) compressKick() {
	c := &s.opt.cp
	if c.wake != nil {
		select {
		case c.wake <- struct{}{}:
		default:
		}
	}
}

func (s *Server) compressLoop(ctx context.Context) {
	c := &s.opt.cp
	for ctx.Err() == nil {
		c.mu.Lock()
		paused := c.paused
		c.mu.Unlock()
		if paused {
			return
		}
		// 有人在看转码视频时让一让:QSV 是共用的,压缩会让正在播放的转码变慢
		if s.HLS != nil && s.HLS.activeRuns() > 0 {
			time.Sleep(5 * time.Second)
			continue
		}
		// 有客户端连着(governor 不是 Idle)时不开始新的压缩;没有客户端后自动继续
		if s.compressHoldReason() != "" {
			time.Sleep(3 * time.Second)
			return
		}
		var id int64
		if err := s.DB.QueryRow(`SELECT id FROM opt_job WHERE state='queued' ORDER BY id LIMIT 1`).Scan(&id); err != nil {
			return
		}
		s.compressOne(ctx, id)
	}
}

func (h *HLS) activeRuns() int {
	h.mu.Lock()
	all := make([]*hlsSess, 0, len(h.sess))
	for _, o := range h.sess {
		all = append(all, o)
	}
	h.mu.Unlock()
	n := 0
	for _, o := range all {
		o.mu.Lock()
		if o.cmd != nil {
			n++
		}
		o.mu.Unlock()
	}
	return n
}

func (s *Server) jobSet(id int64, kv map[string]any) {
	kv["updated"] = time.Now().Unix()
	var cols []string
	var args []any
	for k, v := range kv {
		cols = append(cols, k+"=?")
		args = append(args, v)
	}
	args = append(args, id)
	_, _ = s.DB.Exec(`UPDATE opt_job SET `+strings.Join(cols, ",")+` WHERE id=?`, args...)
}

func (s *Server) loadJob(id int64) (optJob, error) {
	var j optJob
	err := s.DB.QueryRow(`SELECT id, media_id, state, codec, bitrate_kbps, preset, out_path, orig_size, new_size, progress, err, trash_path, created, updated FROM opt_job WHERE id=?`, id).
		Scan(&j.ID, &j.MediaID, &j.State, &j.Codec, &j.Kbps, &j.Preset, &j.OutPath, &j.Orig, &j.New, &j.Progress, &j.Err, &j.Trash, &j.Created, &j.Updated)
	return j, err
}

// encoderFor 选编码器:QSV 能用就用硬件,否则软件。返回编码器名和是否硬件。
func (s *Server) encoderFor(codec string) (string, bool) {
	if s.HLS != nil {
		h264, h265 := s.HLS.QSV()
		if codec == "h265" && h265 {
			return "hevc_qsv", true
		}
		if codec != "h265" && h264 {
			return "h264_qsv", true
		}
	}
	if codec == "h265" {
		return "libx265", false
	}
	return "libx264", false
}

type streamInfo struct{ tenBit, hasSub bool }

func (s *Server) probeStreams(path string) streamInfo {
	var si streamInfo
	out, err := proc.Hide(exec.Command(s.Cfg.Tools.FFprobe, "-v", "error", "-show_entries", "stream=codec_type,pix_fmt", "-of", "csv=p=0", path)).Output()
	if err != nil {
		return si
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if strings.Contains(line, "subtitle") {
			si.hasSub = true
		}
		if strings.Contains(line, "10le") || strings.Contains(line, "10be") || strings.Contains(line, "p010") {
			si.tenBit = true
		}
	}
	return si
}

func outExtFor(ext string, hasSub bool) string {
	switch strings.ToLower(strings.TrimPrefix(ext, ".")) {
	case "mkv":
		return ".mkv"
	case "mp4", "m4v":
		if hasSub {
			return ".mkv"
		}
		return ".mp4"
	case "mov":
		if hasSub {
			return ".mkv"
		}
		return ".mov"
	}
	return ".mkv"
}

func (s *Server) compressArgs(src, out string, j optJob, enc string, hw bool, si streamInfo, aacAudio, simple, hwdec bool) []string {
	threads := strconv.Itoa(s.compressThreads())
	a := []string{"-hide_banner", "-nostdin", "-y", "-v", "error", "-progress", "pipe:1", "-nostats"}
	if hwdec { // 核显硬件解码:解码是压缩时 CPU 占用的大头(软件解码 1080p / 4K 要占好几个核),交给 QSV 后 CPU 基本空闲
		a = append(a, "-hwaccel", "qsv", "-hwaccel_output_format", "qsv")
	}
	a = append(a, "-threads", threads, "-i", src, // 软件解码 / 软件编码时最多用这么多线程(默认 CPU 核数的一半),别把整台机器吃满
		"-map", "0:V:0", "-map", "0:a?") // 0:V = 不含封面图 / 附件图片的视频流(mp4 / mkv 常带封面,0:v:0 可能选到它)
	isMKV := strings.EqualFold(filepath.Ext(out), ".mkv")
	if isMKV {
		a = append(a, "-map", "0:s?", "-c:s", "copy")
	} else {
		a = append(a, "-sn")
	}
	a = append(a, "-map_metadata", "0", "-map_chapters", "0")
	pix := "yuv420p"
	switch {
	case hw && si.tenBit && strings.HasPrefix(enc, "hevc"):
		pix = "p010le"
	case hw:
		pix = "nv12"
	case si.tenBit && enc == "libx265":
		pix = "yuv420p10le"
	}
	if hwdec { // 帧在显存里:用 vpp_qsv 转像素格式(宽高已确认是偶数才会走这一路)
		a = append(a, "-vf", "vpp_qsv=format="+pix)
	} else {
		// 宽高必须是偶数(奇数宽高的片源硬件编码器会直接报错);已经是偶数时这一步什么都不做
		a = append(a, "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format="+pix)
	}
	rate := strconv.Itoa(j.Kbps) + "k"
	maxr := strconv.Itoa(j.Kbps*3/2) + "k"
	buf := strconv.Itoa(j.Kbps*2) + "k"
	switch enc {
	case "hevc_qsv":
		prof := "main"
		if si.tenBit {
			prof = "main10"
		}
		a = append(a, "-c:v", "hevc_qsv", "-preset", j.Preset, "-profile:v", prof, "-b:v", rate, "-maxrate", maxr, "-bufsize", buf)
		if !simple {
			a = append(a, "-look_ahead", "1")
		}
	case "h264_qsv":
		a = append(a, "-c:v", "h264_qsv", "-preset", j.Preset, "-profile:v", "high", "-b:v", rate, "-maxrate", maxr, "-bufsize", buf)
		if !simple {
			a = append(a, "-look_ahead", "1")
		}
	case "libx265":
		a = append(a, "-c:v", "libx265", "-preset", j.Preset, "-b:v", rate, "-maxrate", maxr, "-bufsize", buf, "-x265-params", "log-level=error:pools="+threads)
	default:
		a = append(a, "-c:v", "libx264", "-preset", j.Preset, "-b:v", rate, "-maxrate", maxr, "-bufsize", buf, "-threads", threads)
	}
	if aacAudio {
		a = append(a, "-c:a", "aac", "-b:a", "192k")
	} else {
		a = append(a, "-c:a", "copy")
	}
	if !isMKV {
		a = append(a, "-movflags", "+faststart")
		if strings.HasPrefix(enc, "hevc") || enc == "libx265" {
			a = append(a, "-tag:v", "hvc1")
		}
	}
	return append(a, out)
}

// encAttempt 一种编码方式。硬件编码失败时依次退到:去掉可选参数的硬件编码 → 软件编码,不要因为某个片源 / 驱动的小毛病整个任务失败。
type encAttempt struct {
	enc    string
	hw     bool
	simple bool
	hwdec  bool // 用核显硬件解码(QSV 编码时才有意义)
}

// compressThreads 压缩时软件解码 / 软件编码最多用的线程数:配置 optimize.maxThreads,0 = CPU 核数的一半(至少 2)。
func (s *Server) compressThreads() int {
	n := s.Cfg.Optimize.MaxThreads
	if n <= 0 {
		n = runtime.NumCPU() / 2
	}
	return min(max(n, 1), 32)
}

// qsvDecodable QSV 能硬解的视频编码(ffprobe 名)。
var qsvDecodable = map[string]bool{"h264": true, "hevc": true, "vp9": true, "av1": true, "mpeg2video": true, "vc1": true, "mjpeg": true}

// compressWindowOpen 现在是不是允许压缩的时段:配置 optimize.window,形如 "01:00-07:00"(跨午夜的 "23:00-07:00" 也行),空 = 全天。
func (s *Server) compressWindowOpen(now time.Time) bool {
	w := strings.TrimSpace(s.Cfg.Optimize.Window)
	if w == "" {
		return true
	}
	var h1, m1, h2, m2 int
	if _, err := fmt.Sscanf(strings.ReplaceAll(w, "：", ":"), "%d:%d-%d:%d", &h1, &m1, &h2, &m2); err != nil {
		return true // 写错了就当全天,别让压缩永远不跑
	}
	cur, from, to := now.Hour()*60+now.Minute(), h1*60+m1, h2*60+m2
	if from == to {
		return true
	}
	if from < to {
		return cur >= from && cur < to
	}
	return cur >= from || cur < to // 跨午夜
}

func (s *Server) compressAttempts(codec string, m mediaInfo) []encAttempt {
	enc, hw := s.encoderFor(codec)
	var out []encAttempt
	// 第一选择:核显硬件解码 + 硬件编码(CPU 几乎不占)。要求宽高是偶数、视频编码 QSV 能解;失败了自动退到下面的方式
	if hw && m.W > 0 && m.H > 0 && m.W%2 == 0 && m.H%2 == 0 && qsvDecodable[strings.ToLower(m.Vcodec)] {
		out = append(out, encAttempt{enc, true, false, true})
	}
	out = append(out, encAttempt{enc, hw, false, false})
	if hw {
		sw := "libx264"
		if codec == "h265" {
			sw = "libx265"
		}
		out = append(out, encAttempt{enc, true, true, false}, encAttempt{sw, false, false, false})
	}
	return out
}

// audioCopyError ffmpeg 的报错是不是"音轨放不进这个容器 / 写不了文件头"(改成 AAC 重编音频可能就能解决)。
func audioCopyError(msg string) bool {
	l := strings.ToLower(msg)
	return strings.Contains(l, "audio") || strings.Contains(l, "container") || strings.Contains(l, "write header") || strings.Contains(l, "tag ")
}

// compressOne 编一个任务。
func (s *Server) compressOne(ctx context.Context, id int64) {
	c := &s.opt.cp
	j, err := s.loadJob(id)
	if err != nil || j.State != "queued" {
		return
	}
	m, err := s.mediaInfoByID(j.MediaID)
	if err != nil {
		s.jobSet(id, map[string]any{"state": "failed", "err": "视频已不在索引里"})
		return
	}
	src := m.full()
	st, err := os.Stat(src)
	if err != nil || st.Size() != m.Size {
		s.jobSet(id, map[string]any{"state": "failed", "err": "原文件不存在或已经变化,请重新排队"})
		return
	}
	durMs := m.DurMs
	if durMs <= 0 {
		durMs = probeDurationMs(s.Cfg.Tools.FFprobe, src)
	}
	if durMs <= 0 {
		s.jobSet(id, map[string]any{"state": "failed", "err": "读不到视频时长(文件可能已损坏)"})
		return
	}
	est := int64(j.Kbps) * 1000 / 8 * durMs / 1000
	if free, err := winfs.FreeBytes(m.RootPath); err == nil && free < est*12/10+(1<<30) {
		s.jobSet(id, map[string]any{"state": "failed", "err": fmt.Sprintf("盘空间不足(剩 %d MB,编码输出约需 %d MB)", free>>20, est>>20)})
		return
	}
	si := s.probeStreams(src)
	outDir := filepath.Join(m.RootPath, ".mediahub", "optimize")
	if err := os.MkdirAll(winfs.Path(outDir), 0o755); err != nil {
		s.jobSet(id, map[string]any{"state": "failed", "err": err.Error()})
		return
	}
	out := filepath.Join(outDir, fmt.Sprintf("%d-%d%s", m.ID, j.ID, outExtFor(m.Ext, si.hasSub)))
	attempts := s.compressAttempts(j.Codec, m)
	jctx, cancel := context.WithCancel(ctx)
	c.mu.Lock()
	c.cur, c.cancel = id, cancel
	c.mu.Unlock()
	defer func() {
		cancel()
		c.mu.Lock()
		c.cur, c.cancel = 0, nil
		c.mu.Unlock()
	}()
	s.jobSet(id, map[string]any{"state": "running", "progress": 0.0, "out_path": out, "orig_size": m.Size, "err": ""})
	s.Log.Info("压缩开始", "id", id, "media", m.Name, "codec", attempts[0].enc, "kbps", j.Kbps, "preset", j.Preset)

	run := func(at encAttempt, aac bool) (string, error) {
		cmd := proc.Background(exec.CommandContext(jctx, s.Cfg.Tools.FFmpeg, s.compressArgs(src, winfs.Path(out), j, at.enc, at.hw, si, aac, at.simple, at.hwdec)...)) // 较低的 CPU 优先级
		errBuf := &tailBuf{}
		cmd.Stderr = errBuf
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			return "", err
		}
		if err := cmd.Start(); err != nil {
			return "", err
		}
		_ = proc.SetLowPriority(cmd.Process.Pid, true) // 空闲优先级:别的程序一用 CPU 它就让路,压缩只吃空闲的算力
		// 监视调度级别:有客户端连着就把 ffmpeg 整个进程挂起(进度保留、不占 CPU / 磁盘 / QSV),没有客户端后恢复
		pid := cmd.Process.Pid
		stopMon := make(chan struct{})
		defer close(stopMon)
		go func() {
			t := time.NewTicker(2 * time.Second)
			defer t.Stop()
			suspended := false
			for {
				select {
				case <-stopMon:
					if suspended {
						_ = proc.Resume(pid)
					}
					return
				case <-t.C:
					reason := s.compressHoldReason() // 手动暂停 / 不在允许的时段 / 有客户端连接
					want := reason != ""
					if want && !suspended {
						if proc.Suspend(pid) == nil {
							suspended = true
							s.Log.Info("压缩已暂停", "id", id, "reason", reason)
							s.jobSet(id, map[string]any{"err": "⏸ " + reason + ",已暂停;条件解除后自动继续"})
						}
					} else if !want && suspended {
						if proc.Resume(pid) == nil {
							suspended = false
							s.Log.Info("压缩已继续", "id", id)
							s.jobSet(id, map[string]any{"err": ""})
						}
					}
				}
			}
		}()
		sc := bufio.NewScanner(stdout)
		last := time.Now()
		for sc.Scan() {
			line := sc.Text()
			if v, ok := strings.CutPrefix(line, "out_time_us="); ok {
				if us, err := strconv.ParseInt(v, 10, 64); err == nil && us > 0 && time.Since(last) > 2*time.Second {
					last = time.Now()
					p := float64(us) / 1000 / float64(durMs)
					if p > 0.999 {
						p = 0.999
					}
					s.jobSet(id, map[string]any{"progress": p})
				}
			}
		}
		err = cmd.Wait()
		return errBuf.String(), err
	}
	var msg string
	used := attempts[0]
	for ai, at := range attempts {
		used = at
		t0 := time.Now()
		msg, err = run(at, false)
		if err != nil && jctx.Err() == nil && audioCopyError(msg) { // 音频直接复制失败(mp4 装不下的音轨):改成 AAC 重编一次
			_ = os.Remove(winfs.Path(out))
			msg, err = run(at, true)
		}
		if err == nil || jctx.Err() != nil {
			break
		}
		_ = os.Remove(winfs.Path(out))
		s.Log.Warn("压缩编码失败", "id", id, "media", m.Name, "encoder", at.enc, "simple", at.simple, "err", msg)
		// 跑了很久才失败(磁盘满、驱动中途崩了…)不再重试,免得白白再等几个小时
		if ai == len(attempts)-1 || time.Since(t0) > 5*time.Minute {
			break
		}
		s.jobSet(id, map[string]any{"err": "这种编码方式失败,换一种重试…", "progress": 0.0})
	}
	if jctx.Err() != nil {
		_ = os.Remove(winfs.Path(out))
		if ctx.Err() == nil { // 被用户取消
			s.jobSet(id, map[string]any{"state": "cancelled", "err": "已取消", "out_path": ""})
		} else { // 服务退出:下次启动重新排队
			s.jobSet(id, map[string]any{"state": "queued", "progress": 0.0})
		}
		return
	}
	if err != nil {
		_ = os.Remove(winfs.Path(out))
		s.jobSet(id, map[string]any{"state": "failed", "err": "ffmpeg 失败:" + ffmpegReason(msg), "out_path": ""})
		s.Log.Warn("压缩失败", "id", id, "media", m.Name, "err", msg)
		return
	}
	// ---- 校验
	if why := s.verifyOutput(m, durMs, out, j, used.enc); why != "" {
		_ = os.Remove(winfs.Path(out))
		s.jobSet(id, map[string]any{"state": "failed", "err": why, "out_path": ""})
		s.Log.Warn("压缩结果没通过校验", "id", id, "why", why)
		return
	}
	ost, _ := os.Stat(winfs.Path(out))
	s.jobSet(id, map[string]any{"state": "done", "progress": 1.0, "new_size": ost.Size()})
	s.Log.Info("压缩完成", "id", id, "media", m.Name, "from", m.Size, "to", ost.Size(), "encoder", used.enc)
	// 校验通过(时长对、流齐全、开头 / 中间 / 结尾都能完整解码)= 能正常播放:按设置直接替换原文件(原文件进回收区,默认保留 7 天)
	if s.Cfg.Optimize.AutoReplace {
		s.autoApply(id)
	}
}

// ffmpegReason 把 ffmpeg 的错误输出压成一行人能看懂的原因:跳过常见的无关行,挑第一条真正的错误。
func ffmpegReason(msg string) string {
	for _, l := range strings.Split(msg, "\n") {
		l = strings.TrimSpace(l)
		if l == "" || strings.HasPrefix(l, "[out#") || strings.HasPrefix(l, "Conversion failed") {
			continue
		}
		if len(l) > 220 {
			l = l[:220]
		}
		return l
	}
	return firstLine(msg)
}

// verifyOutput 返回空串表示通过,否则是原因。通过 = 能正常播放:
//
//	① 有输出且明显变小  ② 时长和原片一致  ③ 视频流编码对、音轨没丢  ④ 开头 / 中间 / 结尾各解码 6 秒没有真正的解码错误
//
// 以前"解码时 ffmpeg 有任何输出就判失败",而音频复制过来的片源经常带 "wrong sample count" 这类无害警告,
// 结果好好的压缩结果被丢掉,任务显示"失败"。现在只看真正的损坏迹象(见 fatalDecodeLine)。
func (s *Server) verifyOutput(m mediaInfo, srcDurMs int64, out string, j optJob, enc string) string {
	p := winfs.Path(out)
	ost, err := os.Stat(p)
	if err != nil || ost.Size() == 0 {
		return "没有生成输出文件"
	}
	if ost.Size() >= m.Size*95/100 {
		return fmt.Sprintf("压缩后没有明显变小(%d MB → %d MB),已丢弃", m.Size>>20, ost.Size()>>20)
	}
	dur := probeDurationMs(s.Cfg.Tools.FFprobe, p)
	tol := srcDurMs / 100
	if tol < 1500 {
		tol = 1500
	}
	if dur <= 0 || dur < srcDurMs-tol || dur > srcDurMs+tol {
		return fmt.Sprintf("输出时长不对(原 %d 秒,输出 %d 秒)", srcDurMs/1000, dur/1000)
	}
	// 流:有视频流且编码对;原片有音轨的话输出不能丢
	wantV := "h264"
	if strings.Contains(enc, "265") || strings.Contains(enc, "hevc") {
		wantV = "hevc"
	}
	vN, aN, vcodec := countStreams(s.Cfg.Tools.FFprobe, p)
	if vN == 0 || vcodec != wantV {
		return fmt.Sprintf("输出里的视频流不对(编码 %q,应为 %s)", vcodec, wantV)
	}
	if _, srcA, _ := countStreams(s.Cfg.Tools.FFprobe, m.full()); srcA > 0 && aN == 0 {
		return "输出丢了音轨"
	}
	// 三处解码:开头、中间、结尾
	secs := float64(dur) / 1000
	for _, at := range []float64{0, secs/2 - 3, secs - 8} {
		if at < 0 {
			at = 0
		}
		if why := decodeWindow(s.Cfg.Tools.FFmpeg, p, at, 6); why != "" {
			return fmt.Sprintf("输出在 %d 秒附近不能完整解码:%s", int(at), why)
		}
	}
	return ""
}

// countStreams 输出文件里的视频流数、音频流数和第一条视频流的编码名。
func countStreams(ffprobe, path string) (v, a int, vcodec string) {
	out, err := proc.Hide(exec.Command(ffprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name:stream_disposition=attached_pic", "-of", "csv=p=0", path)).Output()
	if err != nil {
		return 0, 0, ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Split(strings.TrimSpace(line), ",")
		if len(f) < 2 {
			continue
		}
		// 输出格式:codec_name,codec_type,attached_pic
		switch f[1] {
		case "video":
			if len(f) >= 3 && f[2] == "1" {
				continue // 封面图不算
			}
			v++
			if vcodec == "" {
				vcodec = f[0]
			}
		case "audio":
			a++
		}
	}
	return
}

// decodeWindow 从 at 秒开始只解码视频 lenSec 秒;返回空串 = 没有真正的错误。
func decodeWindow(ffmpeg, p string, at float64, lenSec int) string {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	b, err := proc.Hide(exec.CommandContext(ctx, ffmpeg, "-v", "error", "-nostdin", "-ss", strconv.FormatFloat(at, 'f', 2, 64), "-t", strconv.Itoa(lenSec),
		"-i", p, "-map", "0:V:0", "-an", "-sn", "-f", "null", "-")).CombinedOutput()
	for _, l := range strings.Split(string(b), "\n") {
		if fatalDecodeLine(l) {
			return strings.TrimSpace(l)
		}
	}
	if err != nil {
		return "ffmpeg 报错退出:" + ffmpegReason(string(b))
	}
	return ""
}

// 这些只是时间戳 / 采样数之类的无害警告,不代表文件坏了。
var benignDecode = []string{"wrong sample count", "non-monotonic", "non monotonically", "invalid dts", "invalid pts", "past duration too large",
	"timestamp discontinuity", "last message repeated", "queue input is backward", "increasing reorder buffer", "application provided invalid",
	"deprecated pixel format", "changing video frame properties", "no accelerated colorspace conversion", "chapter ", "stream map ", "skipping", "pts has no value"}

// 这些才是真正的损坏迹象。
var fatalDecode = []string{"error while decoding", "invalid data found", "corrupt", "moov atom not found", "missing reference picture", "invalid nal",
	"non-existing pps", "non-existing sps", "no frame", "could not find", "failed to", "decode_slice_header error", "concealing", "sps unavailable", "pps unavailable",
	"truncated", "unexpected end", "end of file"}

func fatalDecodeLine(line string) bool {
	l := strings.ToLower(strings.TrimSpace(line))
	if l == "" {
		return false
	}
	for _, b := range benignDecode {
		if strings.Contains(l, b) {
			return false
		}
	}
	for _, f := range fatalDecode {
		if strings.Contains(l, f) {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------- 接口

type compressReq struct {
	IDs    []int64 `json:"ids"`
	Filter *struct {
		RootID     int64  `json:"rootId"`
		DialogID   int64  `json:"dialogId"`
		MinSizeMB  int64  `json:"minSizeMB"`
		NotCodec   string `json:"notCodec"`   // 例如 hevc:已经是 H.265 的不压
		MinKbps    int64  `json:"minKbps"`    // 码率低于这个的不压
		NameLike   string `json:"nameLike"`   // 文件名包含
		SkipQueued bool   `json:"skipQueued"` // 已经在队列里 / 已压缩的跳过(默认就跳过)
	} `json:"filter"`
	Codec  string `json:"codec"`  // h265 | h264
	Mode   string `json:"mode"`   // abs(固定 kbps)| percent(原码率的百分比)
	Value  int    `json:"value"`  // abs 时是 kbps;percent 时是 10 ~ 90
	Preset string `json:"preset"` // veryfast … veryslow,默认 medium
	DryRun bool   `json:"dryRun"`
}

type compressPlan struct {
	Count     int      `json:"count"`
	Orig      int64    `json:"origBytes"`
	Est       int64    `json:"estBytes"`
	Skipped   []string `json:"skipped,omitempty"`
	jobs      []optJob
	mediaList []mediaInfo
}

func (s *Server) planCompress(in compressReq) (compressPlan, error) {
	var p compressPlan
	if in.Codec != "h264" {
		in.Codec = "h265"
	}
	if !compressPresets[in.Preset] {
		in.Preset = "medium"
	}
	if in.Mode != "percent" {
		in.Mode = "abs"
	}
	if in.Mode == "abs" && (in.Value < 200 || in.Value > 60000) {
		return p, fmt.Errorf("固定码率要在 200 ~ 60000 kbps 之间")
	}
	if in.Mode == "percent" && (in.Value < 10 || in.Value > 90) {
		return p, fmt.Errorf("百分比要在 10 ~ 90 之间")
	}
	var list []mediaInfo
	if len(in.IDs) > 0 {
		for _, id := range in.IDs {
			if m, err := s.mediaInfoByID(id); err == nil && m.Type == 1 {
				list = append(list, m)
			}
		}
	} else if f := in.Filter; f != nil {
		q := mediaInfoSQL + ` WHERE m.type=1 AND m.state<>2 AND m.size>0 AND m.root_id IN (SELECT id FROM roots WHERE enabled=1)`
		var args []any
		if f.RootID > 0 {
			q += ` AND m.root_id=?`
			args = append(args, f.RootID)
		}
		if f.DialogID > 0 {
			q += ` AND m.dialog_id=?`
			args = append(args, f.DialogID)
		}
		if f.MinSizeMB > 0 {
			q += ` AND m.size>=?`
			args = append(args, f.MinSizeMB<<20)
		}
		if f.NotCodec != "" {
			q += ` AND COALESCE(m.vcodec,'')<>?`
			args = append(args, strings.ToLower(f.NotCodec))
		}
		if f.NameLike != "" {
			q += ` AND m.name LIKE ? ESCAPE '\'`
			args = append(args, "%"+strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(f.NameLike)+"%")
		}
		q += ` ORDER BY m.size DESC LIMIT 5000`
		rows, err := s.DB.Query(q, args...)
		if err != nil {
			return p, err
		}
		for rows.Next() {
			if m, err := scanMediaInfo(rows); err == nil {
				list = append(list, m)
			}
		}
		rows.Close()
	} else {
		return p, fmt.Errorf("没有选择要压缩的视频")
	}
	for _, m := range list {
		var n int
		_ = s.DB.QueryRow(`SELECT COUNT(*) FROM opt_job WHERE media_id=? AND state IN ('queued','running','done')`, m.ID).Scan(&n)
		if n > 0 {
			p.Skipped = append(p.Skipped, m.Name+":已经在队列里或已压缩好")
			continue
		}
		durMs := m.DurMs
		origKbps := m.Bitrate / 1000
		if origKbps <= 0 && durMs > 0 {
			origKbps = m.Size * 8 / durMs // 文件大小 / 时长(字节 × 8 / 毫秒 = kbps)
		}
		if f := in.Filter; f != nil && f.MinKbps > 0 && origKbps < f.MinKbps {
			p.Skipped = append(p.Skipped, m.Name+":码率已经很低")
			continue
		}
		kbps := in.Value
		if in.Mode == "percent" {
			kbps = int(origKbps) * in.Value / 100
			if kbps < 300 {
				kbps = 300
			}
		}
		if origKbps > 0 && int64(kbps) >= origKbps*9/10 {
			p.Skipped = append(p.Skipped, fmt.Sprintf("%s:目标码率 %d kbps 不低于原码率 %d kbps,压不小", m.Name, kbps, origKbps))
			continue
		}
		p.Count++
		p.Orig += m.Size
		if durMs > 0 {
			p.Est += int64(kbps+128) * 1000 / 8 * durMs / 1000 // 视频 + 约 128 kbps 的音频
		} else {
			p.Est += m.Size / 2
		}
		p.jobs = append(p.jobs, optJob{MediaID: m.ID, Codec: in.Codec, Kbps: kbps, Preset: in.Preset})
		p.mediaList = append(p.mediaList, m)
	}
	return p, nil
}

func (s *Server) adminCompressEnqueue(w http.ResponseWriter, r *http.Request) {
	var in compressReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<20)).Decode(&in); err != nil {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	plan, err := s.planCompress(in)
	if err != nil {
		writeErr(w, 400, "bad.request", err.Error())
		return
	}
	if !in.DryRun {
		now := time.Now().Unix()
		for _, j := range plan.jobs {
			_, _ = s.DB.Exec(`INSERT INTO opt_job(media_id, state, codec, bitrate_kbps, preset, created, updated) VALUES(?, 'queued', ?, ?, ?, ?, ?)`,
				j.MediaID, j.Codec, j.Kbps, j.Preset, now, now)
		}
		s.compressKick()
	}
	if len(plan.Skipped) > 30 {
		plan.Skipped = append(plan.Skipped[:30], fmt.Sprintf("……还有 %d 个", len(plan.Skipped)-30))
	}
	writeJSON(w, 200, plan)
}

func (s *Server) adminCompressCaps(w http.ResponseWriter, r *http.Request) {
	h264, h265 := false, false
	if s.HLS != nil {
		h264, h265 = s.HLS.QSV()
	}
	c := &s.opt.cp
	c.mu.Lock()
	paused, cur := c.paused, c.cur
	c.mu.Unlock()
	writeJSON(w, 200, map[string]any{"qsvH264": h264, "qsvH265": h265, "probing": s.HLS != nil && !s.HLS.Probed(),
		"presets": []string{"veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"}, "paused": paused, "current": cur})
}

func (s *Server) adminCompressJobs(w http.ResponseWriter, r *http.Request) {
	state := r.URL.Query().Get("state")
	off, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	lim := limitParam(r, 50, 200)
	q := `SELECT j.id, j.media_id, j.state, j.codec, j.bitrate_kbps, j.preset, j.orig_size, j.new_size, j.progress, j.err, j.trash_path, j.created, j.updated,
		COALESCE(m.name,''), COALESCE(m.dir_rel,''), COALESCE(ro.label,''), COALESCE(ro.path,'')
		FROM opt_job j LEFT JOIN media m ON m.id=j.media_id LEFT JOIN roots ro ON ro.id=m.root_id`
	var args []any
	if state != "" {
		q += ` WHERE j.state=?`
		args = append(args, state)
	}
	q += ` ORDER BY CASE j.state WHEN 'running' THEN 0 WHEN 'queued' THEN 1 WHEN 'done' THEN 2 ELSE 3 END, j.id DESC LIMIT ? OFFSET ?`
	args = append(args, lim, off)
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	defer rows.Close()
	jobs := []optJob{}
	for rows.Next() {
		var j optJob
		var rootPath string
		if rows.Scan(&j.ID, &j.MediaID, &j.State, &j.Codec, &j.Kbps, &j.Preset, &j.Orig, &j.New, &j.Progress, &j.Err, &j.Trash, &j.Created, &j.Updated, &j.Name, &j.Dir, &j.Root, &rootPath) == nil {
			if rootPath != "" && j.Name != "" {
				j.Path = filepath.Join(rootPath, j.Dir, j.Name)
			}
			jobs = append(jobs, j)
		}
	}
	sum := map[string]int64{}
	rs, _ := s.DB.Query(`SELECT state, COUNT(*), COALESCE(SUM(CASE WHEN new_size>0 THEN orig_size-new_size ELSE 0 END),0) FROM opt_job GROUP BY state`)
	var saved int64
	if rs != nil {
		for rs.Next() {
			var st string
			var n, sv int64
			if rs.Scan(&st, &n, &sv) == nil {
				sum[st] = n
				if st == "done" || st == "applied" {
					saved += sv
				}
			}
		}
		rs.Close()
	}
	// 失败原因汇总(把数字抹掉再归类),界面上一眼看出失败主要是什么原因
	reasons := map[string]int{}
	if fr, err := s.DB.Query(`SELECT err FROM opt_job WHERE state='failed'`); err == nil {
		for fr.Next() {
			var e string
			if fr.Scan(&e) == nil {
				reasons[normalizeReason(e)]++
			}
		}
		fr.Close()
	}
	type reasonCount struct {
		Reason string `json:"reason"`
		Count  int    `json:"count"`
	}
	fail := []reasonCount{}
	for k, v := range reasons {
		fail = append(fail, reasonCount{k, v})
	}
	sort.Slice(fail, func(i, j int) bool { return fail[i].Count > fail[j].Count })
	if len(fail) > 10 {
		fail = fail[:10]
	}
	writeJSON(w, 200, map[string]any{"jobs": jobs, "counts": sum, "savedBytes": saved, "failReasons": fail})
}

var digitsRe = regexp.MustCompile(`\d+`)

// normalizeReason 失败原因归类:数字换成 N,截断。
func normalizeReason(e string) string {
	e = strings.TrimSpace(digitsRe.ReplaceAllString(e, "N"))
	if e == "" {
		return "(没有记录原因)"
	}
	if r := []rune(e); len(r) > 70 {
		e = string(r[:70]) + "…"
	}
	return e
}

// adminCompressRetry POST /api/v1/admin/compress/retry {ids:[…]} —— 把失败的任务重新排队(不带 ids = 全部失败的)。
func (s *Server) adminCompressRetry(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IDs []int64 `json:"ids"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in)
	q := `UPDATE opt_job SET state='queued', progress=0, err='', out_path='', updated=? WHERE state='failed'`
	args := []any{time.Now().Unix()}
	if len(in.IDs) == 0 {
		q += ` AND err NOT LIKE '压缩后没有明显变小%'`
	}
	if len(in.IDs) > 0 {
		q += ` AND id IN (` + strings.TrimRight(strings.Repeat("?,", len(in.IDs)), ",") + `)`
		for _, id := range in.IDs {
			args = append(args, id)
		}
	}
	res, err := s.DB.Exec(q, args...)
	if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	n, _ := res.RowsAffected()
	s.compressKick()
	s.Log.Info("失败的压缩任务已重新排队", "n", n)
	writeJSON(w, 200, map[string]any{"requeued": n})
}

type idsReq struct {
	IDs   []int64 `json:"ids"`
	Purge bool    `json:"purge"`
}

func (s *Server) adminCompressCancel(w http.ResponseWriter, r *http.Request) {
	var in idsReq
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in)
	c := &s.opt.cp
	for _, id := range in.IDs {
		c.mu.Lock()
		if c.cur == id && c.cancel != nil {
			c.cancel()
		}
		c.mu.Unlock()
		_, _ = s.DB.Exec(`UPDATE opt_job SET state='cancelled', err='已取消', updated=? WHERE id=? AND state='queued'`, time.Now().Unix(), id)
	}
	w.WriteHeader(204)
}

func (s *Server) adminCompressRemove(w http.ResponseWriter, r *http.Request) {
	var in idsReq
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in)
	for _, id := range in.IDs {
		j, err := s.loadJob(id)
		if err != nil || j.State == "running" {
			continue
		}
		if j.OutPath != "" && (j.State == "done" || j.State == "failed" || j.State == "cancelled") {
			_ = os.Remove(winfs.Path(j.OutPath)) // 没有替换的压缩版输出:删掉(它在 .mediahub\optimize 里)
		}
		_, _ = s.DB.Exec(`DELETE FROM opt_job WHERE id=?`, id)
	}
	w.WriteHeader(204)
}

// adminCompressClear POST /api/v1/admin/compress/clear {includeDone:false} —— 一键清除压缩队列:
// 取消正在压缩的、删除排队中 / 失败 / 已取消的任务及它们残留的输出文件。includeDone=true 时连「已完成、待替换」的任务一起清除
// (它们的压缩版输出文件会被删掉,原视频不受影响);已经替换过的(applied)任务只是历史记录,永远不动。
func (s *Server) adminCompressClear(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IncludeDone bool `json:"includeDone"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in)
	c := &s.opt.cp
	c.mu.Lock()
	cur := c.cur
	if c.cancel != nil {
		c.cancel() // 正在压缩的立刻停掉(compressOne 会把它标成 cancelled 并删掉半成品输出)
	}
	c.mu.Unlock()
	states := []string{"queued", "failed", "cancelled"}
	if in.IncludeDone {
		states = append(states, "done")
	}
	removed := 0
	for _, st := range states {
		rows, err := s.DB.Query(`SELECT id FROM opt_job WHERE state=?`, st)
		if err != nil {
			continue
		}
		var ids []int64
		for rows.Next() {
			var id int64
			if rows.Scan(&id) == nil {
				ids = append(ids, id)
			}
		}
		rows.Close()
		for _, id := range ids {
			j, err := s.loadJob(id)
			if err != nil {
				continue
			}
			if j.OutPath != "" {
				_ = os.Remove(winfs.Path(j.OutPath)) // 压缩版输出在 .mediahub\optimize 里
			}
			if _, err := s.DB.Exec(`DELETE FROM opt_job WHERE id=?`, id); err == nil {
				removed++
			}
		}
	}
	// 正在压缩的那个:等它被取消后(状态变 cancelled)再清一次
	if cur != 0 {
		go func() {
			for i := 0; i < 20; i++ {
				time.Sleep(500 * time.Millisecond)
				j, err := s.loadJob(cur)
				if err != nil {
					return
				}
				if j.State == "cancelled" || j.State == "failed" {
					_ = os.Remove(winfs.Path(j.OutPath))
					_, _ = s.DB.Exec(`DELETE FROM opt_job WHERE id=?`, cur)
					return
				}
			}
		}()
	}
	s.Log.Info("压缩队列已清除", "removed", removed, "includeDone", in.IncludeDone, "cancelledRunning", cur != 0)
	writeJSON(w, 200, map[string]any{"removed": removed, "cancelledRunning": cur != 0})
}

func (s *Server) adminCompressPause(pause bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c := &s.opt.cp
		c.mu.Lock()
		c.paused = pause
		c.mu.Unlock()
		v := "0"
		if pause {
			v = "1"
		}
		// 记住暂停状态:服务重启 / 自动更新后仍然是暂停(以前重启一下队列就自己又开始跑了)
		_, _ = s.DB.Exec(`INSERT INTO kv(k,v) VALUES('compress.paused',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v`, v)
		s.Log.Info("压缩队列", "paused", pause)
		if !pause {
			s.compressKick()
		}
		// 暂停 = 正在压缩的那个 ffmpeg 也会在 2 秒内被挂起(见 compressOne 里的监视协程),不再占 CPU / 磁盘 / 核显;继续 = 恢复
		w.WriteHeader(204)
	}
}

// compressPaused 是否被手动暂停。
func (s *Server) compressPaused() bool {
	c := &s.opt.cp
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.paused
}

// compressHoldReason 现在为什么不该压缩(空 = 可以压缩):手动暂停 / 不在允许的时段 / 有客户端连接。
func (s *Server) compressHoldReason() string {
	switch {
	case s.compressPaused():
		return "已手动暂停"
	case !s.compressWindowOpen(time.Now()):
		return "不在允许压缩的时段"
	case s.Gov.PauseCompress():
		return "有客户端连接"
	}
	return ""
}

// applyResult 一批替换的结果。
type applyResult struct {
	Applied int      `json:"applied"`
	Saved   int64    `json:"saved"`
	Purged  int64    `json:"purgedBytes"`
	Failed  []string `json:"failed"`
	Batch   string   `json:"batch"`
}

// applyJobs 用压缩版替换原文件:原文件【移进回收区】(可还原),压缩版放到原来的位置;purge=true 时替换成功后立刻永久删除原文件。
func (s *Server) applyJobs(ids []int64, purge bool) applyResult {
	batch := "compress-" + time.Now().Format("20060102-150405")
	res := applyResult{Failed: []string{}, Batch: batch}
	roots := map[int64]bool{}
	batchRoot := map[int64]string{}
	for _, id := range ids {
		j, err := s.loadJob(id)
		if err != nil || j.State != "done" {
			res.Failed = append(res.Failed, fmt.Sprintf("任务 %d 不是「已完成」状态", id))
			continue
		}
		m, err := s.mediaInfoByID(j.MediaID)
		if err != nil {
			res.Failed = append(res.Failed, fmt.Sprintf("任务 %d:原视频已不在索引里", id))
			continue
		}
		src := m.full()
		if st, err := os.Stat(src); err != nil || st.Size() != j.Orig {
			res.Failed = append(res.Failed, m.Name+":原文件已经变化,不能替换")
			continue
		}
		if st, err := os.Stat(winfs.Path(j.OutPath)); err != nil || st.Size() != j.New {
			res.Failed = append(res.Failed, m.Name+":压缩版文件不见了")
			continue
		}
		// 最终名称:同名同容器就覆盖原位置;容器变了(.avi → .mkv)用新扩展名;重名加个后缀
		ext := filepath.Ext(j.OutPath)
		stem := strings.TrimSuffix(m.Name, filepath.Ext(m.Name))
		finalName := stem + ext
		finalPath := filepath.Join(m.RootPath, m.DirRel, finalName)
		if err := moveToTrash(m, batch, "压缩替换"); err != nil {
			res.Failed = append(res.Failed, m.Name+":移入回收区失败:"+err.Error())
			continue
		}
		if _, err := os.Stat(winfs.Path(finalPath)); err == nil {
			finalPath = filepath.Join(m.RootPath, m.DirRel, stem+" (压缩)"+ext)
		}
		if err := os.Rename(winfs.Path(j.OutPath), winfs.Path(finalPath)); err != nil {
			// 放不进去:把原文件从回收区放回去,数据不丢
			back := filepath.Join(trashRoot(m.RootPath), batch, m.rel())
			_ = os.Rename(winfs.Path(back), src)
			res.Failed = append(res.Failed, m.Name+":替换失败,已还原原文件:"+err.Error())
			continue
		}
		s.forgetMedia(m.ID)
		s.jobSet(id, map[string]any{"state": "applied", "trash_path": batch, "err": ""})
		res.Applied++
		res.Saved += j.Orig - j.New
		roots[m.RootID] = true
		batchRoot[m.RootID] = m.RootPath
	}
	if purge {
		for _, rp := range batchRoot {
			dir := filepath.Join(trashRoot(rp), batch)
			if underTrash(rp, dir) == nil {
				sz, _ := dirSize(dir)
				if os.RemoveAll(winfs.Path(dir)) == nil {
					res.Purged += sz
				}
			}
		}
	}
	for rid := range roots {
		s.rescanSoon(rid)
	}
	if res.Applied > 0 {
		s.Log.Info("压缩版已替换原文件", "n", res.Applied, "saved", res.Saved, "purged", res.Purged, "batch", batch)
	}
	return res
}

// adminCompressApply POST /api/v1/admin/compress/apply {ids:[任务 id], purge:false} —— 用压缩版替换原文件。
// 原文件被移进回收区(可还原);purge=true 时替换成功后立刻永久删除原文件(省空间,但没有后悔药,界面里要二次确认)。
func (s *Server) adminCompressApply(w http.ResponseWriter, r *http.Request) {
	var in idsReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in); err != nil || len(in.IDs) == 0 {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	writeJSON(w, 200, s.applyJobs(in.IDs, in.Purge))
}

// autoApply 压缩校验通过后自动替换(设置 optimize.autoReplace)。原视频正被手机播放 / 被别的程序占用时移不动,
// 任务留在「已完成」并写上原因,压缩队列空闲时会定期再试(retryAutoApply)。
func (s *Server) autoApply(id int64) {
	res := s.applyJobs([]int64{id}, s.Cfg.Optimize.PurgeOriginal)
	if res.Applied == 0 && len(res.Failed) > 0 {
		s.jobSet(id, map[string]any{"err": "自动替换没成功,稍后重试:" + res.Failed[0]})
		s.Log.Warn("自动替换没成功", "id", id, "why", res.Failed[0])
	}
}

// retryAutoApply 把「已完成、待替换」的任务再试一次自动替换(例如上次原视频正在被播放)。
func (s *Server) retryAutoApply() {
	if !s.Cfg.Optimize.AutoReplace {
		return
	}
	rows, err := s.DB.Query(`SELECT id FROM opt_job WHERE state='done' ORDER BY id LIMIT 20`)
	if err != nil {
		return
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		s.autoApply(id)
	}
}

// rescanSoon 替换文件后合并重扫:90 秒内同一个根目录只重扫一次(连续替换很多个文件时不会反复全盘重扫)。
func (s *Server) rescanSoon(rootID int64) {
	if s.Rescan == nil {
		return
	}
	s.opt.cp.mu.Lock()
	if s.opt.rescanAt == nil {
		s.opt.rescanAt = map[int64]time.Time{}
	}
	if t, ok := s.opt.rescanAt[rootID]; ok && time.Since(t) < 90*time.Second {
		s.opt.cp.mu.Unlock()
		return
	}
	s.opt.rescanAt[rootID] = time.Now()
	s.opt.cp.mu.Unlock()
	time.AfterFunc(90*time.Second, func() { s.Rescan(rootID) })
}

var _ = sql.ErrNoRows
