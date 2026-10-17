//go:build !windows

// Package proc 放启动外部程序时的共用设置。
package proc

import (
	"os/exec"
	"sync/atomic"
)

// LowPriority 在非 Windows 平台没有作用。
var LowPriority atomic.Bool

// Hide 在非 Windows 平台不需要做任何事。
func Hide(c *exec.Cmd) *exec.Cmd { return c }

// Background 在非 Windows 平台不需要做任何事。
func Background(c *exec.Cmd) *exec.Cmd { return c }

// Suspend / Resume / SetLowPriority 只在 Windows 上实现。
func Suspend(pid int) error                   { return nil }
func Resume(pid int) error                    { return nil }
func SetLowPriority(pid int, idle bool) error { return nil }

// LockFile 在非 Windows 平台不做任何事(开发 / 测试用)。
func LockFile(path string) (release func(), err error) { return func() {}, nil }
