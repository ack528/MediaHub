package api

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestLowresFor(t *testing.T) {
	cases := []struct{ long, target, want int }{{4000, 360, 3}, {1600, 240, 2}, {1000, 360, 1}, {300, 360, 0}, {8000, 240, 3}, {100, 0, 0}}
	for _, c := range cases {
		if got := lowresFor(c.long, c.target); got != c.want {
			t.Errorf("lowresFor(%d,%d) = %d, 想要 %d", c.long, c.target, got, c.want)
		}
	}
}

// JPEG 用"缩小解码"(-lowres)生成缩略图后,尺寸仍然正好是目标档位(长边 240,比例不变)。
func TestThumbLowresKeepsSize(t *testing.T) {
	e := newEnv(t)
	e.login()
	dir := filepath.Join(e.lib, "lowres")
	os.MkdirAll(dir, 0o755)
	writePattern(t, filepath.Join(dir, "wide.jpg"), 2400, 1600, 0, 90)
	rescanAll(t, e)
	id := idByName(t, e, "wide.jpg")
	resp, b := e.do("GET", "/api/v1/media/"+itoa(int(id))+"/render?w=240&fmt=webp&v=1", nil, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("thumb = %d", resp.StatusCode)
	}
	p := filepath.Join(t.TempDir(), "t.webp")
	_ = os.WriteFile(p, b, 0o644)
	out, err := exec.Command(e.srv.Cfg.Tools.FFprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", p).Output()
	if err != nil {
		t.Skipf("ffprobe 读不了 webp: %v", err)
	}
	if got := strings.TrimSpace(string(out)); got != "240,160" {
		t.Fatalf("缩略图尺寸 = %q, 想要 240,160", got)
	}
}

// 生成缩略图时顺手把 ThumbHash 占位数据算好写进库里(同一次解码多一路输出),随列表返回(base64)。
func TestThumbRenderStoresThumbhash(t *testing.T) {
	e := newEnv(t)
	e.login()
	dir := filepath.Join(e.lib, "占位")
	os.MkdirAll(dir, 0o755)
	writePattern(t, filepath.Join(dir, "ph.jpg"), 1200, 800, 0, 90)
	rescanAll(t, e)
	id := idByName(t, e, "ph.jpg")
	var th []byte
	_ = e.srv.DB.QueryRow(`SELECT thumbhash FROM media WHERE id=?`, id).Scan(&th)
	if len(th) != 0 {
		t.Fatalf("生成缩略图之前不该有 ThumbHash(不再后台整库补算)")
	}
	if resp, _ := e.do("GET", "/api/v1/media/"+itoa(int(id))+"/render?w=240&fmt=webp&v=1", nil, nil); resp.StatusCode != 200 {
		t.Fatalf("thumb = %d", resp.StatusCode)
	}
	_ = e.srv.DB.QueryRow(`SELECT thumbhash FROM media WHERE id=?`, id).Scan(&th)
	if len(th) < 10 || len(th) > 40 {
		t.Fatalf("ThumbHash 长度不对: %d", len(th))
	}
	var h histResp
	var dl struct{ Dialogs []Dialog }
	e.getJSON("/api/v1/dialogs", &dl)
	did := ""
	for _, d := range dl.Dialogs {
		if d.Title == "占位" {
			did = d.ID
		}
	}
	e.getJSON("/api/v1/dialogs/"+did+"/history?limit=20&types=photo", &h)
	found := false
	for _, it := range h.Items {
		if it.Name == "ph.jpg" && it.Thumbhash != "" {
			found = true
		}
	}
	if !found {
		t.Fatal("列表里的图片应该带 thumbhash")
	}
}
