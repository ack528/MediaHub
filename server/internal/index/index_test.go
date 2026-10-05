package index

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"mediahub/internal/config"
	"mediahub/internal/store"
)

func projectRoot(t *testing.T) string {
	if r := os.Getenv("MEDIAHUB_ROOT"); r != "" {
		return r
	}
	t.Skip("MEDIAHUB_ROOT 未设置(先运行 . .\\env.ps1)")
	return ""
}

func copyFile(t *testing.T, src, dst string) {
	t.Helper()
	b, err := os.ReadFile(src)
	if err != nil {
		t.Skipf("样本缺失 %s(先运行 gen-samples.ps1)", src)
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dst, b, 0o644); err != nil {
		t.Fatal(err)
	}
}

func setup(t *testing.T) (*Indexer, Root, string) {
	pr := projectRoot(t)
	lib := t.TempDir()
	gen := filepath.Join(pr, "testdata", "generated")
	copyFile(t, filepath.Join(gen, "image", "sample.jpg"), filepath.Join(lib, "旅行", "2023-京都", "IMG_0001.jpg"))
	copyFile(t, filepath.Join(gen, "image", "sample.jpg"), filepath.Join(lib, "旅行", "2023-京都", "IMG_0010.jpg"))
	copyFile(t, filepath.Join(gen, "image", "sample.jpg"), filepath.Join(lib, "旅行", "2023-京都", "IMG_0002.jpg"))
	copyFile(t, filepath.Join(gen, "video", "h264_aac.mp4"), filepath.Join(lib, "旅行", "2023-京都", "clip 1.mp4"))
	copyFile(t, filepath.Join(gen, "video", "hevc10_hdr10.mkv"), filepath.Join(lib, "视频", "hdr.mkv"))
	copyFile(t, filepath.Join(gen, "image", "sample.jpg"), filepath.Join(lib, "$RECYCLE.BIN", "x.jpg")) // 应被排除
	copyFile(t, filepath.Join(gen, "image", "sample.jpg"), filepath.Join(lib, ".mediahub", "posters", "y.jpg"))
	os.WriteFile(filepath.Join(lib, "notes.txt"), []byte("hi"), 0o644) // 非媒体,默认不索引
	copyFile(t, filepath.Join(gen, "image", "sample.jpg"), filepath.Join(lib, "root.jpg"))

	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "i.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	cfg := config.Default()
	cfg.Roots = []config.Root{{Path: lib, Label: "TEST"}}
	c2, _ := config.Load("", pr)
	cfg.Tools = c2.Tools
	ix := New(db, cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	roots, err := ix.SyncRoots()
	if err != nil || len(roots) != 1 {
		t.Fatalf("SyncRoots: %v %v", roots, err)
	}
	return ix, roots[0], lib
}

func count(t *testing.T, ix *Indexer, q string, args ...any) int {
	t.Helper()
	var n int
	if err := ix.DB.QueryRow(q, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestScanEnrichRenameDelete(t *testing.T) {
	ix, r, lib := setup(t)
	ctx := context.Background()
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	// 媒体:3 张京都照片 + 1 视频 + hdr.mkv + root.jpg = 6;排除回收站、.mediahub、txt
	if n := count(t, ix, `SELECT count(*) FROM media`); n != 6 {
		t.Fatalf("media=%d want 6", n)
	}
	// 对话 = 根目录(root.jpg)+ 母文件夹"旅行"+ 母文件夹"视频" = 3;
	// "旅行"下的子文件夹"2023-京都"不单独成组;$RECYCLE.BIN 与 .mediahub 被排除
	if n := count(t, ix, `SELECT count(*) FROM dialogs`); n != 3 {
		t.Fatalf("dialogs=%d want 3", n)
	}
	if n := count(t, ix, `SELECT count(*) FROM dialogs WHERE title='2023-京都'`); n != 0 {
		t.Fatal("子文件夹不应成为对话")
	}
	// 阶段 2
	if err := ix.Enrich(ctx, r); err != nil {
		t.Fatal(err)
	}
	if n := count(t, ix, `SELECT count(*) FROM media WHERE state=0`); n != 0 {
		t.Fatalf("unenriched=%d", n)
	}
	var w, h int
	if err := ix.DB.QueryRow(`SELECT w,h FROM media WHERE name='IMG_0001.jpg'`).Scan(&w, &h); err != nil || w != 4000 || h != 3000 {
		t.Fatalf("photo size %dx%d err=%v", w, h, err)
	}
	var vc, hdr string
	if err := ix.DB.QueryRow(`SELECT vcodec, COALESCE(hdr,'') FROM media WHERE name='hdr.mkv'`).Scan(&vc, &hdr); err != nil || vc != "hevc" || hdr != "hdr10" {
		t.Fatalf("video meta %s %s err=%v", vc, hdr, err)
	}
	// 对话统计:"旅行"把子文件夹里的 3 张照片 + 1 个视频平铺进来
	var photos, videos, rec, topics int
	if err := ix.DB.QueryRow(`SELECT cnt_photo,cnt_video,cnt_recursive,topics FROM dialogs WHERE title='旅行'`).Scan(&photos, &videos, &rec, &topics); err != nil {
		t.Fatal(err)
	}
	if photos != 3 || videos != 1 || rec != 4 || topics != 0 {
		t.Fatalf("旅行: photo=%d video=%d rec=%d topics=%d", photos, videos, rec, topics)
	}
	// 文件的真实所在目录
	var dirRel string
	if err := ix.DB.QueryRow(`SELECT dir_rel FROM media WHERE name='IMG_0001.jpg'`).Scan(&dirRel); err != nil || dirRel != `旅行\2023-京都` {
		t.Fatalf("dir_rel=%q err=%v", dirRel, err)
	}
	// 自然排序键:IMG_0002 在 IMG_0010 之前
	var first string
	ix.DB.QueryRow(`SELECT name FROM media WHERE name LIKE 'IMG_%' ORDER BY name_sort LIMIT 1`).Scan(&first)
	if first != "IMG_0001.jpg" {
		t.Fatalf("first by name_sort = %s", first)
	}
	// FTS:中文/子串
	if n := count(t, ix, `SELECT count(*) FROM media_fts WHERE media_fts MATCH '"IMG_00"'`); n != 3 {
		t.Fatalf("fts IMG_00 = %d", n)
	}

	// 记录重命名前的 ID
	var idBefore int64
	ix.DB.QueryRow(`SELECT id FROM media WHERE name='IMG_0001.jpg'`).Scan(&idBefore)
	var dialogBefore, videoDialogBefore int64
	ix.DB.QueryRow(`SELECT id FROM dialogs WHERE title='旅行'`).Scan(&dialogBefore)
	ix.DB.QueryRow(`SELECT id FROM dialogs WHERE title='视频'`).Scan(&videoDialogBefore)

	// 重命名文件与文件夹,删除一个文件
	if err := os.Rename(filepath.Join(lib, "旅行", "2023-京都", "IMG_0001.jpg"), filepath.Join(lib, "旅行", "2023-京都", "IMG_0001_改名.jpg")); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(lib, "旅行", "2023-京都"), filepath.Join(lib, "旅行", "京都旅行")); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(lib, "视频", "hdr.mkv")); err != nil {
		t.Fatal(err)
	}
	// 母文件夹改名:对话 id 不变,标题跟着变
	copyFile(t, filepath.Join(projectRoot(t), "testdata", "generated", "image", "sample.jpg"), filepath.Join(lib, "视频", "子目录", "深层.jpg"))
	if err := os.Rename(filepath.Join(lib, "视频"), filepath.Join(lib, "影片")); err != nil {
		t.Fatal(err)
	}
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	var idAfter, dialogAfter int64
	var nameAfter, dirAfter string
	ix.DB.QueryRow(`SELECT id,name,dir_rel FROM media WHERE name LIKE 'IMG_0001%'`).Scan(&idAfter, &nameAfter, &dirAfter)
	ix.DB.QueryRow(`SELECT id FROM dialogs WHERE title='旅行'`).Scan(&dialogAfter)
	var videoDialogAfter int64
	if err := ix.DB.QueryRow(`SELECT id FROM dialogs WHERE title='影片'`).Scan(&videoDialogAfter); err != nil || videoDialogAfter != videoDialogBefore {
		t.Fatalf("母文件夹改名后对话 id 应不变: %d -> %d err=%v", videoDialogBefore, videoDialogAfter, err)
	}
	if n := count(t, ix, `SELECT count(*) FROM media WHERE dialog_id=? AND name='深层.jpg'`, videoDialogAfter); n != 1 {
		t.Fatalf("深层文件应归入母文件夹对话, n=%d", n)
	}
	if dirAfter != `旅行\京都旅行` {
		t.Fatalf("子文件夹改名后 dir_rel=%q", dirAfter)
	}
	if idAfter != idBefore || nameAfter != "IMG_0001_改名.jpg" {
		t.Fatalf("media id changed on rename: %d -> %d (%s)", idBefore, idAfter, nameAfter)
	}
	if dialogAfter != dialogBefore {
		t.Fatalf("dialog id changed on rename: %d -> %d", dialogBefore, dialogAfter)
	}
	if n := count(t, ix, `SELECT count(*) FROM media WHERE name='hdr.mkv'`); n != 0 {
		t.Fatal("deleted file still indexed")
	}
	if n := count(t, ix, `SELECT count(*) FROM media_fts WHERE media_fts MATCH '"hdr"'`); n != 0 {
		t.Fatal("deleted file still in fts")
	}
	if n := count(t, ix, `SELECT count(*) FROM media_fts WHERE media_fts MATCH '"_改名"'`); n != 1 {
		t.Fatalf("renamed file not searchable, n=%d", n)
	}
	// 未改动的文件保持 state=1(不重复补全)
	if n := count(t, ix, `SELECT count(*) FROM media WHERE state=0 AND name<>'深层.jpg'`); n != 0 {
		t.Fatalf("rescan reset unchanged media: state=0 count=%d", n)
	}
}

// 回归:两个根目录并发扫描 + 同时有 API 风格的写入,不能出现 "database is locked"。
func TestConcurrentScansAndWrites(t *testing.T) {
	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	cfg := config.Default()
	var libs []string
	for r := 0; r < 3; r++ {
		lib := t.TempDir()
		for d := 0; d < 30; d++ {
			dir := filepath.Join(lib, "dir", string(rune('A'+d%26))+string(rune('0'+d/26)))
			os.MkdirAll(dir, 0o755)
			for f := 0; f < 40; f++ {
				os.WriteFile(filepath.Join(dir, "p"+string(rune('a'+f%26))+string(rune('0'+f/26))+".jpg"), []byte("x"), 0o644)
			}
		}
		libs = append(libs, lib)
		cfg.Roots = append(cfg.Roots, config.Root{Path: lib, Label: "R"})
	}
	ix := New(db, cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	roots, err := ix.SyncRoots()
	if err != nil || len(roots) != 3 {
		t.Fatalf("roots=%v err=%v", roots, err)
	}
	errs := make(chan error, 8)
	done := make(chan struct{})
	for _, r := range roots {
		go func(r Root) { errs <- ix.ScanRoot(context.Background(), r) }(r)
	}
	go func() { // 模拟 API 写入
		defer close(done)
		for i := 0; i < 300; i++ {
			if _, err := db.Exec(`INSERT INTO user_dialog(user_id,dialog_id,pinned) VALUES(1,?,1) ON CONFLICT(user_id,dialog_id) DO UPDATE SET pinned=1`, i); err != nil {
				errs <- err
				return
			}
		}
	}()
	for i := 0; i < 3; i++ {
		if err := <-errs; err != nil {
			t.Fatalf("concurrent scan failed: %v", err)
		}
	}
	<-done
	if n := count(t, ix, `SELECT count(*) FROM media`); n != 3*30*40 {
		t.Fatalf("media=%d want %d", n, 3*30*40)
	}
}

// 续扫:上次被中断的扫描接着做(跳过已扫完的母文件夹),完整扫完后启动时在时限内不再重扫。
func TestResumeInterruptedScan(t *testing.T) {
	ix, r, lib := setup(t)
	ctx := context.Background()
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	if ix.ScanDue(r, time.Hour) {
		t.Fatal("刚完整扫描过,1 小时内不应再需要扫描")
	}
	if !ix.ScanDue(r, 0) {
		t.Fatal("maxAge=0 表示总是扫描")
	}
	// 模拟"扫到一半被关闭":状态改回 scanning;"旅行"已扫完,"视频"还没扫
	var gen, travel, video int64
	ix.DB.QueryRow(`SELECT gen FROM media LIMIT 1`).Scan(&gen)
	ix.DB.QueryRow(`SELECT id FROM dialogs WHERE title='旅行'`).Scan(&travel)
	ix.DB.QueryRow(`SELECT id FROM dialogs WHERE title='视频'`).Scan(&video)
	ix.DB.Exec(`UPDATE roots SET scan_state='scanning', scan_gen=? WHERE id=?`, gen, r.ID)
	ix.DB.Exec(`UPDATE dialogs SET scanned_gen=? WHERE id=?`, gen, travel)
	ix.DB.Exec(`UPDATE dialogs SET scanned_gen=0 WHERE id=?`, video)
	ix.DB.Exec(`UPDATE media SET gen=1 WHERE dialog_id=?`, video) // 还没扫到的群组,媒体行仍是上一轮扫描的代号
	if !ix.ScanDue(r, time.Hour) {
		t.Fatal("被中断的扫描必须续扫")
	}
	// 两个母文件夹里各删一个文件:已扫完的"旅行"会被跳过(仍留在库里),没扫完的"视频"会被重新扫描(随之消失)
	os.Remove(filepath.Join(lib, "旅行", "2023-京都", "IMG_0002.jpg"))
	os.Remove(filepath.Join(lib, "视频", "hdr.mkv"))
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	if n := count(t, ix, `SELECT count(*) FROM media WHERE name='IMG_0002.jpg'`); n != 1 {
		t.Fatalf("已扫完的母文件夹应被跳过,IMG_0002.jpg 仍应在库里, n=%d", n)
	}
	if n := count(t, ix, `SELECT count(*) FROM media WHERE name='hdr.mkv'`); n != 0 {
		t.Fatalf("没扫完的母文件夹应重新扫描,hdr.mkv 应已消失, n=%d", n)
	}
	resumed := false
	for _, p := range ix.Progress() {
		resumed = resumed || p.Resumed
	}
	if !resumed {
		t.Fatal("Progress.Resumed 应为 true")
	}
	if n := count(t, ix, `SELECT count(*) FROM roots WHERE scan_state='complete'`); n != 1 {
		t.Fatal("续扫完成后状态应为 complete")
	}
	if ix.ScanDue(r, time.Hour) {
		t.Fatal("续扫完成后不应再需要扫描")
	}
	// 手动重新扫描:丢弃中断记录,完整重扫,IMG_0002.jpg 这时才消失
	ix.ResetScan(r.ID)
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	if n := count(t, ix, `SELECT count(*) FROM media WHERE name='IMG_0002.jpg'`); n != 0 {
		t.Fatalf("完整重扫后 IMG_0002.jpg 应消失, n=%d", n)
	}
}
