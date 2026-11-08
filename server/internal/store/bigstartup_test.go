package store

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// MEDIAHUB_BIG=300000 go test -run TestBigStartup -v ./internal/store —— 造一个几十万条记录的库,比较完整检查和快速启动各要多久。默认跳过。
func TestBigStartup(t *testing.T) {
	var n int
	if _, err := fmt.Sscanf(os.Getenv("MEDIAHUB_BIG"), "%d", &n); err != nil || n <= 0 {
		t.Skip("设置 MEDIAHUB_BIG=行数 才运行")
	}
	dir := t.TempDir()
	if k := os.Getenv("MEDIAHUB_BIG_KEEP"); k != "" { // 把造好的库留下来(给端到端启动测试用)
		dir = k
	}
	path := filepath.Join(dir, "hub.sqlite")
	db, err := OpenFast(path)
	if err != nil {
		t.Fatal(err)
	}
	tx, _ := db.Begin()
	ins, _ := tx.Prepare(`INSERT INTO media(id,dialog_id,root_id,name,name_sort,ext,type,size,mtime,ctime,taken_eff,fingerprint,gen,dir_rel) VALUES(?,?,1,?,?,?,0,?,?,?,?,?,1,'')`)
	fts, _ := tx.Prepare(`INSERT INTO media_fts(rowid,name) VALUES(?,?)`)
	for i := 1; i <= n; i++ {
		name := fmt.Sprintf("IMG_%08d_旅行照片.jpg", i)
		if _, err := ins.Exec(i, i%500, name, name, "jpg", 1000+i, i, i, 1_600_000_000+i, fmt.Sprintf("%d:%d", 1000+i, i)); err != nil {
			t.Fatal(err)
		}
		if _, err := fts.Exec(i, name); err != nil {
			t.Fatal(err)
		}
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	db.Close()
	t0 := time.Now()
	full, err := OpenMigratedWithRepair(path)
	if err != nil {
		t.Fatal(err)
	}
	tFull := time.Since(t0)
	full.Close()
	t0 = time.Now()
	fast, err := OpenFast(path)
	if err != nil {
		t.Fatal(err)
	}
	tFast := time.Since(t0)
	t0 = time.Now()
	if err := BackgroundCheck(t.Context(), fast); err != nil {
		t.Fatal(err)
	}
	tBg := time.Since(t0)
	fast.Close()
	t.Logf("%d 行:完整检查启动 %v;快速启动 %v;后台 quick_check %v(不挡服务)", n, tFull.Round(time.Millisecond), tFast.Round(time.Millisecond), tBg.Round(time.Millisecond))
}
