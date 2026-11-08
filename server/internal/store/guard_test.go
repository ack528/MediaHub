package store

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBeginSessionDecidesWhetherToVerify(t *testing.T) {
	dir := t.TempDir()
	// 全新安装(没有数据库):不检查
	if v, _ := BeginSession(dir); v {
		t.Fatal("fresh install must not verify")
	}
	EndSession(dir)
	touchDB := func() { _ = os.WriteFile(filepath.Join(dir, "hub.sqlite"), []byte("x"), 0o600) }
	touchDB()
	// 上次正常退出:不检查
	if v, r := BeginSession(dir); v {
		t.Fatalf("clean exit must skip the full check, got reason %q", r)
	}
	// 上次没有 EndSession(崩溃 / 强杀):检查
	if v, r := BeginSession(dir); !v || r == "" {
		t.Fatalf("unclean exit must verify, got %v %q", v, r)
	}
	EndSession(dir)
	// 登记了需要检查:检查,直到 CheckDone
	RequestCheck(dir, "测试")
	if v, r := BeginSession(dir); !v || r != "测试" {
		t.Fatalf("requested check must verify with its reason, got %v %q", v, r)
	}
	EndSession(dir)
	if v, _ := BeginSession(dir); !v {
		t.Fatal("request stays until CheckDone")
	}
	CheckDone(dir)
	EndSession(dir)
	if v, _ := BeginSession(dir); v {
		t.Fatal("check done and clean exit must skip")
	}
	EndSession(dir)
	// 有数据库但从没写过状态(从旧版本升级上来):检查一次
	_ = os.Remove(filepath.Join(dir, sessionFile))
	if v, _ := BeginSession(dir); !v {
		t.Fatal("unknown previous state must verify once")
	}
	EndSession(dir)
	t.Setenv("MEDIAHUB_CHECK_DB", "1")
	if v, _ := BeginSession(dir); !v {
		t.Fatal("env must force verify")
	}
}

func TestOpenFastMigratesAndBackgroundCheckPasses(t *testing.T) {
	db, err := OpenFast(filepath.Join(t.TempDir(), "hub.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var n int
	if err := db.QueryRow(`SELECT count(*) FROM sqlite_schema WHERE name='media'`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("schema not migrated: %v %d", err, n)
	}
	if err := BackgroundCheck(t.Context(), db); err != nil {
		t.Fatal(err)
	}
}
