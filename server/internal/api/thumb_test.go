package api

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

// 小尺寸 WebP 缩略图:比原图小得多,有 immutable 缓存头,第二次直接命中磁盘缓存;视频 / 不支持的格式 404。
func TestThumbWebP(t *testing.T) {
	e := newEnv(t)
	e.login()
	dir := filepath.Join(e.lib, "缩略图")
	os.MkdirAll(dir, 0o755)
	orig := filepath.Join(dir, "big.jpg")
	writePattern(t, orig, 1600, 1200, 0, 92)
	rescanAll(t, e)
	id := idByName(t, e, "big.jpg")
	st, _ := os.Stat(orig)

	url := "/api/v1/media/" + itoa(int(id)) + "/render?w=240&fmt=webp&v=1"
	resp, b := e.do("GET", url, nil, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("thumb = %d %s", resp.StatusCode, firstN(b, 200))
	}
	if ct := resp.Header.Get("Content-Type"); ct != "image/webp" {
		t.Fatalf("content-type %q", ct)
	}
	if len(b) < 12 || !bytes.HasPrefix(b, []byte("RIFF")) || string(b[8:12]) != "WEBP" {
		t.Fatalf("不是 WebP: %q", firstN(b, 16))
	}
	if int64(len(b)) > st.Size()/4 {
		t.Fatalf("缩略图应该比原图小得多: %d vs 原图 %d", len(b), st.Size())
	}
	if cc := resp.Header.Get("Cache-Control"); cc != "public, max-age=31536000, immutable" {
		t.Fatalf("Cache-Control = %q", cc)
	}
	// 请求 300 与 240 落在不同档位(240 / 360):各自缓存;同一档位第二次直接命中缓存
	resp2, b2 := e.do("GET", url, nil, nil)
	if resp2.StatusCode != 200 || !bytes.Equal(b, b2) {
		t.Fatal("第二次应该命中缓存且内容一致")
	}
	if thumbBucket(300) != 360 || thumbBucket(240) != 240 || thumbBucket(5000) != 1440 {
		t.Fatalf("档位不对: %d %d %d", thumbBucket(300), thumbBucket(240), thumbBucket(5000))
	}
	// 视频:不生成缩略图
	var vid int64
	_ = e.srv.DB.QueryRow(`SELECT id FROM media WHERE type=1 LIMIT 1`).Scan(&vid)
	if vid != 0 {
		resp, _ := e.do("GET", "/api/v1/media/"+itoa(int(vid))+"/render?w=240&fmt=webp&v=1", nil, nil)
		if resp.StatusCode != 404 {
			t.Fatalf("视频不该有 WebP 缩略图,实际 %d", resp.StatusCode)
		}
	}
	// 不带 fmt=webp 时行为不变(原来的 JPEG 转换)
	resp3, _ := e.do("GET", "/api/v1/media/"+itoa(int(id))+"/render?w=480&v=1", nil, nil)
	if resp3.StatusCode != 200 || resp3.Header.Get("Content-Type") != "image/jpeg" {
		t.Fatalf("原来的 render 应该不变: %d %s", resp3.StatusCode, resp3.Header.Get("Content-Type"))
	}
}
