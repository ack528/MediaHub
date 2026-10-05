package index

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"mediahub/internal/config"
)

// 扫描过的文件夹从配置里移除(停用,数据保留)再加回来:直接复用已有索引,启动时不重新扫描。
func TestReAddedRootReusesIndex(t *testing.T) {
	ix, r, lib := setup(t)
	ctx := context.Background()
	if err := ix.ScanRoot(ctx, r); err != nil {
		t.Fatal(err)
	}
	if r.Reused {
		t.Fatal("第一次添加不应标记为复用")
	}
	before := count(t, ix, `SELECT count(*) FROM media`)
	if before == 0 {
		t.Fatal("扫描应有数据")
	}
	// 同一份配置再次启动:不是"重新添加"
	roots, err := ix.SyncRoots()
	if err != nil || len(roots) != 1 || roots[0].Reused || roots[0].ID != r.ID {
		t.Fatalf("重启: %+v %v", roots, err)
	}
	// 从配置里移除:数据保留,但变为停用
	ix.Cfg.Roots = nil
	if roots, _ = ix.SyncRoots(); len(roots) != 0 {
		t.Fatal("没有配置任何根目录")
	}
	if n := count(t, ix, `SELECT count(*) FROM roots WHERE enabled=1`); n != 0 {
		t.Fatalf("移除后应停用,enabled=1 的有 %d 个", n)
	}
	if n := count(t, ix, `SELECT count(*) FROM media`); n != before {
		t.Fatalf("移除根目录不应删除索引数据:%d → %d", before, n)
	}
	// 以不同的大小写重新添加:同一条记录,标记为复用,启动时可跳过扫描
	ix.Cfg.Roots = []config.Root{{Path: strings.ToUpper(lib), Label: "AGAIN"}}
	roots, err = ix.SyncRoots()
	if err != nil || len(roots) != 1 {
		t.Fatalf("%v %v", roots, err)
	}
	if !roots[0].Reused || roots[0].ID != r.ID {
		t.Fatalf("应复用原记录: %+v (原 id %d)", roots[0], r.ID)
	}
	if n := count(t, ix, `SELECT count(*) FROM roots`); n != 1 {
		t.Fatalf("不应新增 roots 记录,实际 %d", n)
	}
	if n := count(t, ix, `SELECT count(*) FROM media`); n != before {
		t.Fatalf("复用后数据应保持不变:%d → %d", before, n)
	}
}

// 目录名以空格 / 点结尾(Win32 普通路径会把它"规范化"掉,报 123 或找不到)也要能扫描到。
func TestScanDirWithTrailingSpace(t *testing.T) {
	ix, r, lib := setup(t)
	bad := `\\?\` + filepath.Join(lib, "带空格的文件夹 ")
	if err := os.MkdirAll(filepath.Join(bad, "sub."), 0o755); err != nil {
		t.Skipf("无法创建扩展路径目录: %v", err)
	}
	b, _ := os.ReadFile(filepath.Join(projectRoot(t), "testdata", "generated", "image", "sample.jpg"))
	if err := os.WriteFile(filepath.Join(bad, "sub.", "inner.jpg"), b, 0o644); err != nil {
		t.Skipf("无法创建文件: %v", err)
	}
	if err := ix.ScanRoot(context.Background(), r); err != nil {
		t.Fatal(err)
	}
	if n := count(t, ix, `SELECT count(*) FROM media WHERE name='inner.jpg'`); n != 1 {
		t.Fatalf("结尾带空格 / 点的目录里的文件应被索引, n=%d", n)
	}
	for _, p := range ix.Progress() {
		if p.Errors != 0 || len(p.FailedDirs) != 0 {
			t.Fatalf("不应有读取失败: %+v", p.FailedDirs)
		}
	}
	if err := ix.Enrich(context.Background(), r); err != nil {
		t.Fatal(err)
	}
	var state int
	ix.DB.QueryRow(`SELECT state FROM media WHERE name='inner.jpg'`).Scan(&state)
	if state != 1 {
		t.Fatalf("元数据应能读取(state=1),实际 %d", state)
	}
}
