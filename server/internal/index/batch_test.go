package index

import "testing"

func TestBatchRollbackDoesNotCommitPartialScan(t *testing.T) {
	ix, _, _ := setup(t)
	b := &batch{db: ix.DB}
	if err := b.begin(); err != nil {
		t.Fatal(err)
	}
	if _, err := b.tx.Exec(`INSERT INTO kv(k,v) VALUES('partial-scan','must-rollback')`); err != nil {
		t.Fatal(err)
	}
	b.rollback()
	if n := count(t, ix, `SELECT count(*) FROM kv WHERE k='partial-scan'`); n != 0 {
		t.Fatalf("committed partial scan: %d", n)
	}
}
