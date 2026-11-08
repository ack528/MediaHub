package store

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBeginSessionDecidesWhetherToVerify(t *testing.T) {
	dir := t.TempDir()
	// 全新安装(没有数据库):不检查
	if m, _ := BeginSession(dir); m != CheckNone {
		t.Fatal("fresh install must not verify")
	}
	EndSession(dir)
	touchDB := func() { _ = os.WriteFile(filepath.Join(dir, "hub.sqlite"), []byte("x"), 0o600) }
	touchDB()
	// 上次正常退出:不检查
	if m, r := BeginSession(dir); m != CheckNone {
		t.Fatalf("clean exit must skip the full check, got reason %q", r)
	}
	// 上次没有 EndSession(崩溃 / 强杀):先起服务、后台检查(不挡着开始服务)
	if m, r := BeginSession(dir); m != CheckLater || r == "" {
		t.Fatalf("unclean exit must check in the background, got %v %q", m, r)
	}
	EndSession(dir)
	// 登记了需要检查:检查,直到 CheckDone
	RequestCheck(dir, "测试")
	if m, r := BeginSession(dir); m != CheckNow || r != "测试" {
		t.Fatalf("requested check must verify before serving with its reason, got %v %q", m, r)
	}
	EndSession(dir)
	if m, _ := BeginSession(dir); m != CheckNow {
		t.Fatal("request stays until CheckDone")
	}
	CheckDone(dir)
	EndSession(dir)
	if m, _ := BeginSession(dir); m != CheckNone {
		t.Fatal("check done and clean exit must skip")
	}
	EndSession(dir)
	// 有数据库但从没写过状态(从旧版本升级上来):检查一次
	_ = os.Remove(filepath.Join(dir, sessionFile))
	if m, _ := BeginSession(dir); m != CheckLater {
		t.Fatal("unknown previous state must be checked once in the background")
	}
	EndSession(dir)
	t.Setenv("MEDIAHUB_CHECK_DB", "1")
	if m, _ := BeginSession(dir); m != CheckNow {
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
