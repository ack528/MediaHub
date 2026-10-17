package index

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"
	"time"
)

// dump 把索引里的对话和媒体压成可比较的字符串列表。
func dump(t *testing.T, ix *Indexer) []string {
	t.Helper()
	var out []string
	rows, err := ix.DB.Query(`SELECT 'D|' || id || '|' || rel_path || '|' || title FROM dialogs UNION ALL SELECT 'M|' || dialog_id || '|' || dir_rel || '|' || name FROM media`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		out = append(out, s)
	}
	sort.Strings(out)
	return out
}

// 增量扫描(只重扫变化的目录)之后的索引,必须和整盘重新扫描的结果完全一致:
// 新增文件、删除文件、新建文件夹、删除整个文件夹、文件夹改名、被排除目录里的变化。
func TestRescanDirsEqualsFullScan(t *testing.T) {
	ix, r, lib := setup(t)
	ctx := context.Background()
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	pr := projectRoot(t)
	sample := filepath.Join(pr, "testdata", "generated", "image", "sample.jpg")

	// 一批变化
	copyFile(t, sample, filepath.Join(lib, "旅行", "2023-京都", "NEW_0003.jpg")) // 新增文件
	_ = os.Remove(filepath.Join(lib, "旅行", "2023-京都", "IMG_0002.jpg"))        // 删除文件
	copyFile(t, sample, filepath.Join(lib, "新相册", "a.jpg"))                    // 新母文件夹(新对话)
	copyFile(t, sample, filepath.Join(lib, "新相册", "子", "孙", "b.jpg"))          // 新文件夹里更深的子文件夹
	_ = os.RemoveAll(filepath.Join(lib, "视频"))                                  // 整个母文件夹消失
	if err := os.Rename(filepath.Join(lib, "旅行", "2023-京都"), filepath.Join(lib, "旅行", "2024-东京")); err != nil { // 文件夹改名
		t.Fatal(err)
	}
	copyFile(t, sample, filepath.Join(lib, ".mediahub", "posters", "z.jpg")) // 被排除的目录:不该进索引

	dirs := []string{
		filepath.Join(lib, "旅行", "2023-京都"), // 已经不存在(改名了)
		filepath.Join(lib, "旅行", "2024-东京"),
		filepath.Join(lib, "旅行"),
		filepath.Join(lib, "新相册"),
		filepath.Join(lib, "视频"),
		filepath.Join(lib, ".mediahub", "posters"),
		lib,
	}
	changed, err := ix.RescanDirs(ctx, r, dirs)
	if err != nil || !changed {
		t.Fatalf("RescanDirs: changed=%v err=%v", changed, err)
	}
	incr := dump(t, ix)
	// FTS 和 media 同步
	var nm, nf int
	_ = ix.DB.QueryRow(`SELECT count(*) FROM media`).Scan(&nm)
	_ = ix.DB.QueryRow(`SELECT count(*) FROM media_fts`).Scan(&nf)
	if nm != nf {
		t.Fatalf("全文索引条数 %d 应等于媒体数 %d", nf, nm)
	}

	// 整盘重扫后应当完全一致
	ix.ResetScan(r.ID)
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	full := dump(t, ix)
	if !reflect.DeepEqual(incr, full) {
		t.Fatalf("增量结果和整盘扫描不一致\n增量:\n%v\n整盘:\n%v", incr, full)
	}
	for _, s := range incr {
		if s == "M|0||z.jpg" || len(s) > 3 && s[len(s)-5:] == "z.jpg" {
			t.Fatalf(".mediahub 里的文件不该进索引: %s", s)
		}
	}
}

// 实时监视:往根目录里拷一个文件,几秒内自动出现在索引里;删掉它,又自动消失。
func TestWatchPicksUpChanges(t *testing.T) {
	ix, r, lib := setup(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	pr := projectRoot(t)
	done := make(chan error, 1)
	go func() {
		done <- ix.Watch(ctx, r, func() func() { return ix.LockRoot(r.ID) }, WatchOptions{Quiet: 300 * time.Millisecond, MaxWait: 2 * time.Second, MinGap: 200 * time.Millisecond})
	}()
	time.Sleep(500 * time.Millisecond) // 等监视就绪
	target := filepath.Join(lib, "旅行", "2023-京都", "LIVE_0001.jpg")
	copyFile(t, filepath.Join(pr, "testdata", "generated", "image", "sample.jpg"), target)
	has := func() bool { return count(t, ix, `SELECT count(*) FROM media WHERE name='LIVE_0001.jpg'`) == 1 }
	deadline := time.Now().Add(15 * time.Second)
	for !has() && time.Now().Before(deadline) {
		time.Sleep(200 * time.Millisecond)
	}
	if !has() {
		select {
		case err := <-done:
			t.Skipf("这个环境不能监视目录: %v", err)
		default:
		}
		t.Fatal("新拷进来的文件没有自动进索引")
	}
	_ = os.Remove(target)
	deadline = time.Now().Add(15 * time.Second)
	for has() && time.Now().Before(deadline) {
		time.Sleep(200 * time.Millisecond)
	}
	if has() {
		t.Fatal("删除的文件没有自动从索引里消失")
	}
}
