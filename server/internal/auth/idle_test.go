package auth

import (
	"context"
	"mediahub/internal/store"
	"path/filepath"
	"testing"
	"time"
)

func TestIdleTokenVerificationDoesNotWaitForBackgroundWriter(t *testing.T) {
	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "idle.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := New(db, 30)
	s.SetIterations(1000)
	if err = s.CreateUser("me", "password"); err != nil {
		t.Fatal(err)
	}
	token, _, uid, err := s.Login("me", "password", "phone")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec(`UPDATE tokens SET last_seen=?`, time.Now().Add(-2*time.Hour).Unix()); err != nil {
		t.Fatal(err)
	}
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`UPDATE users SET created=created WHERE name='me'`); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	start := time.Now()
	got, _, _, ok, err := s.VerifyInfoContext(ctx, token)
	if err != nil || !ok || got != uid {
		t.Fatalf("idle session rejected: %d %v %v", got, ok, err)
	}
	if time.Since(start) > 400*time.Millisecond {
		t.Fatal("authentication waited for token maintenance write")
	}
	tx.Rollback()
	for deadline := time.Now().Add(2 * time.Second); len(s.touchSlot) > 0 && time.Now().Before(deadline); {
		time.Sleep(10 * time.Millisecond)
	}
	var seen int64
	if err = db.QueryRow(`SELECT last_seen FROM tokens`).Scan(&seen); err != nil {
		t.Fatal(err)
	}
	if seen < time.Now().Add(-time.Minute).Unix() {
		t.Fatal("maintenance did not resume after lock released")
	}
}

func TestVerificationReportsDatabaseFailureSeparatelyFromInvalidToken(t *testing.T) {
	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "failure.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	s := New(db, 30)
	db.Close()
	_, _, _, ok, err := s.VerifyInfoContext(context.Background(), "token")
	if ok || err == nil {
		t.Fatal("database failure reported as invalid credential")
	}
}
