package api

import (
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 兼容版本:H.264 + AC3 的 MKV(手机能解视频、不一定能解 AC3 音频)→ 视频原样复制、音频转 AAC、装进 MP4;
// 之后 GET 直接返回文件(支持 Range);视频编码不能封装的(mpeg2)明确告知只能转码。
func TestCompatRemux(t *testing.T) {
	e := newEnv(t)
	e.login()
	dir := filepath.Join(e.lib, "兼容")
	os.MkdirAll(dir, 0o755)
	src := filepath.Join(dir, "ac3.mkv")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=25", "-f", "lavfi", "-i", "sine=f=440:d=6",
		"-t", "6", "-c:v", "libx264", "-g", "25", "-c:a", "ac3", src).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	mpeg2 := filepath.Join(dir, "old.mpg")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=25", "-t", "2", "-c:v", "mpeg2video", mpeg2).CombinedOutput(); err != nil {
		t.Logf("无法生成 mpeg2 样本,跳过那一半: %v %s", err, out)
		mpeg2 = ""
	}
	rescanAll(t, e)
	id := idByName(t, e, "ac3.mkv")
	base := "/api/v1/media/" + itoa(int(id)) + "/compat"

	// 还没做:202 + none
	resp, b := e.do("GET", base, nil, nil)
	if resp.StatusCode != 202 || !strings.Contains(string(b), `"none"`) {
		t.Fatalf("还没制作应该 202/none: %d %s", resp.StatusCode, b)
	}
	if resp, _ := e.do("POST", base+"/prepare", nil, nil); resp.StatusCode != 200 {
		t.Fatalf("prepare = %d", resp.StatusCode)
	}
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		resp, _ = e.do("GET", base, nil, nil)
		if resp.StatusCode == 200 {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if resp.StatusCode != 200 {
		_, b = e.do("GET", base, nil, nil)
		t.Fatalf("兼容版本没做出来: %d %s", resp.StatusCode, b)
	}
	// 拿到文件,检查编码:视频仍是 h264(没重新编码),音频是 aac
	resp, b = e.do("GET", base, nil, nil)
	out := filepath.Join(t.TempDir(), "c.mp4")
	_ = os.WriteFile(out, b, 0o644)
	pr, err := exec.Command(e.srv.Cfg.Tools.FFprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "csv=p=0", out).Output()
	if err != nil {
		t.Fatal(err)
	}
	got := strings.ReplaceAll(strings.TrimSpace(string(pr)), "\r", "")
	if !strings.Contains(got, "h264,video") || !strings.Contains(got, "aac,audio") {
		t.Fatalf("兼容版本编码不对: %q", got)
	}
	// Range 请求
	resp, _ = e.do("GET", base, nil, map[string]string{"Range": "bytes=0-99"})
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("兼容版本应该支持 Range,实际 %d", resp.StatusCode)
	}
	// 再 prepare:直接返回 ready
	_, b = e.do("POST", base+"/prepare", nil, nil)
	if !strings.Contains(string(b), `"ready"`) {
		t.Fatalf("已有兼容版本应该返回 ready: %s", b)
	}
	// 视频编码不能封装 → failed + 原因
	if mpeg2 != "" {
		rescanAll(t, e)
		id2 := idByName(t, e, "old.mpg")
		_, b = e.do("POST", "/api/v1/media/"+itoa(int(id2))+"/compat/prepare", nil, nil)
		if !strings.Contains(string(b), `"failed"`) || !strings.Contains(string(b), "video-codec") {
			t.Fatalf("mpeg2 应该明确不能做兼容版本: %s", b)
		}
	}
}
