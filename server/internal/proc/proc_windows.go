//go:build windows

// Package proc 放启动外部程序(ffmpeg / ffprobe / ExifTool)时的共用设置。
package proc

import (
	"os/exec"
	"syscall"
)

const createNoWindow = 0x08000000

// Hide 让子进程不弹出控制台窗口。
// 服务端以无控制台的方式运行(由管理程序脱离启动),此时 Windows 会为每个控制台子程序新建一个可见的 cmd 窗口,
// 扫描大库时就是"疯狂弹 ffmpeg 窗口";加上 CREATE_NO_WINDOW 即可避免。
func Hide(c *exec.Cmd) *exec.Cmd {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	return c
}
