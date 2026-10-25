package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRestartRootDoesNotSelectDevelopmentConfig(t *testing.T) {
	root := t.TempDir()
	stable := filepath.Join(root, "persistent", "config.json")
	legacy := filepath.Join(root, "runtime", "mediahub", "config.json")
	for _, p := range []string{stable, legacy} {
		if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(`{"roots":[]}`), 0600); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("MEDIAHUB_ROOT", root)
	t.Setenv("MEDIAHUB_DEV", "")
	t.Setenv("MEDIAHUB_CONFIG", "")
	if got := defaultPath(root, stable); got != stable {
		t.Fatalf("update inherited root selected %s", got)
	}
	t.Setenv("MEDIAHUB_CONFIG", legacy)
	if got := defaultPath(root, stable); got != legacy {
		t.Fatalf("explicit config lost: %s", got)
	}
	t.Setenv("MEDIAHUB_CONFIG", "")
	t.Setenv("MEDIAHUB_DEV", "1")
	if got := defaultPath(root, stable); got != legacy {
		t.Fatalf("development config lost: %s", got)
	}
}

func TestConfigPathMadeAbsoluteForRestart(t *testing.T) {
	t.Chdir(t.TempDir())
	if err := os.WriteFile("config.json", []byte(`{"listen":"127.0.0.1:9123","roots":[]}`), 0600); err != nil {
		t.Fatal(err)
	}
	c, err := Load("config.json", ".")
	if err != nil {
		t.Fatal(err)
	}
	if !filepath.IsAbs(c.Path) || c.Listen != "127.0.0.1:9123" {
		t.Fatalf("%+v", c)
	}
}
