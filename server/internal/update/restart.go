package update

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

// Restart relaunches this installation with the same absolute configuration.
// It starts a detached helper first; failure to launch never stops the service.
func Restart(root, config string, stop func()) error {
	if stop == nil {
		return fmt.Errorf("服务退出回调不可用")
	}
	root, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	config, err = filepath.Abs(config)
	if err != nil {
		return err
	}
	if _, err = os.Stat(config); err != nil {
		return fmt.Errorf("配置文件不可读: %w", err)
	}
	if _, err = os.Stat(filepath.Join(root, "runtime", "bin", "mediahub.exe")); err != nil {
		return fmt.Errorf("服务程序不存在: %w", err)
	}
	dir := filepath.Join(root, "update")
	if err = os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	script := filepath.Join(dir, "restart.ps1")
	if err = os.WriteFile(script, append([]byte{0xef, 0xbb, 0xbf}, []byte(restartScript)...), 0600); err != nil {
		return err
	}
	cmd := exec.Command("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script, "-ServerPid", strconv.Itoa(os.Getpid()), "-Root", root, "-Config", config)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 | 0x00000200}
	if err = cmd.Start(); err != nil {
		return err
	}
	go func() { _ = cmd.Wait() }()
	go func() { time.Sleep(time.Second); stop() }()
	return nil
}

const restartScript = `param([int]$ServerPid,[string]$Root,[string]$Config)
$ErrorActionPreference='Stop'
$p=Get-Process -Id $ServerPid -ErrorAction SilentlyContinue
if ($p -and -not $p.WaitForExit(60000)) { exit 1 }
$env:MEDIAHUB_ROOT=$Root
$env:MEDIAHUB_CONFIG=$Config
$svcExe=Join-Path $Root 'runtime\bin\mediahub.exe'
Start-Process -FilePath $svcExe -ArgumentList @('serve','-config',('"'+$Config+'"')) -WorkingDirectory $Root -WindowStyle Hidden | Out-Null
`
