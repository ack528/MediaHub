package store

import (
	"path/filepath"
	"testing"
)

func TestSearchRecovery(t *testing.T) {
	for _, corrupt := range []bool{false, true} {
		t.Run(map[bool]string{false: "missing-and-orphan", true: "corrupt-fts"}[corrupt], func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "hub.sqlite")
			db, err := OpenMigrated(path)
			if err != nil {
				t.Fatal(err)
			}
			_, err = db.Exec(`INSERT INTO media(id,dialog_id,root_id,name,name_sort,ext,type,size,mtime,ctime,taken_eff,fingerprint) VALUES(7,1,1,'京都旅行.mp4','','mp4',1,100,1,1,1,'100:1'); INSERT INTO kv VALUES('keep','account-and-queue-data'); INSERT INTO media_fts(rowid,name) VALUES(99,'orphan')`)
			if err != nil {
				t.Fatal(err)
			}
			if corrupt {
				if _, err := db.Exec(`UPDATE media_fts_data SET block=x'00' WHERE id=10`); err != nil {
					t.Fatal(err)
				}
				if _, err := db.Exec(`INSERT INTO media_fts(media_fts) VALUES('integrity-check')`); err == nil {
					t.Fatal("fixture must be corrupt")
				}
			}
			db.Close()
			db, err = OpenMigrated(path)
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			var id int64
			if err := db.QueryRow(`SELECT rowid FROM media_fts WHERE media_fts MATCH '京都旅'`).Scan(&id); err != nil || id != 7 {
				t.Fatalf("search=%d err=%v", id, err)
			}
			var n int
			if err := db.QueryRow(`SELECT count(*) FROM media_fts`).Scan(&n); err != nil || n != 1 {
				t.Fatalf("count=%d err=%v", n, err)
			}
			var value string
			if err := db.QueryRow(`SELECT v FROM kv WHERE k='keep'`).Scan(&value); err != nil || value != "account-and-queue-data" {
				t.Fatalf("preserved=%q err=%v", value, err)
			}
			if err := EnsureSearchIndex(db); err != nil {
				t.Fatal(err)
			}
		})
	}
}
