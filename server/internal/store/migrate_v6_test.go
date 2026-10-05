package store

import (
	"path/filepath"
	"testing"
)

// 0.4.0 之前的数据库没有扫描状态;升级后凡有索引数据的根目录都视为"已扫描过",不再整盘重扫。
func TestMigrateV6MarksExistingRootsScanned(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "old.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for i := 0; i < 5; i++ {
		if _, err := db.Exec(migrations[i]); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.Exec("PRAGMA user_version = 5"); err != nil {
		t.Fatal(err)
	}
	must := func(q string, a ...any) {
		t.Helper()
		if _, err := db.Exec(q, a...); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	must(`INSERT INTO roots(id,path,label,volume_serial,volume_guid,last_scan) VALUES(1,'D:\','D:',1,'g',1700000000)`)
	must(`INSERT INTO roots(id,path,label,volume_serial,volume_guid) VALUES(2,'E:\','E:',2,'g')`) // 没有任何数据
	must(`INSERT INTO dialogs(id,root_id,rel_path,title,title_sort) VALUES(5,1,'旅行','旅行','旅行')`)
	must(`INSERT INTO media(id,dialog_id,root_id,name,name_sort,ext,type,size,mtime,ctime,taken_eff,fingerprint) VALUES(9,5,1,'a.jpg','a.jpg','jpg',0,1,1,1,1,'x')`)
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	var s1, s2 string
	var last1 int64
	db.QueryRow(`SELECT scan_state, last_scan FROM roots WHERE id=1`).Scan(&s1, &last1)
	db.QueryRow(`SELECT scan_state FROM roots WHERE id=2`).Scan(&s2)
	if s1 != "complete" || last1 != 1700000000 {
		t.Fatalf("有数据的根目录应视为已完整扫描(保留原时间): %q %d", s1, last1)
	}
	if s2 != "" {
		t.Fatalf("没有数据的根目录仍应是未扫描: %q", s2)
	}
}
