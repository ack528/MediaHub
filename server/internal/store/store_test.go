package store

import (
	"path/filepath"
	"testing"
)

// 验证设计假设:FTS5 trigram 分词器可用,且能对中文文件名做子串搜索;WAL 生效。
func TestFTS5TrigramChineseSubstring(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "t.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	var mode string
	if err := db.QueryRow("PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("journal_mode=%q err=%v", mode, err)
	}
	if _, err := db.Exec(`CREATE VIRTUAL TABLE fts USING fts5(name, tokenize='trigram')`); err != nil {
		t.Fatalf("fts5 trigram not available: %v", err)
	}
	names := []string{"2023-京都旅行-IMG_0001.heic", "家庭聚会_20240501.mp4", "IMG_9021.jpg", "京都清水寺.CR2"}
	for _, n := range names {
		if _, err := db.Exec(`INSERT INTO fts(name) VALUES (?)`, n); err != nil {
			t.Fatal(err)
		}
	}
	cases := map[string]int{"京都旅": 1, "京都": 0 /* 少于 3 个字符,trigram 需另行处理 */, "IMG_": 2, "聚会_2024": 1, "清水寺": 1}
	for q, want := range cases {
		var n int
		// 3 个字符以上走 MATCH;更短的走 LIKE 兜底(设计里要用)
		if len([]rune(q)) >= 3 {
			if err := db.QueryRow(`SELECT count(*) FROM fts WHERE fts MATCH ?`, `"`+q+`"`).Scan(&n); err != nil {
				t.Fatal(err)
			}
		} else {
			if err := db.QueryRow(`SELECT count(*) FROM fts WHERE name LIKE '%'||?||'%'`, q).Scan(&n); err != nil {
				t.Fatal(err)
			}
			want = 2 // "京都旅行" 与 "京都清水寺" 两条
		}
		if n != want {
			t.Errorf("query %q: got %d want %d", q, n, want)
		}
	}
}
