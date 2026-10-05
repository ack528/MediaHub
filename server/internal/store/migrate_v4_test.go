package store

import (
	"path/filepath"
	"testing"
)

// 从旧版本(每个文件夹一个对话)升级:子文件夹对话的媒体并入母文件夹对话,子文件夹对话被删除,真实目录记入 dir_rel。
func TestMigrateV4RehomesNestedDialogs(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "old.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	// 先建到 v3(旧结构)
	for i := 0; i < 3; i++ {
		if _, err := db.Exec(migrations[i]); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.Exec("PRAGMA user_version = 3"); err != nil {
		t.Fatal(err)
	}
	bs := `\`
	// 旧数据:根(1)→ 母文件夹 旅行(2)→ 子文件夹 旅行\京都(3)→ 孙文件夹 旅行\京都\清水(4)
	mustExec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.Exec(q, args...); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	ins := `INSERT INTO dialogs(id,root_id,parent_id,rel_path,title,title_sort) VALUES(?,?,?,?,?,?)`
	mustExec(ins, 1, 1, nil, "", "D:", "d:")
	mustExec(ins, 2, 1, 1, "旅行", "旅行", "旅行")
	mustExec(ins, 3, 1, 2, "旅行"+bs+"京都", "京都", "京都")
	mustExec(ins, 4, 1, 3, "旅行"+bs+"京都"+bs+"清水", "清水", "清水")
	insM := `INSERT INTO media(id,dialog_id,root_id,name,name_sort,ext,type,size,mtime,ctime,taken_eff,fingerprint) VALUES(?,?,?,?,?,?,0,1,1,1,1,'x')`
	mustExec(insM, 10, 1, 1, "root.jpg", "root.jpg", "jpg")
	mustExec(insM, 11, 2, 1, "a.jpg", "a.jpg", "jpg")
	mustExec(insM, 12, 3, 1, "b.jpg", "b.jpg", "jpg")
	mustExec(insM, 13, 4, 1, "c.jpg", "c.jpg", "jpg")

	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM dialogs`).Scan(&n)
	if n != 2 { // 只剩根 + 母文件夹
		t.Fatalf("dialogs=%d want 2", n)
	}
	check := func(id int64, wantDialog int64, wantDir string) {
		t.Helper()
		var d int64
		var dir string
		if err := db.QueryRow(`SELECT dialog_id, dir_rel FROM media WHERE id=?`, id).Scan(&d, &dir); err != nil {
			t.Fatal(err)
		}
		if d != wantDialog || dir != wantDir {
			t.Fatalf("media %d: dialog=%d dir=%q, want %d %q", id, d, dir, wantDialog, wantDir)
		}
	}
	check(10, 1, "")
	check(11, 2, "旅行")
	check(12, 2, "旅行"+bs+"京都")
	check(13, 2, "旅行"+bs+"京都"+bs+"清水")
	var parent any
	db.QueryRow(`SELECT parent_id FROM dialogs WHERE id=2`).Scan(&parent)
	if parent != nil {
		t.Fatalf("母文件夹的 parent_id 应为 NULL, got %v", parent)
	}
}
