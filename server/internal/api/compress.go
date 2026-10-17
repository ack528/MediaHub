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
}

type compressState struct {
	mu     sync.Mutex
	paused bool
	cur    int64 // 正在编码的任务 id
	cancel context.CancelFunc
	wake   chan struct{}
	once   sync.Once
}

var compressPresets = map[string]bool{"veryfast": true, "faster": true, "fast": true, "medium": true, "slow": true, "slower": true, "veryslow": true}

// StartOptimizeWorker 启动压缩队列的后台协程(main 里调用一次)。启动时把上次没编完的任务重新排队。
func (s *Server) StartOptimizeWorker(ctx context.Context) {
	c := &s.opt.cp
	c.once.Do(func() {
		c.wake = make(chan struct{}, 1)
		_, _ = s.DB.Exec(`UPDATE opt_job SET state='queued', progress=0 WHERE state='running'`)
		go func() {
			for {
				select {
				case <-ctx.Done():
					return
				case <-c.wake:
				case <-time.After(3 * time.Second):
				}
				s.compressLoop(ctx)
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
		if s.Gov.PauseCompress() {
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

func (s *Server) compressArgs(src, out string, j optJob, enc string, hw bool, si streamInfo, aacAudio bool) []string {
	a := []string{"-hide_banner", "-nostdin", "-y", "-v", "error", "-progress", "pipe:1", "-nostats", "-i", src,
		"-map", "0:v:0", "-map", "0:a?"}
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
	a = append(a, "-vf", "format="+pix)
	rate := strconv.Itoa(j.Kbps) + "k"
	maxr := strconv.Itoa(j.Kbps*3/2) + "k"
	buf := strconv.Itoa(j.Kbps*2) + "k"
	switch enc {
	case "hevc_qsv":
		prof := "main"
		if si.tenBit {
			prof = "main10"
		}
		a = append(a, "-c:v", "hevc_qsv", "-preset", j.Preset, "-profile:v", prof, "-b:v", rate, "-maxrate", maxr, "-bufsize", buf, "-look_ahead", "1")
	case "h264_qsv":
		a = append(a, "-c:v", "h264_qsv", "-preset", j.Preset, "-profile:v", "high", "-b:v", rate, "-maxrate", maxr, "-bufsize", buf, "-look_ahead", "1")
	case "libx265":
		a = append(a, "-c:v", "libx265", "-preset", j.Preset, "-b:v", rate, "-maxrate", maxr, "-bufsize", buf, "-x265-params", "log-level=error")
	default:
		a = append(a, "-c:v", "libx264", "-preset", j.Preset, "-b:v", rate, "-maxrate", maxr, "-bufsize", buf)
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
	enc, hw := s.encoderFor(j.Codec)
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
	s.Log.Info("压缩开始", "id", id, "media", m.Name, "codec", enc, "kbps", j.Kbps, "preset", j.Preset)

	run := func(aac bool) (string, error) {
		cmd := proc.Background(exec.CommandContext(jctx, s.Cfg.Tools.FFmpeg, s.compressArgs(src, winfs.Path(out), j, enc, hw, si, aac)...)) // 较低的 CPU 优先级
		errBuf := &tailBuf{}
		cmd.Stderr = errBuf
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			return "", err
		}
		if err := cmd.Start(); err != nil {
			return "", err
		}
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
					want := s.Gov.PauseCompress()
					if want && !suspended {
						if proc.Suspend(pid) == nil {
							suspended = true
							s.Log.Info("有客户端连接,压缩已暂停", "id", id)
							s.jobSet(id, map[string]any{"err": "⏸ 有客户端连接,已暂停;没有客户端后自动继续"})
						}
					} else if !want && suspended {
						if proc.Resume(pid) == nil {
							suspended = false
							s.Log.Info("没有客户端连接,压缩已继续", "id", id)
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
	msg, err := run(false)
	if err != nil && jctx.Err() == nil && (strings.Contains(msg, "audio") || strings.Contains(msg, "Audio")) { // 音频直接复制失败(mp4 装不下的音轨):改成 AAC 重编一次
		_ = os.Remove(winfs.Path(out))
		msg, err = run(true)
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
		s.jobSet(id, map[string]any{"state": "failed", "err": "ffmpeg 失败:" + firstLine(msg), "out_path": ""})
		s.Log.Warn("压缩失败", "id", id, "err", msg)
		return
	}
	// ---- 校验
	if why := s.verifyOutput(m, durMs, out, j); why != "" {
		_ = os.Remove(winfs.Path(out))
		s.jobSet(id, map[string]any{"state": "failed", "err": why, "out_path": ""})
		s.Log.Warn("压缩结果没通过校验", "id", id, "why", why)
		return
	}
	ost, _ := os.Stat(winfs.Path(out))
	s.jobSet(id, map[string]any{"state": "done", "progress": 1.0, "new_size": ost.Size()})
	s.Log.Info("压缩完成", "id", id, "media", m.Name, "from", m.Size, "to", ost.Size())
}

// verifyOutput 返回空串表示通过,否则是原因。
func (s *Server) verifyOutput(m mediaInfo, srcDurMs int64, out string, j optJob) string {
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
	// 结尾能完整解码:截断 / 写坏的输出这里会有错误输出
	tail := float64(dur)/1000 - 8
	if tail < 0 {
		tail = 0
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	b, err := proc.Hide(exec.CommandContext(ctx, s.Cfg.Tools.FFmpeg, "-v", "error", "-nostdin", "-ss", strconv.FormatFloat(tail, 'f', 2, 64), "-i", p, "-f", "null", "-")).CombinedOutput()
	if err != nil || strings.TrimSpace(string(b)) != "" {
		return "输出的结尾不能完整解码:" + firstLine(string(b))
	}
	return ""
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
		COALESCE(m.name,''), COALESCE(m.dir_rel,''), COALESCE(ro.label,'')
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
		if rows.Scan(&j.ID, &j.MediaID, &j.State, &j.Codec, &j.Kbps, &j.Preset, &j.Orig, &j.New, &j.Progress, &j.Err, &j.Trash, &j.Created, &j.Updated, &j.Name, &j.Dir, &j.Root) == nil {
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
	writeJSON(w, 200, map[string]any{"jobs": jobs, "counts": sum, "savedBytes": saved})
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
		if !pause {
			s.compressKick()
		}
		w.WriteHeader(204)
	}
}

// adminCompressApply POST /api/v1/admin/compress/apply {ids:[任务 id], purge:false} —— 用压缩版替换原文件。
// 原文件被移进回收区(可还原);purge=true 时替换成功后立刻永久删除原文件(省空间,但没有后悔药,界面里要二次确认)。
func (s *Server) adminCompressApply(w http.ResponseWriter, r *http.Request) {
	var in idsReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in); err != nil || len(in.IDs) == 0 {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	batch := "compress-" + time.Now().Format("20060102-150405")
	applied, failed := 0, []string{}
	var saved int64
	roots := map[int64]bool{}
	batchRoot := map[int64]string{}
	for _, id := range in.IDs {
		j, err := s.loadJob(id)
		if err != nil || j.State != "done" {
			failed = append(failed, fmt.Sprintf("任务 %d 不是「已完成」状态", id))
			continue
		}
		m, err := s.mediaInfoByID(j.MediaID)
		if err != nil {
			failed = append(failed, fmt.Sprintf("任务 %d:原视频已不在索引里", id))
			continue
		}
		src := m.full()
		if st, err := os.Stat(src); err != nil || st.Size() != j.Orig {
			failed = append(failed, m.Name+":原文件已经变化,不能替换")
			continue
		}
		if st, err := os.Stat(winfs.Path(j.OutPath)); err != nil || st.Size() != j.New {
			failed = append(failed, m.Name+":压缩版文件不见了")
			continue
		}
		// 最终名称:同名同容器就覆盖原位置;容器变了(.avi → .mkv)用新扩展名;重名加个后缀
		ext := filepath.Ext(j.OutPath)
		stem := strings.TrimSuffix(m.Name, filepath.Ext(m.Name))
		finalName := stem + ext
		finalPath := filepath.Join(m.RootPath, m.DirRel, finalName)
		if err := moveToTrash(m, batch, "压缩替换"); err != nil {
			failed = append(failed, m.Name+":移入回收区失败:"+err.Error())
			continue
		}
		if _, err := os.Stat(winfs.Path(finalPath)); err == nil {
			finalPath = filepath.Join(m.RootPath, m.DirRel, stem+" (压缩)"+ext)
		}
		if err := os.Rename(winfs.Path(j.OutPath), winfs.Path(finalPath)); err != nil {
			// 放不进去:把原文件从回收区放回去,数据不丢
			back := filepath.Join(trashRoot(m.RootPath), batch, m.rel())
			_ = os.Rename(winfs.Path(back), src)
			failed = append(failed, m.Name+":替换失败,已还原原文件:"+err.Error())
			continue
		}
		s.forgetMedia(m.ID)
		s.jobSet(id, map[string]any{"state": "applied", "trash_path": batch})
		applied++
		saved += j.Orig - j.New
		roots[m.RootID] = true
		batchRoot[m.RootID] = m.RootPath
	}
	purged := int64(0)
	if in.Purge {
		for _, rp := range batchRoot {
			dir := filepath.Join(trashRoot(rp), batch)
			if underTrash(rp, dir) == nil {
				sz, _ := dirSize(dir)
				if os.RemoveAll(winfs.Path(dir)) == nil {
					purged += sz
				}
			}
		}
	}
	for rid := range roots {
		if s.Rescan != nil {
			s.Rescan(rid)
		}
	}
	if applied > 0 {
		s.Log.Info("压缩版已替换原文件", "n", applied, "saved", saved, "purged", purged, "batch", batch)
	}
	writeJSON(w, 200, map[string]any{"applied": applied, "saved": saved, "purgedBytes": purged, "failed": failed, "batch": batch})
}

var _ = sql.ErrNoRows
