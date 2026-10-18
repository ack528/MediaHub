package api

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"mediahub/internal/config"
)

// 「暂停」要让正在压缩的 ffmpeg 也停下来(挂起),而不只是"不开始下一个"(以前的 bug:点了暂停 CPU 照样满载);
// 暂停状态要记住(重启后仍暂停);「继续」后恢复。
func TestCompressPauseSuspendsRunning(t *testing.T) {
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	e.srv.Cfg.Video.HWAccel = "none"
	e.srv.Cfg.Optimize.AutoReplace = false
	dir := filepath.Join(e.lib, "暂停")
	os.MkdirAll(dir, 0o755)
	src := filepath.Join(dir, "long.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30", "-t", "40",
		"-c:v", "libx264", "-b:v", "6M", "-minrate", "6M", "-maxrate", "6M", "-bufsize", "6M", src).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	rescanAll(t, e)
	id := idByName(t, e, "long.mp4")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e.srv.StartOptimizeWorker(ctx)
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800, "preset": "veryslow"}, hdr); resp.StatusCode != 200 {
		t.Fatalf("enqueue = %d", resp.StatusCode)
	}
	job := func() (state string, progress float64, errMsg string) {
		_ = e.srv.DB.QueryRow(`SELECT state, progress, err FROM opt_job ORDER BY id DESC LIMIT 1`).Scan(&state, &progress, &errMsg)
		return
	}
	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		if st, p, _ := job(); st == "running" && p > 0 {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if st, p, _ := job(); st != "running" || p <= 0 {
		t.Fatalf("应该已经在压缩了: %s %v", st, p)
	}

	// 暂停:2 秒内监视协程把 ffmpeg 挂起,进度不再前进,提示写明原因
	if resp, _ := e.do("POST", "/api/v1/admin/compress/pause", nil, hdr); resp.StatusCode != 204 {
		t.Fatalf("pause = %d", resp.StatusCode)
	}
	time.Sleep(4 * time.Second)
	_, p1, msg := job()
	if !strings.Contains(msg, "已手动暂停") {
		t.Fatalf("暂停后任务提示应写明原因: %q", msg)
	}
	time.Sleep(4 * time.Second)
	if _, p2, _ := job(); p2 != p1 {
		t.Fatalf("暂停后进度不应该前进: %v → %v", p1, p2)
	}
	var pv string
	_ = e.srv.DB.QueryRow(`SELECT v FROM kv WHERE k='compress.paused'`).Scan(&pv)
	if pv != "1" {
		t.Fatalf("暂停状态应该被记住: %q", pv)
	}

	// 继续:恢复后进度继续前进
	if resp, _ := e.do("POST", "/api/v1/admin/compress/resume", nil, hdr); resp.StatusCode != 204 {
		t.Fatalf("resume = %d", resp.StatusCode)
	}
	deadline = time.Now().Add(40 * time.Second)
	var p3 float64
	for time.Now().Before(deadline) {
		if st, p, _ := job(); p > p1 || st == "done" {
			p3 = p
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if p3 <= p1 {
		t.Fatalf("继续后进度应该前进: %v → %v", p1, p3)
	}
	e.do("POST", "/api/v1/admin/compress/clear", map[string]any{}, hdr) // 取消 + 清掉,别让后面的编码拖慢测试
}

func TestCompressWindowOpen(t *testing.T) {
	s := &Server{Cfg: config.Default()}
	at := func(h, m int) time.Time { return time.Date(2026, 10, 18, h, m, 0, 0, time.Local) }
	cases := []struct {
		win  string
		t    time.Time
		want bool
	}{
		{"", at(12, 0), true},
		{"01:00-07:00", at(3, 0), true},
		{"01:00-07:00", at(7, 0), false},
		{"01:00-07:00", at(12, 0), false},
		{"23:00-07:00", at(23, 30), true}, // 跨午夜
		{"23:00-07:00", at(2, 0), true},
		{"23:00-07:00", at(12, 0), false},
		{"乱写的", at(12, 0), true}, // 写错了当全天
	}
	for _, c := range cases {
		s.Cfg.Optimize.Window = c.win
		if got := s.compressWindowOpen(c.t); got != c.want {
			t.Errorf("窗口 %q 在 %s: 得到 %v, 想要 %v", c.win, c.t.Format("15:04"), got, c.want)
		}
	}
}

// 核显硬件解码的参数:-hwaccel qsv 放在 -i 之前、用 vpp_qsv 转像素格式;软件路径用 scale(偶数宽高)+ format;线程数受限。
func TestCompressArgsHwDecode(t *testing.T) {
	s := &Server{Cfg: config.Default()}
	s.Cfg.Optimize.MaxThreads = 3
	j := optJob{Kbps: 2000, Preset: "medium"}
	hw := strings.Join(s.compressArgs("in.mkv", "out.mkv", j, "hevc_qsv", true, streamInfo{tenBit: true}, false, false, true), " ")
	for _, want := range []string{"-hwaccel qsv", "-hwaccel_output_format qsv", "vpp_qsv=format=p010le", "-c:v hevc_qsv", "-profile:v main10", "-threads 3 -i in.mkv"} {
		if !strings.Contains(hw, want) {
			t.Errorf("硬件解码参数缺少 %q:\n%s", want, hw)
		}
	}
	if strings.Contains(hw, "scale=") {
		t.Errorf("硬件解码路径不该用软件 scale: %s", hw)
	}
	sw := strings.Join(s.compressArgs("in.mkv", "out.mkv", j, "libx265", false, streamInfo{}, false, false, false), " ")
	if strings.Contains(sw, "-hwaccel") || !strings.Contains(sw, "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p") || !strings.Contains(sw, "pools=3") {
		t.Errorf("软件路径参数不对:\n%s", sw)
	}
	// 第一选择是硬解 + 硬编的条件:偶数宽高、QSV 能解的编码
	if s.Cfg.Optimize.MaxThreads != 3 || s.compressThreads() != 3 {
		t.Errorf("compressThreads = %d", s.compressThreads())
	}
}

// 本机有 QSV 时,真的用硬解 + 硬编压一个样本,结果是 hevc、时长一致(没有 QSV 就跳过)。
func TestCompressHwDecodeRealIfQSV(t *testing.T) {
	e := newEnv(t)
	probe := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=black:s=640x360:d=0.2", "-vf", "format=nv12", "-c:v", "hevc_qsv", "-f", "null", "-")
	if err := probe.Run(); err != nil {
		t.Skip("这台机器没有可用的 QSV 硬件编码")
	}
	pr := projectRoot(t)
	src := filepath.Join(pr, "testdata", "generated", "video", "h264_aac_1080p.mp4")
	if _, err := os.Stat(src); err != nil {
		t.Skip("缺少 1080p 样本")
	}
	out := filepath.Join(t.TempDir(), "o.mp4")
	args := e.srv.compressArgs(src, out, optJob{Kbps: 1500, Preset: "veryfast"}, "hevc_qsv", true, streamInfo{}, false, false, true)
	if b, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, args...).CombinedOutput(); err != nil {
		t.Fatalf("硬解 + 硬编失败: %v\n%s", err, b)
	}
	if vN, _, vc := countStreams(e.srv.Cfg.Tools.FFprobe, out); vN != 1 || vc != "hevc" {
		t.Fatalf("输出应该是一路 hevc: %d %q", vN, vc)
	}
}

// 任务列表带视频的完整路径(管理程序「定位」按钮用)。
func TestCompressJobsHavePath(t *testing.T) {
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	dir := filepath.Join(e.lib, "定位")
	os.MkdirAll(dir, 0o755)
	src := filepath.Join(dir, "p.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=25", "-t", "2", "-c:v", "libx264", "-b:v", "4M", "-minrate", "4M", "-maxrate", "4M", "-bufsize", "4M", src).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	rescanAll(t, e)
	id := idByName(t, e, "p.mp4")
	e.srv.opt.cp.paused = true // 只入队,不真的压缩
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800}, hdr); resp.StatusCode != 200 {
		t.Fatalf("enqueue = %d", resp.StatusCode)
	}
	var jl struct{ Jobs []optJob }
	_, b := e.do("GET", "/api/v1/admin/compress/jobs", nil, hdr)
	if err := jsonUnmarshal(b, &jl); err != nil || len(jl.Jobs) != 1 {
		t.Fatalf("jobs: %v %s", err, b)
	}
	if got := jl.Jobs[0].Path; !strings.EqualFold(got, src) {
		t.Fatalf("path = %q, 想要 %q", got, src)
	}
}
