package api

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// 内置 ffmpeg 转码:完整 VOD 播放列表、顺序取段、拖动到后面的段(重启 ffmpeg)、停止后清理。
func TestBuiltinHLS(t *testing.T) {
	e := newEnv(t)
	e.login()
	e.srv.Cfg.Video.HWAccel = "none" // 测试走软编,结果可预期
	e.srv.HLS = NewHLS(e.srv.Cfg, e.srv.Log)
	// 样本只有几秒,循环拼成 ~30 秒的长视频(-c copy,很快),再重新扫描入库
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
	id := s.Items[0].ID
	q := "?maxHeight=240&maxBitrate=500000&sid=t1"
	resp, body := e.do("GET", "/api/v1/media/"+id+"/hls/master.m3u8"+q, nil, nil)
	if resp.StatusCode != 200 || !strings.Contains(string(body), "#EXT-X-PLAYLIST-TYPE:VOD") || !strings.Contains(string(body), "#EXT-X-ENDLIST") {
		t.Fatalf("playlist: %d %s", resp.StatusCode, body)
	}
	n := strings.Count(string(body), "#EXTINF")
	if n < 2 {
		t.Fatalf("样本太短,只有 %d 段", n)
	}
	if !strings.Contains(string(body), "seg00000.ts?maxHeight=240&maxBitrate=500000&sid=t1") {
		t.Fatalf("segment uri: %s", body)
	}
	for _, i := range []int{0, n - 1, 1} { // 先顺序,再跳到最后一段,再回看(已被清掉的不一定存在,会重新转)
		seg := "/api/v1/media/" + id + "/hls/seg" + pad5(i) + ".ts" + q
		resp, b := e.do("GET", seg, nil, nil)
		if resp.StatusCode != 200 || len(b) < 188 || b[0] != 0x47 {
			t.Fatalf("seg %d: %d len=%d %s", i, resp.StatusCode, len(b), firstN(b, 200))
		}
		if ct := resp.Header.Get("Content-Type"); ct != "video/mp2t" {
			t.Fatalf("content-type %q", ct)
		}
	}
	dirs, _ := os.ReadDir(e.srv.HLS.dir)
	if len(dirs) != 1 {
		t.Fatalf("应有 1 个会话目录,实际 %d", len(dirs))
	}
	// 切换画质:同一个 sid 的旧会话被结束
	e.do("GET", "/api/v1/media/"+id+"/hls/master.m3u8?maxHeight=144&maxBitrate=300000&sid=t1", nil, nil)
	if resp, _ := e.do("DELETE", "/api/v1/media/"+id+"/hls?sid=t1", nil, nil); resp.StatusCode != 204 {
		t.Fatalf("stop: %d", resp.StatusCode)
	}
	e.srv.HLS.mu.Lock()
	left := len(e.srv.HLS.sess)
	e.srv.HLS.mu.Unlock()
	if left != 0 {
		t.Fatalf("停止后会话应清空,实际 %d", left)
	}
	// 不存在的段
	resp, _ = e.do("GET", "/api/v1/media/"+id+"/hls/seg09999.ts"+q, nil, nil)
	if resp.StatusCode != 404 {
		t.Fatalf("越界的段应 404,实际 %d", resp.StatusCode)
	}
}

func pad5(i int) string {
	s := "00000" + itoa(i)
	return s[len(s)-5:]
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b []byte
	for ; i > 0; i /= 10 {
		b = append([]byte{byte('0' + i%10)}, b...)
	}
	return string(b)
}

func firstN(b []byte, n int) string {
	if len(b) > n {
		b = b[:n]
	}
	return string(bytes.ToValidUTF8(b, nil))
}
