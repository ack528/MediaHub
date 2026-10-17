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

// 同一个文件只能被锁一次;释放后可以再锁。
func TestLockFile(t *testing.T) {
	p := t.TempDir() + `\inst.lock`
	rel, err := LockFile(p)
	if err != nil {
		t.Fatal(err)
	}
	if _, err2 := LockFile(p); err2 == nil {
		t.Fatal("第二次加锁应该失败")
	}
	rel()
	rel2, err := LockFile(p)
	if err != nil {
		t.Fatalf("释放后应该能再加锁: %v", err)
	}
	rel2()
}
