package meta

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func projectRoot(t *testing.T) string {
	if r := os.Getenv("MEDIAHUB_ROOT"); r != "" {
		return r
	}
	t.Skip("MEDIAHUB_ROOT 未设置(先运行 . .\\env.ps1)")
	return ""
}

func TestProbeSamples(t *testing.T) {
	r := projectRoot(t)
	ffprobe := filepath.Join(r, "tools", "ffmpeg", "ffprobe.exe")
	dir := filepath.Join(r, "testdata", "generated", "video")
	cases := map[string]struct {
		container, vcodec string
		w, h              int
		hdr               string
	}{
		"h264_aac.mp4":     {"mp4", "h264", 1280, 720, ""},
		"hevc10_hdr10.mkv": {"mkv", "hevc", 1280, 720, "hdr10"},
		"vp9_opus.webm":    {"webm", "vp9", 1280, 720, ""},
		"h264_mp3.avi":     {"avi", "mpeg4", 1280, 720, ""},
		"h264_4k.mp4":      {"mp4", "h264", 3840, 2160, ""},
	}
	for name, c := range cases {
		f := filepath.Join(dir, name)
		if _, err := os.Stat(f); err != nil {
			t.Skipf("样本缺失: %s(先运行 gen-samples.ps1)", name)
		}
		v, err := Probe(context.Background(), ffprobe, f)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if v.Container != c.container || v.VCodec != c.vcodec || v.W != c.w || v.H != c.h || v.HDR != c.hdr {
			t.Errorf("%s: got container=%s vcodec=%s %dx%d hdr=%q", name, v.Container, v.VCodec, v.W, v.H, v.HDR)
		}
		if v.DurationMs < 2000 || v.DurationMs > 6000 {
			t.Errorf("%s: duration %d", name, v.DurationMs)
		}
	}
}

func TestExifBatchChineseNames(t *testing.T) {
	r := projectRoot(t)
	exiftool := filepath.Join(r, "tools", "exiftool", "exiftool.exe")
	src := filepath.Join(r, "testdata", "generated", "image", "sample.jpg")
	b, err := os.ReadFile(src)
	if err != nil {
		t.Skip("sample.jpg 缺失")
	}
	d := t.TempDir()
	f1 := filepath.Join(d, "京都旅行 001.jpg")
	f2 := filepath.Join(d, "b.jpg")
	os.WriteFile(f1, b, 0o644)
	os.WriteFile(f2, b, 0o644)
	m, err := ExifBatch(context.Background(), exiftool, []string{f1, f2})
	if err != nil {
		t.Fatal(err)
	}
	if len(m) != 2 {
		t.Fatalf("got %d results: %+v", len(m), m)
	}
	if p := m[f1]; p == nil || p.W != 4000 || p.H != 3000 {
		t.Fatalf("中文文件名元数据不对: %+v", p)
	}
}

// HEIF 容器自带旋转(irot)的文件:诺基亚 EXIF 方向为 1 但容器要转 90°;iPhone 两个标签都写了,不能算两次。
func TestHeifRotationSwapsOnce(t *testing.T) {
	r := projectRoot(t)
	exiftool := filepath.Join(r, "tools", "exiftool", "exiftool.exe")
	dir := filepath.Join(r, "testdata", "real", "image")
	nokia := filepath.Join(dir, "nokia_8.3_5G.heif")
	iphone := filepath.Join(dir, "iphone_13_pro_max.HEIC")
	for _, f := range []string{nokia, iphone} {
		if _, err := os.Stat(f); err != nil {
			t.Skipf("样本缺失: %s", f)
		}
	}
	m, err := ExifBatch(context.Background(), exiftool, []string{nokia, iphone})
	if err != nil {
		t.Fatal(err)
	}
	if p := m[nokia]; p == nil || p.W != 3456 || p.H != 4608 { // 竖图
		t.Errorf("nokia heif should be portrait 3456x4608, got %+v", p)
	}
	if p := m[iphone]; p == nil || p.W != 3024 || p.H != 4032 { // 只交换一次
		t.Errorf("iphone heic should be portrait 3024x4032, got %+v", p)
	}
}
