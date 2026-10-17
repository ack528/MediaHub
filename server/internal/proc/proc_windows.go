//go:build windows

// Package proc 放启动外部程序(ffmpeg / ffprobe / ExifTool)时的共用设置。
package proc

import (
	"os/exec"
	"sync/atomic"
	"syscall"
)

const (
	createNoWindow        = 0x08000000
	belowNormalPriority   = 0x00004000
	idlePriorityClass     = 0x00000040
	processSuspendResume  = 0x0800
	processSetInformation = 0x0200
)

// LowPriority 为 true 时,[Background] 启动的后台子进程用"低于正常"的 CPU 优先级(主进程 / 前台转码不受影响)。由 main 按配置设置。
var LowPriority atomic.Bool

// Hide 让子进程不弹出控制台窗口。
// 服务端以无控制台的方式运行(由管理程序脱离启动),此时 Windows 会为每个控制台子程序新建一个可见的 cmd 窗口,
// 扫描大库时就是"疯狂弹 ffmpeg 窗口";加上 CREATE_NO_WINDOW 即可避免。
func Hide(c *exec.Cmd) *exec.Cmd {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	return c
}

// Background 在 Hide 的基础上,让后台任务(压缩、重复检测取帧、补全元数据)的子进程以较低的 CPU 优先级运行,
// 有客户端在看视频时它们抢不过前台的转码和服务进程本身。
func Background(c *exec.Cmd) *exec.Cmd {
	flags := uint32(createNoWindow)
	if LowPriority.Load() {
		flags |= belowNormalPriority
	}
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: flags}
	return c
}

var (
	ntdll        = syscall.NewLazyDLL("ntdll.dll")
	procSuspend  = ntdll.NewProc("NtSuspendProcess")
	procResume   = ntdll.NewProc("NtResumeProcess")
	kernel32     = syscall.NewLazyDLL("kernel32.dll")
	procSetClass = kernel32.NewProc("SetPriorityClass")
)

func withHandle(pid int, access uint32, f func(h syscall.Handle) error) error {
	h, err := syscall.OpenProcess(access, false, uint32(pid))
	if err != nil {
		return err
	}
	defer syscall.CloseHandle(h)
	return f(h)
}

// Suspend 暂停一个进程(它的所有线程停止运行,占用的内存还在,但不再占 CPU / 磁盘)。用于"有客户端连接时暂停后台压缩"。
func Suspend(pid int) error {
	return withHandle(pid, processSuspendResume, func(h syscall.Handle) error {
		if r, _, _ := procSuspend.Call(uintptr(h)); r != 0 {
			return syscall.Errno(r)
		}
		return nil
	})
}

// Resume 恢复被 Suspend 的进程。
func Resume(pid int) error {
	return withHandle(pid, processSuspendResume, func(h syscall.Handle) error {
		if r, _, _ := procResume.Call(uintptr(h)); r != 0 {
			return syscall.Errno(r)
		}
		return nil
	})
}

// SetLowPriority 把一个已经在运行的进程降到"低于正常"(idle = true 时降到最低的"空闲"级别)。
func SetLowPriority(pid int, idle bool) error {
	class := uintptr(belowNormalPriority)
	if idle {
		class = idlePriorityClass
	}
	return withHandle(pid, processSetInformation, func(h syscall.Handle) error {
		if r, _, err := procSetClass.Call(uintptr(h), class); r == 0 {
			return err
		}
		return nil
	})
}
