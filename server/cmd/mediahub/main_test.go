package main

import (
	"flag"
	"io"
	"log/slog"
	"mediahub/internal/config"
	"mediahub/internal/store"
	"path/filepath"
	"testing"
)

func TestExplicitMissingConfigurationDoesNotStartWithDefaults(t *testing.T) {
	_, _, err := loadCfg(flag.NewFlagSet("test", flag.ContinueOnError), []string{"-config", filepath.Join(t.TempDir(), "missing.json")})
	if err == nil {
		t.Fatal("explicit missing configuration silently reset to defaults")
	}
}

func TestExplicitlyEmptyRootsAreNotRestoredAfterRestart(t *testing.T) {
	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "hub.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	old := t.TempDir()
	if _, err = db.Exec(`INSERT INTO roots(path,label,enabled) VALUES(?,?,1)`, old, "old"); err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	cfg := config.Default()
	cfg.Roots = []config.Root{}
	cfg.RootsUnset = false
	restoreRoots(db, cfg, logger)
	if len(cfg.Roots) != 0 {
		t.Fatal("explicit empty configuration was overwritten")
	}
	cfg.RootsUnset = true
	restoreRoots(db, cfg, logger)
	if len(cfg.Roots) != 1 || cfg.Roots[0].Path != old {
		t.Fatal("missing configuration no longer recovers roots")
	}
}
