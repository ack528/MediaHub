package api

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// 日志里一次失败有几千字,同一句话重复几十遍;缓冲区从中间截断,首行是半截("der: Cannot allocate memory")。
func TestCondenseFFmpegErr(t *testing.T) {
	var b strings.Builder
	b.WriteString("der: Cannot allocate memory\r\n") // 被截断的半截首行
	for i := 0; i < 40; i++ {
		b.WriteString("[h264_qsv @ 0000000002650980] get_buffer() failed\r\n")
		b.WriteString("[vist#0:0/h264 @ 0000000002981640] [dec:h264_qsv @ 0000000002650300] Decoding error: Cannot allocate memory\r\n")
	}
	b.WriteString("[vist#0:0/h264 @ 0000000002981640] Decode error rate 0.999939 exceeds maximum 0.666667\r\n")
	raw := b.String()
	for len(raw) < 4000 { // 补到缓冲区上限,模拟 tailBuf 的截断
		raw += raw
	}
	out := condenseFFmpegErr(raw)
	if len(out) > 1300 {
		t.Fatalf("没压短: %d 字节", len(out))
	}
	if strings.HasPrefix(out, "der:") {
		t.Fatalf("半截首行应该被去掉: %q", out)
	}
	if !strings.Contains(out, "get_buffer() failed") || !strings.Contains(out, "(×") {
		t.Fatalf("应该保留这句话并标出重复次数: %q", out)
	}
	if strings.Contains(out, "0000000002650980") {
		t.Fatalf("内存地址应该被去掉(同一句话才能合并): %q", out)
	}
	if got := condenseFFmpegErr("a\r\nb"); got != "a | b" {
		t.Fatalf("短输出应该原样保留: %q", got)
	}
}

// 核显硬解:连续失败(随后软解成功)就暂停尝试;一次成功清零计数。
func TestHwdecBreaker(t *testing.T) {
	e := newEnv(t)
	s := e.srv
	if !s.hwdecAllowed() {
		t.Fatal("一开始应该允许尝试")
	}
	s.noteHwdec(false)
	if !s.hwdecAllowed() {
		t.Fatal("失败 1 次还不该停用")
	}
	s.noteHwdec(true) // 成功一次:计数清零
	s.noteHwdec(false)
	if !s.hwdecAllowed() {
		t.Fatal("成功后计数应该清零")
	}
	s.noteHwdec(false)
	if s.hwdecAllowed() {
		t.Fatal("连续失败 2 次应该停用")
	}
	s.opt.cp.mu.Lock()
	s.opt.cp.hwdecOffUntil = time.Now().Add(-time.Second)
	s.opt.cp.mu.Unlock()
	if !s.hwdecAllowed() {
		t.Fatal("过了停用期应该重新允许")
	}
}

// ffmpeg 卡住(不退出也没有进度)时,看门狗应该杀掉它,任务失败并写明原因,而不是停几个小时。
func TestCompressStallWatchdog(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("用 .cmd 假装 ffmpeg,只在 Windows 上测")
	}
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	e.srv.Cfg.Video.HWAccel = "none"
	e.srv.Cfg.Optimize.AutoReplace = false
	dir := filepath.Join(e.lib, "卡住")
	os.MkdirAll(dir, 0o755)
	src := filepath.Join(dir, "clip.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=30", "-t", "6",
		"-c:v", "libx264", "-b:v", "2M", src).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	rescanAll(t, e)
	id := idByName(t, e, "clip.mp4")

	oldLimit, oldTick := compressStallLimit, compressStallTick
	compressStallLimit, compressStallTick = 2*time.Second, 200*time.Millisecond
	defer func() { compressStallLimit, compressStallTick = oldLimit, oldTick }()
	fake := filepath.Join(t.TempDir(), "fakeffmpeg.cmd") // 什么输出都没有,也不退出
	if err := os.WriteFile(fake, []byte("@echo off\r\nping -n 9 127.0.0.1 >nul\r\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	e.srv.Cfg.Tools.FFmpeg = fake

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e.srv.StartOptimizeWorker(ctx)
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h264", "mode": "abs", "value": 500, "preset": "veryfast"}, hdr); resp.StatusCode != 200 {
		t.Fatalf("enqueue = %d", resp.StatusCode)
	}
	var state, errMsg string
	start := time.Now()
	deadline := start.Add(40 * time.Second)
	for time.Now().Before(deadline) {
		_ = e.srv.DB.QueryRow(`SELECT state, err FROM opt_job ORDER BY id DESC LIMIT 1`).Scan(&state, &errMsg)
		if state == "failed" {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if state != "failed" || !strings.Contains(errMsg, "没有进度") {
		t.Fatalf("卡住的任务应该被终止并标为失败: state=%s err=%q", state, errMsg)
	}
	if time.Since(start) > 30*time.Second {
		t.Fatalf("看门狗太慢: %v", time.Since(start))
	}
}
