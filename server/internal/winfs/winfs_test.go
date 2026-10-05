//go:build windows

package winfs

import (
	"os"
	"path/filepath"
	"testing"
)

func TestListDirFileIDsStableAcrossRename(t *testing.T) {
	d := t.TempDir()
	f := filepath.Join(d, "测试 a.jpg")
	if err := os.WriteFile(f, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(d, "子目录"), 0o755); err != nil {
		t.Fatal(err)
	}
	es, err := ListDir(d)
	if err != nil {
		t.Fatal(err)
	}
	if len(es) != 2 {
		t.Fatalf("entries=%d %+v", len(es), es)
	}
	var id1 uint64
	for _, e := range es {
		if e.Name == "测试 a.jpg" {
			id1 = e.FileID
			if e.IsDir || e.Size != 5 {
				t.Fatalf("bad entry %+v", e)
			}
		}
	}
	if id1 == 0 {
		t.Fatal("no file id")
	}
	// 重命名后 FileID 不变
	if err := os.Rename(f, filepath.Join(d, "改名.jpg")); err != nil {
		t.Fatal(err)
	}
	es, _ = ListDir(d)
	for _, e := range es {
		if e.Name == "改名.jpg" && e.FileID != id1 {
			t.Fatalf("file id changed on rename: %d -> %d", id1, e.FileID)
		}
	}
	if one, err := FileID(filepath.Join(d, "改名.jpg")); err != nil || one != id1 {
		t.Fatalf("FileID()=%d err=%v want %d", one, err, id1)
	}
	if _, _, err := VolumeInfo(d); err != nil {
		t.Fatal(err)
	}
	if free, err := FreeBytes(d); err != nil || free <= 0 {
		t.Fatalf("free=%d err=%v", free, err)
	}
}
