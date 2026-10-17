package tunnel

import (
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
	"testing/fstest"
)

func gz(t *testing.T, b []byte) []byte {
	var buf bytes.Buffer
	w := gzip.NewWriter(&buf)
	w.Write(b)
	w.Close()
	return buf.Bytes()
}

// 内置 frpc 的释放:第一次写出;内容没变不再重写;新版本(压缩包不同)会覆盖;没有内置时报错。
func TestExtractEmbedded(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "bin")
	v1 := fstest.MapFS{"embed/frpc.exe.gz": {Data: gz(t, []byte("frpc-v1"))}}
	p, err := extractEmbedded(v1, "embed/frpc.exe.gz", dir)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "frpc-v1" {
		t.Fatalf("内容 = %q", b)
	}
	// 同一版本再来一次:不重写(把文件改一下,应该保持被改后的内容)
	os.WriteFile(p, []byte("local-edit"), 0o700)
	if _, err := extractEmbedded(v1, "embed/frpc.exe.gz", dir); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "local-edit" {
		t.Fatalf("同一版本不应该重写: %q", b)
	}
	// 新版本覆盖
	v2 := fstest.MapFS{"embed/frpc.exe.gz": {Data: gz(t, []byte("frpc-v2"))}}
	if _, err := extractEmbedded(v2, "embed/frpc.exe.gz", dir); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "frpc-v2" {
		t.Fatalf("新版本应该覆盖: %q", b)
	}
	if _, err := extractEmbedded(fstest.MapFS{}, "embed/frpc.exe.gz", dir); err == nil {
		t.Fatal("没有内置时应该报错")
	}
}
