package store

import (
	"database/sql"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func repairFixture(t *testing.T) (string, *sql.DB) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "hub.sqlite")
	db, err := OpenMigrated(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	_, err = db.Exec(`
INSERT INTO media(id,dialog_id,root_id,name,name_sort,ext,type,size,mtime,ctime,taken_eff,fingerprint,gen)
 VALUES(63452,1,1,'京都旅行.mp4','','mp4',1,100,1,1,1,'100:1',7);
INSERT INTO media_fts(rowid,name) VALUES(63452,'京都旅行.mp4');
INSERT INTO users(id,name,pw_hash,created,is_admin) VALUES(1,'owner','preserve-hash',1,1);
INSERT INTO tokens(hash,user_id,device,expires,last_seen) VALUES('keep-token',1,'phone',100,1);
INSERT INTO user_playback VALUES(1,63452,12345,1);
INSERT INTO user_browse_history VALUES(1,1,1,'history');
INSERT INTO opt_job(id,media_id,state,codec,bitrate_kbps,created,updated) VALUES(1,63452,'queued','hevc',2000,1,1);
`)
	if err != nil {
		t.Fatal(err)
	}
	return path, db
}

// Empty a real index leaf, leaving the media table intact. This reproduces
// "row 63452 missing from index ix_m_gen" without changing its schema.
func damagePage(t *testing.T, db *sql.DB, path, name string, indexOnly bool) {
	t.Helper()
	var page, size int
	if err := db.QueryRow(`SELECT rootpage FROM sqlite_schema WHERE name=?`, name).Scan(&page); err != nil {
		t.Fatal(err)
	}
	if err := db.QueryRow(`PRAGMA page_size`).Scan(&size); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	b := make([]byte, size)
	if indexOnly {
		b[0] = 0x0a // index leaf with no cells
		binary.BigEndian.PutUint16(b[5:7], uint16(size))
	} else {
		b[0] = 0xff // invalid table page type; no safe index repair is possible
	}
	if _, err := f.WriteAt(b, int64((page-1)*size)); err != nil {
		t.Fatal(err)
	}
}

func assertRepairData(t *testing.T, db *sql.DB) {
	t.Helper()
	for _, q := range []string{
		`SELECT count(*) FROM media WHERE id=63452 AND gen=7`,
		`SELECT count(*) FROM media INDEXED BY ix_m_gen WHERE root_id=1 AND gen=7`,
		`SELECT count(*) FROM media_fts WHERE media_fts MATCH '京都旅' AND rowid=63452`,
		`SELECT count(*) FROM users WHERE pw_hash='preserve-hash' AND is_admin=1`,
		`SELECT count(*) FROM tokens WHERE hash='keep-token'`,
		`SELECT count(*) FROM user_playback WHERE pos_ms=12345`,
		`SELECT count(*) FROM user_browse_history WHERE json='history'`,
		`SELECT count(*) FROM opt_job WHERE state='queued' AND media_id=63452`,
	} {
		var n int
		if err := db.QueryRow(q).Scan(&n); err != nil || n != 1 {
			t.Fatalf("%s: n=%d err=%v", q, n, err)
		}
	}
}

func TestStartupRepairsIndexAndFTSWithPreservedSnapshot(t *testing.T) {
	path, db := repairFixture(t)
	if _, err := db.Exec(`UPDATE media_fts_data SET block=x'00' WHERE id=10`); err != nil {
		t.Fatal(err)
	}
	damagePage(t, db, path, "ix_m_gen", true)
	damaged, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := integrityCheck(damaged); err == nil {
		t.Fatal("fixture is not corrupt")
	}
	damaged.Close()
	repaired, err := OpenMigratedWithRepair(path)
	if err != nil {
		t.Fatal(err)
	}
	assertRepairData(t, repaired)
	if err := integrityCheck(repaired); err != nil {
		t.Fatal(err)
	}
	repaired.Close()
	snapshots, err := filepath.Glob(filepath.Join(filepath.Dir(path), "repair", "*.sqlite"))
	if err != nil || len(snapshots) != 1 {
		t.Fatalf("snapshots=%v err=%v", snapshots, err)
	}
	original, err := Open(snapshots[0])
	if err != nil {
		t.Fatal(err)
	}
	defer original.Close()
	if err := integrityCheck(original); err == nil {
		t.Fatal("snapshot should retain the original corruption")
	}
	var n int
	if err := original.QueryRow(`SELECT count(*) FROM users WHERE pw_hash='preserve-hash'`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("snapshot data lost: %d %v", n, err)
	}
	// A second startup neither repeats repair nor creates another snapshot.
	repaired, err = OpenMigratedWithRepair(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repaired.Close()
	assertRepairData(t, repaired)
	again, _ := filepath.Glob(filepath.Join(filepath.Dir(path), "repair", "*.sqlite"))
	if len(again) != 1 {
		t.Fatalf("unnecessary repair: %v", again)
	}
}

func TestStartupRepairPreservesCommittedWAL(t *testing.T) {
	path, db := repairFixture(t)
	// Keep the writer connection open so committed data remains in WAL.
	if _, err := db.Exec(`INSERT INTO kv VALUES('wal-only','keep'); UPDATE media_fts_data SET block=x'00' WHERE id=10`); err != nil {
		t.Fatal(err)
	}
	repaired, err := OpenMigratedWithRepair(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repaired.Close()
	assertRepairData(t, repaired)
	snapshots, _ := filepath.Glob(filepath.Join(filepath.Dir(path), "repair", "*.sqlite"))
	if len(snapshots) != 1 {
		t.Fatalf("snapshots=%v", snapshots)
	}
	original, err := Open(snapshots[0])
	if err != nil {
		t.Fatal(err)
	}
	defer original.Close()
	var v string
	if err := original.QueryRow(`SELECT v FROM kv WHERE k='wal-only'`).Scan(&v); err != nil || v != "keep" {
		t.Fatalf("committed WAL missing: %q %v", v, err)
	}
}

func TestRepairRefusesUnrecoverableTableDamage(t *testing.T) {
	path, db := repairFixture(t)
	damagePage(t, db, path, "media", false)
	got, err := OpenMigratedWithRepair(path)
	if got != nil {
		got.Close()
		t.Fatal("must not open an unrepaired database")
	}
	if err == nil || !strings.Contains(err.Error(), "自修复失败") {
		t.Fatalf("err=%v", err)
	}
	// REINDEX/FTS rebuild must not leave a partial repair behind.
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var name string
	if err := db.QueryRow(`SELECT name FROM media_fts WHERE rowid=63452`).Scan(&name); err != nil || name != "京都旅行.mp4" {
		t.Fatalf("FTS changed despite rollback: %q %v", name, err)
	}
}

func TestRepairRequiresSnapshotBeforeWriting(t *testing.T) {
	path, db := repairFixture(t)
	if _, err := db.Exec(`UPDATE media_fts_data SET block=x'00' WHERE id=10`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	if err := os.WriteFile(filepath.Join(filepath.Dir(path), "repair"), []byte("block snapshot creation"), 0600); err != nil {
		t.Fatal(err)
	}
	if got, err := OpenMigratedWithRepair(path); err == nil {
		got.Close()
		t.Fatal("must refuse repair without snapshot")
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var b []byte
	if err := db.QueryRow(`SELECT block FROM media_fts_data WHERE id=10`).Scan(&b); err != nil || len(b) != 1 || b[0] != 0 {
		t.Fatalf("damaged data modified: %x %v", b, err)
	}
}

func TestRepairRollsBackWhenFinalValidationFails(t *testing.T) {
	path, db := repairFixture(t)
	_, err := db.Exec(`CREATE TABLE guarded(v INTEGER CHECK(v>0));
PRAGMA ignore_check_constraints=ON;
INSERT INTO guarded VALUES(-1);
PRAGMA ignore_check_constraints=OFF;
UPDATE media_fts_data SET block=x'00' WHERE id=10;`)
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	if got, err := OpenMigratedWithRepair(path); err == nil {
		got.Close()
		t.Fatal("must not accept a database that fails final validation")
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var b []byte
	if err := db.QueryRow(`SELECT block FROM media_fts_data WHERE id=10`).Scan(&b); err != nil || len(b) != 1 || b[0] != 0 {
		t.Fatalf("FTS repair was not rolled back: %x %v", b, err)
	}
}

func TestHealthyStartupDoesNotCreateRepairCopy(t *testing.T) {
	path := filepath.Join(t.TempDir(), "hub.sqlite")
	for i := 0; i < 2; i++ {
		db, err := OpenMigratedWithRepair(path)
		if err != nil {
			t.Fatal(err)
		}
		db.Close()
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(path), "repair")); !os.IsNotExist(err) {
		t.Fatalf("healthy database should not create repair copies: %v", err)
	}
}

func TestStartupRepairsMissingSearchTable(t *testing.T) {
	path, db := repairFixture(t)
	if _, err := db.Exec(`DROP TABLE media_fts`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	repaired, err := OpenMigratedWithRepair(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repaired.Close()
	assertRepairData(t, repaired)
}

type codedTestError int

func (c codedTestError) Code() int     { return int(c) }
func (c codedTestError) Error() string { return fmt.Sprint(int(c)) }

func TestCorruptionClassification(t *testing.T) {
	for _, code := range []int{11, 267, 779, 26} {
		if !IsCorruption(fmt.Errorf("wrapped: %w", codedTestError(code))) {
			t.Fatalf("missed %d", code)
		}
	}
	for _, code := range []int{5, 6, 10, 13, 19, 1} {
		if IsCorruption(codedTestError(code)) {
			t.Fatalf("should not repair %d", code)
		}
	}
	if IsCorruption(nil) {
		t.Fatal("nil is not corruption")
	}
}
