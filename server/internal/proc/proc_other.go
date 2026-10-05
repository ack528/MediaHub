//go:build !windows

// Package proc 放启动外部程序时的共用设置。
package proc

import "os/exec"

// Hide 在非 Windows 平台不需要做任何事。
func Hide(c *exec.Cmd) *exec.Cmd { return c }
