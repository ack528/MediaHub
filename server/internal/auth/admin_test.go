package auth

import (
	"mediahub/internal/store"
	"path/filepath"
	"testing"
)

func TestFirstAdminAndLocalGrant(t *testing.T) {
	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "users.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := New(db, 30)
	s.SetIterations(1000)
	for _, name := range []string{"first", "second"} {
		if err = s.CreateUser(name, "password"); err != nil {
			t.Fatal(err)
		}
	}
	users, err := s.ListUsers()
	if err != nil || len(users) != 2 || !users[0].IsAdmin || users[1].IsAdmin {
		t.Fatalf("initial roles: %v %v", users, err)
	}
	if s.SetAdmin("first", false) == nil {
		t.Fatal("revoked last administrator")
	}
	if s.DeleteUser("first") == nil {
		t.Fatal("deleted last administrator with remaining users")
	}
	if err = s.SetAdmin("second", true); err != nil {
		t.Fatal(err)
	}
	if err = s.SetAdmin("first", false); err != nil {
		t.Fatal(err)
	}
	if err = s.DeleteUser("first"); err != nil {
		t.Fatal(err)
	}
	if s.SetAdmin("missing", true) == nil {
		t.Fatal("granted nonexistent user")
	}
}
