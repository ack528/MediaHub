package api

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 准备一个 ~30 秒的长视频并返回它的 id。
func longVideoID(t *testing.T, e *env) string {
	src := filepath.Join(e.lib, "相册", "嵌套", "更深", "clip.mp4")
	long := filepath.Join(e.lib, "相册", "long.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-stream_loop", "9", "-i", src, "-c", "copy", long).CombinedOutput(); err != nil {
		t.Skipf("无法生成长样本: %v %s", err, out)
	}
	roots, _ := e.srv.Idx.SyncRoots()
	if err := e.srv.Idx.ScanRoot(context.Background(), roots[0]); err != nil {
		t.Fatal(err)
	}
	if err := e.srv.Idx.Enrich(context.Background(), roots[0]); err != nil {
		t.Fatal(err)
	}
	var s struct{ Items []Item }
	e.getJSON("/api/v1/search?q=long&types=video", &s)
	if len(s.Items) != 1 {
		t.Fatal("long.mp4 not found")
	}
	return s.Items[0].ID
}

func segCodec(t *testing.T, e *env, body []byte) string {
	f := filepath.Join(t.TempDir(), "s.ts")
	if err := writeFile(f, body); err != nil {
		t.Fatal(err)
	}
	out, err := exec.Command(e.srv.Cfg.Tools.FFprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", f).Output()
	if err != nil {
		t.Fatalf("ffprobe: %v", err)
	}
	if f := strings.Fields(string(out)); len(f) > 0 {
		return f[0]
	}
	return ""
}

// H.265 转码 + 6M 码率上限 + 服务端记住的偏好(请求里不带码率 / 编码时用偏好)+ 预热。
func TestHLSH265PrefsPrepare(t *testing.T) {
	e := newEnv(t)
	e.login()
	e.srv.Cfg.Video.HWAccel = "none"
	e.srv.Cfg.Video.AllowSoftwareHEVC = true // 测试机没有 QSV:允许软编 H.265
	e.srv.HLS = NewHLS(e.srv.Cfg, e.srv.Log)
	t.Cleanup(func() { // 先把所有转码会话结束(Windows 上 ffmpeg 还占着样本文件时,临时目录删不掉)
		e.srv.HLS.mu.Lock()
		all := e.srv.HLS.sess
		e.srv.HLS.sess = map[string]*hlsSess{}
		e.srv.HLS.mu.Unlock()
		for _, o := range all {
			o.destroy()
		}
		time.Sleep(500 * time.Millisecond)
	})
	id := longVideoID(t, e)

	// 1. 显式要 h265,码率要 12M:编码是 hevc,实际码率被限制到 6M
	p := e.srv.HLS.effective(hlsParams{Bitrate: 12_000_000, Codec: "h265"}, 0, "h264")
	if p.Bitrate != 6_000_000 || p.Codec != "h265" {
		t.Fatalf("effective = %+v", p)
	}
	resp, b := e.do("GET", "/api/v1/media/"+id+"/hls/seg00000.ts?maxBitrate=12000000&codec=h265&sid=c1", nil, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("h265 seg: %d %s", resp.StatusCode, firstN(b, 200))
	}
	if c := segCodec(t, e, b); c != "hevc" {
		t.Fatalf("编码应是 hevc,实际 %q", c)
	}
	// 软编不允许时 h265 请求退回 h264
	e.srv.Cfg.Video.AllowSoftwareHEVC = false
	if p := e.srv.HLS.effective(hlsParams{Codec: "h265"}, 0, "h264"); p.Codec != "h264" {
		t.Fatalf("不支持 h265 时应退回 h264: %+v", p)
	}
	e.srv.Cfg.Video.AllowSoftwareHEVC = true

	// 2. 服务端记住偏好:PUT 之后,请求不带码率 / 编码就按偏好(1.5M + h265)
	if resp, _ := e.do("PUT", "/api/v1/state/player", map[string]any{"bitrate": 1500000, "codec": "h265"}, nil); resp.StatusCode != 204 {
		t.Fatalf("put pref = %d", resp.StatusCode)
	}
	pr := e.srv.playerPrefs(0)
	_ = pr
	resp, b = e.do("GET", "/api/v1/media/"+id+"/hls/seg00000.ts?sid=c2", nil, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("pref seg: %d %s", resp.StatusCode, firstN(b, 200))
	}
	if c := segCodec(t, e, b); c != "hevc" {
		t.Fatalf("没带编码时应按偏好用 hevc,实际 %q", c)
	}
	e.srv.HLS.mu.Lock()
	var got bool
	for _, o := range e.srv.HLS.sess {
		if o.p.SID == "c2" && o.p.Bitrate == 1_500_000 && o.p.Codec == "h265" {
			got = true
		}
	}
	e.srv.HLS.mu.Unlock()
	if !got {
		t.Fatal("会话参数没有取自偏好(1.5M / h265)")
	}

	// 转码路数(默认 2)被前面两个会话占着时,预热不会去挤掉它们:先结束,再预热
	if resp, _ := e.do("POST", "/api/v1/media/"+id+"/hls/prepare?sid=full1&maxBitrate=800000", nil, nil); resp.StatusCode != 202 {
		t.Fatalf("路数满时 prepare 也应返回 202(只是放弃),实际 %d", resp.StatusCode)
	}
	e.srv.HLS.mu.Lock()
	for _, o := range e.srv.HLS.sess {
		if o.p.SID == "full1" && o.cmd != nil {
			t.Fatal("路数已满,预热不应该启动新的转码")
		}
	}
	e.srv.HLS.mu.Unlock()
	e.do("DELETE", "/api/v1/media/"+id+"/hls?sid=c1", nil, nil)
	e.do("DELETE", "/api/v1/media/"+id+"/hls?sid=c2", nil, nil)
	e.do("DELETE", "/api/v1/media/"+id+"/hls?sid=full1", nil, nil)

	// 3. 预热:POST prepare 立即返回 202,稍后第一段已经在磁盘上;预热的会话转够几段会自己停
	resp, _ = e.do("POST", "/api/v1/media/"+id+"/hls/prepare?sid=pre1&maxBitrate=800000&codec=h264", nil, nil)
	if resp.StatusCode != 202 {
		t.Fatalf("prepare = %d", resp.StatusCode)
	}
	var seg0 string
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		e.srv.HLS.mu.Lock()
		for _, o := range e.srv.HLS.sess {
			if o.p.SID == "pre1" {
				seg0 = o.segFile(0)
			}
		}
		e.srv.HLS.mu.Unlock()
		if seg0 != "" && fileExists(seg0) {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if seg0 == "" || !fileExists(seg0) {
		t.Fatal("预热后第一段应该已经转好")
	}
	// 真正请求这个 sid 的第一段:直接拿到(不需要再等转码)
	t0 := time.Now()
	resp, b = e.do("GET", "/api/v1/media/"+id+"/hls/seg00000.ts?sid=pre1&maxBitrate=800000&codec=h264", nil, nil)
	if resp.StatusCode != 200 || len(b) < 188 {
		t.Fatalf("预热后取第一段: %d", resp.StatusCode)
	}
	if d := time.Since(t0); d > 3*time.Second {
		t.Fatalf("预热过的第一段应当秒回,实际 %v", d)
	}
}

func writeFile(path string, b []byte) error { return os.WriteFile(path, b, 0o644) }

// 核显硬件解码的参数:偶数宽高、QSV 能解的编码、用硬件编码时才用;失败过(noHwdec)或奇数宽高退回软件解码。
func TestHLSHwDecodeArgs(t *testing.T) {
	h := &HLS{}
	mk := func(w, hh int, vc string) *hlsSess {
		s := &hlsSess{h: h, p: hlsParams{Bitrate: 3_000_000, Codec: "h264"}, path: "in.mkv", segs: 10}
		s.props = hlsProps{w, hh, vc}
		return s
	}
	join := func(a []string) string { return strings.Join(a, " ") }
	hw := join(mk(1920, 1080, "hevc").args(5, true))
	for _, want := range []string{"-hwaccel qsv", "-hwaccel_output_format qsv", "vpp_qsv=format=nv12", "-c:v h264_qsv", "-analyzeduration 5000000", "-ss 20"} {
		if !strings.Contains(hw, want) {
			t.Errorf("硬解参数缺少 %q:\n%s", want, hw)
		}
	}
	if strings.Contains(hw, "scale=") || strings.Contains(hw, "yadif") {
		t.Errorf("硬解路径不该有软件滤镜: %s", hw)
	}
	for name, s := range map[string]*hlsSess{"奇数宽高": mk(1921, 1080, "h264"), "不能硬解的编码": mk(640, 480, "wmv3"), "没有信息": mk(0, 0, "")} {
		if a := join(s.args(0, true)); strings.Contains(a, "-hwaccel") || !strings.Contains(a, "scale=trunc(iw/2)*2") {
			t.Errorf("%s 应该用软件解码: %s", name, a)
		}
	}
	failed := mk(1920, 1080, "h264")
	failed.noHwdec = true
	if a := join(failed.args(0, true)); strings.Contains(a, "-hwaccel") || !strings.Contains(a, "-c:v h264_qsv") {
		t.Errorf("硬解失败过应该退回软解但仍用硬编: %s", a)
	}
	if a := join(mk(1920, 1080, "h264").args(0, false)); strings.Contains(a, "-hwaccel") || !strings.Contains(a, "libx264") {
		t.Errorf("软件编码时不用硬解: %s", a)
	}
}
