package logx

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSetupTailRecoverBundle(t *testing.T) {
	NoStderrRedirect = true
	dir := t.TempDir()
	m, err := Setup(dir, "test-1.0", "info", nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(m.Close)
	m.Log.Info("你好", "k", "v")
	m.Log.Debug("调试信息不应出现(级别 info)")
	m.Log.Warn("警告一条")
	m.Log.Error("错误一条")
	if got := strings.Join(m.Tail(50, ""), "\n"); !strings.Contains(got, "你好") || strings.Contains(got, "调试信息") {
		t.Fatalf("tail 内容不对: %s", got)
	}
	if got := m.Tail(50, "warn"); len(got) != 2 {
		t.Fatalf("warn 级别应有 2 行(警告 + 错误),got %d", len(got))
	}
	m.SetLevel("debug")
	m.Log.Debug("现在能看到调试")
	if got := strings.Join(m.Tail(50, ""), "\n"); !strings.Contains(got, "现在能看到调试") {
		t.Fatal("调整为 debug 后应能看到调试日志")
	}

	// panic 被捕获,写出崩溃报告,调用方继续运行
	done := make(chan struct{})
	Go("测试协程", func() {
		defer close(done)
		panic("boom")
	})
	<-done
	// 等崩溃报告落盘
	var crash []string
	for i := 0; i < 50 && len(crash) == 0; i++ {
		crash = m.CrashFiles()
		if len(crash) == 0 {
			os.Stat(dir)
		}
	}
	if len(crash) == 0 {
		t.Fatal("没有生成崩溃报告")
	}
	b, _ := os.ReadFile(filepath.Join(m.Dir, crash[0]))
	if !strings.Contains(string(b), "boom") || !strings.Contains(string(b), "测试协程") || !strings.Contains(string(b), "test-1.0") {
		t.Fatalf("崩溃报告缺内容:\n%s", b)
	}

	// 打包
	var buf bytes.Buffer
	if err := m.Bundle(&buf); err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, f := range zr.File {
		names[f.Name] = true
	}
	if !names["environment.txt"] || !names[crash[0]] {
		t.Fatalf("zip 里应包含 environment.txt 和崩溃报告, got %v", names)
	}
}
