//go:build windows

package logx

import (
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
)

// redirectStderr 把进程的 stderr 重定向到 <日志目录>\stderr.log。
// 服务端是由管理程序脱离启动的,stderr 本来没有去处;Go 运行时的致命错误(并发写 map、栈溢出、OOM 等)
// 不能被 recover 捕获,只会写到 stderr,重定向之后这些信息就能留下来。
func redirectStderr(dir string) {
	f, err := os.OpenFile(filepath.Join(dir, "stderr.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	if st, e := f.Stat(); e == nil && st.Size() > 5<<20 { // 过大就重新开始
		f.Close()
		f, err = os.OpenFile(filepath.Join(dir, "stderr.log"), os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
		if err != nil {
			return
		}
	}
	if err := windows.SetStdHandle(windows.STD_ERROR_HANDLE, windows.Handle(f.Fd())); err == nil {
		os.Stderr = f
	}
}
