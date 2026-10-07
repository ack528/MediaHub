package update

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.3.2", "1.3.1", 1}, {"1.3.2", "1.3.2", 0}, {"1.3.2", "1.10.0", -1}, {"2", "1.99.99", 1},
		{"1.4", "1.4.0", 0}, {"v1.4.1", "1.4", 1}, {"1.3.2", "1.3.2.1", -1},
	}
	for _, c := range cases {
		if got := CompareVersions(c.a, c.b); got != c.want {
			t.Errorf("CompareVersions(%q,%q)=%d want %d", c.a, c.b, got, c.want)
		}
	}
}

func TestUpdateNameRegex(t *testing.T) {
	for name, want := range map[string]string{
		"MediaHub-1.4.0-update.zip": "1.4.0", "MediaHub-2.10-update.zip": "2.10",
		"MediaHub-1.4.0-portable.zip": "", "LocalBrowse-2.5.0.apk": "", "MediaHub-1.4.0-update.zip.sha": "",
	} {
		m := updateName.FindStringSubmatch(name)
		got := ""
		if m != nil {
			got = m[1]
		}
		if got != want {
			t.Errorf("%s -> %q want %q", name, got, want)
		}
	}
}

func TestUnzipRejectsSlip(t *testing.T) {
	dir := t.TempDir()
	zp := filepath.Join(dir, "bad.zip")
	f, _ := os.Create(zp)
	zw := zip.NewWriter(f)
	w, _ := zw.Create("../evil.txt")
	w.Write([]byte("x"))
	zw.Close()
	f.Close()
	if err := unzip(zp, filepath.Join(dir, "out")); err == nil {
		t.Fatal("越界路径应该被拒绝")
	}
	if _, err := os.Stat(filepath.Join(dir, "evil.txt")); err == nil {
		t.Fatal("文件被写到了目录外")
	}
}

func TestUnzipOK(t *testing.T) {
	dir := t.TempDir()
	zp := filepath.Join(dir, "ok.zip")
	f, _ := os.Create(zp)
	zw := zip.NewWriter(f)
	w, _ := zw.Create("runtime/bin/mediahub.exe")
	w.Write([]byte("exe"))
	w, _ = zw.Create("MediaHub.exe")
	w.Write([]byte("mgr"))
	zw.Close()
	f.Close()
	out := filepath.Join(dir, "out")
	if err := unzip(zp, out); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"runtime/bin/mediahub.exe", "MediaHub.exe"} {
		if _, err := os.Stat(filepath.Join(out, p)); err != nil {
			t.Errorf("缺少 %s", p)
		}
	}
}
