package update

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMain(m *testing.M) {
	if path := os.Getenv("MEDIAHUB_ARG_PROBE"); path != "" {
		cwd, _ := os.Getwd()
		b, _ := json.Marshal(map[string]any{"args": os.Args[1:], "cwd": cwd, "config": os.Getenv("MEDIAHUB_CONFIG")})
		if os.WriteFile(path, b, 0600) != nil {
			os.Exit(3)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func TestInstallerRestartRetainsConfigWithSpacesAndUnicode(t *testing.T) {
	ps, err := exec.LookPath("powershell.exe")
	if err != nil {
		t.Skip("Windows PowerShell unavailable")
	}
	root := filepath.Join(t.TempDir(), "便携程序 with spaces")
	if err = os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	cfg := filepath.Join(root, "自定义 配置.json")
	probe := filepath.Join(root, "args.json")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	start := strings.Index(applyScript, "function Start-Hub {")
	end := strings.Index(applyScript[start:], "\n}") + start + 2
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	script := "$Root=" + quote(root) + "\n$Config=" + quote(cfg) + "\n$svcExe=" + quote(exe) + "\n" + applyScript[start:end] + "\nStart-Hub\n"
	p := filepath.Join(root, "test.ps1")
	if err = os.WriteFile(p, append([]byte{0xef, 0xbb, 0xbf}, []byte(script)...), 0600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(ps, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", p)
	cmd.Env = append(os.Environ(), "MEDIAHUB_ARG_PROBE="+probe, "MEDIAHUB_CONFIG=wrong.json")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%v: %s", err, out)
	}
	var b []byte
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); {
		b, err = os.ReadFile(probe)
		if err == nil {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	var got struct {
		Args        []string
		Cwd, Config string
	}
	if json.Unmarshal(b, &got) != nil {
		t.Fatalf("helper did not write probe: %s", b)
	}
	if len(got.Args) != 3 || got.Args[0] != "serve" || got.Args[1] != "-config" || got.Args[2] != cfg || got.Config != cfg || got.Cwd != root {
		t.Fatalf("configuration lost: %+v", got)
	}
}

func TestRemoteRestartScriptRetainsConfig(t *testing.T) {
	ps, err := exec.LookPath("powershell.exe")
	if err != nil {
		t.Skip("Windows PowerShell unavailable")
	}
	root := filepath.Join(t.TempDir(), "远程 restart with spaces")
	bin := filepath.Join(root, "runtime", "bin")
	if err = os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	exe, _ := os.Executable()
	binary, err := os.ReadFile(exe)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(bin, "mediahub.exe"), binary, 0700); err != nil {
		t.Fatal(err)
	}
	cfg := filepath.Join(root, "持久 配置.json")
	probe := filepath.Join(root, "probe.json")
	script := filepath.Join(root, "restart.ps1")
	if err = os.WriteFile(script, append([]byte{0xef, 0xbb, 0xbf}, []byte(restartScript)...), 0600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(ps, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, "-ServerPid", "2147483600", "-Root", root, "-Config", cfg)
	cmd.Env = append(os.Environ(), "MEDIAHUB_ARG_PROBE="+probe, "MEDIAHUB_CONFIG=wrong.json")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%v: %s", err, out)
	}
	var got struct {
		Args        []string
		Cwd, Config string
	}
	var b []byte
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); {
		b, err = os.ReadFile(probe)
		if err == nil {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if json.Unmarshal(b, &got) != nil || len(got.Args) != 3 || got.Args[2] != cfg || got.Config != cfg || got.Cwd != root {
		t.Fatalf("remote restart lost config: %s", b)
	}
}
