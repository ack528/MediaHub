package store

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestBackupRotateAndRestore(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenMigrated(filepath.Join(dir, "hub.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO kv(k,v) VALUES('hello','v1')`); err != nil {
		t.Fatal(err)
	}
	n1, err := Backup(db, dir, 2)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`UPDATE kv SET v='v2' WHERE k='hello'`); err != nil {
		t.Fatal(err)
	}
	time.Sleep(1100 * time.Millisecond) // 文件名精确到秒
	n2, err := Backup(db, dir, 2)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(1100 * time.Millisecond)
	n3, err := Backup(db, dir, 2) // 只留最近 2 份
	if err != nil {
		t.Fatal(err)
	}
	list := ListBackups(dir)
	if len(list) != 2 || list[0].Name != n3 || list[1].Name != n2 {
		t.Fatalf("应该只剩最近 2 份: %+v (n1=%s)", list, n1)
	}
	if _, err := os.Stat(filepath.Join(BackupDir(dir), n1)); err == nil {
		t.Fatal("最旧的一份应该被删掉")
	}
	// 还原到 n2(此时 hello=v2):先把现在的库改成 v3,标记还原,关库,应用
	if _, err := db.Exec(`UPDATE kv SET v='v3' WHERE k='hello'`); err != nil {
		t.Fatal(err)
	}
	if err := MarkRestore(dir, "../evil.sqlite"); err == nil {
		t.Fatal("路径穿越的备份名应该被拒绝")
	}
	if err := MarkRestore(dir, n2); err != nil {
		t.Fatal(err)
	}
	db.Close()
	got, err := ApplyPendingRestore(dir)
	if err != nil || got != n2 {
		t.Fatalf("应用还原: %q %v", got, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "hub.sqlite.before-restore")); err != nil {
		t.Fatal("还原前的库应该留底")
	}
	db2, err := OpenMigrated(filepath.Join(dir, "hub.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db2.Close()
	var v string
	if err := db2.QueryRow(`SELECT v FROM kv WHERE k='hello'`).Scan(&v); err != nil || v != "v2" {
		t.Fatalf("还原后应该是备份时的内容: %q %v", v, err)
	}
	if again, _ := ApplyPendingRestore(dir); again != "" {
		t.Fatal("还原标记应该只生效一次")
	}
}
