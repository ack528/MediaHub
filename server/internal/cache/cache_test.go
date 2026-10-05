package cache

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"

	"mediahub/internal/store"
)

func newMgr(t *testing.T, quotaGB int) (*Manager, string, func(int64, string)) {
	t.Helper()
	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "t.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	m := New(db, slog.New(slog.NewTextHandler(io.Discard, nil)), quotaGB, 0, filepath.Join(t.TempDir(), "fallback"))
	root := t.TempDir()
	return m, root, func(id int64, p string) {
		db.Exec(`INSERT INTO roots(id,path,label) VALUES(?,?,?)`, id, p, "T")
	}
}

func TestInitOnDriveAndIgnoreFile(t *testing.T) {
	m, root, reg := newMgr(t, 0) // 配额 0 → 任何可用空间都满足
	reg(1, root)
	rc, err := m.Init(1, root, "T:")
	if err != nil {
		t.Fatal(err)
	}
	if rc.Mode != ModeOnDrive || rc.Dir != filepath.Join(root, ".mediahub") {
		t.Fatalf("unexpected %+v", rc)
	}
	if _, err := os.Stat(filepath.Join(rc.Dir, ".ignore")); err != nil {
		t.Fatal("missing .ignore")
	}
}

func TestFallbackWhenNotEnoughSpace(t *testing.T) {
	m, root, reg := newMgr(t, 1<<20) // 1 PB 配额,必然放不下
	reg(1, root)
	rc, err := m.Init(1, root, "F:")
	if err != nil {
		t.Fatal(err)
	}
	if rc.Mode != ModeFallback || rc.Reason == "" {
		t.Fatalf("expected fallback, got %+v", rc)
	}
	if _, err := os.Stat(filepath.Join(root, ".mediahub")); err == nil {
		t.Fatal("must not create cache dir on the full drive")
	}
}

func TestPutWritesOnlyInsideCacheDirAndEvicts(t *testing.T) {
	m, root, reg := newMgr(t, 0)
	reg(1, root)
	rc, _ := m.Init(1, root, "T:")
	rc.Quota = 1000 // 手动设置很小的配额
	for id := int64(1); id <= 5; id++ {
		p, err := m.Put(1, "posters", id, "webp", func(tmp string) error { return os.WriteFile(tmp, make([]byte, 300), 0o644) })
		if err != nil {
			t.Fatal(err)
		}
		if rel, _ := filepath.Rel(rc.Dir, p); rel == "" || rel[0] == '.' && rel[1] == '.' {
			t.Fatalf("outside cache: %s", p)
		}
	}
	if used := m.Usage(1); used > 1000 {
		t.Fatalf("quota not enforced: used=%d", used)
	}
	// 非法 kind 被拒绝
	if _, err := m.Put(1, `..\evil`, 9, "webp", func(string) error { return nil }); err == nil {
		t.Fatal("path traversal in kind must be rejected")
	}
	// 根目录下除 .mediahub 外没有任何新文件
	es, _ := os.ReadDir(root)
	for _, e := range es {
		if e.Name() != ".mediahub" {
			t.Fatalf("unexpected file in drive root: %s", e.Name())
		}
	}
}
