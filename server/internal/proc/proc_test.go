//go:build windows

package proc

import (
	"os/exec"
	"testing"
	"time"
)

// 挂起 / 恢复 / 降优先级一个真实的子进程:不报错,进程仍然存活直到被结束。
func TestSuspendResumeLowPriority(t *testing.T) {
	cmd := Background(exec.Command("ping", "-n", "30", "127.0.0.1"))
	if err := cmd.Start(); err != nil {
		t.Skip("没有 ping 可用:", err)
	}
	defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
	pid := cmd.Process.Pid
	time.Sleep(200 * time.Millisecond)
	if err := SetLowPriority(pid, false); err != nil {
		t.Fatalf("降优先级: %v", err)
	}
	if err := Suspend(pid); err != nil {
		t.Fatalf("挂起: %v", err)
	}
	time.Sleep(200 * time.Millisecond)
	if err := Resume(pid); err != nil {
		t.Fatalf("恢复: %v", err)
	}
	if cmd.ProcessState != nil {
		t.Fatal("进程不应该已经退出")
	}
}
