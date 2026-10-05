// Package logx 是服务端的日志与崩溃捕获:
//   - 日志写到 <数据目录>\logs\mediahub-YYYYMMDD.log,单个文件超过 20 MB 自动切分,只保留最近 14 天且总量不超过 300 MB;
//   - 内存里留最近 3000 行,管理程序随时可以查看,不用读盘;
//   - 任何后台协程 / HTTP 请求里的 panic 都被捕获:记日志、写 crash-*.txt(堆栈 + 运行环境 + 最近日志),服务继续运行;
//   - 主流程崩溃时同样写崩溃报告再退出;运行时的致命错误(比如并发写 map)由重定向后的 stderr 记到 stderr.log;
//   - 可打包成 zip(日志 + 崩溃报告 + 运行环境,不含密码 / 令牌 / API 密钥),方便把问题发给开发者。
package logx

import (
	"archive/zip"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	maxFileBytes  = 20 << 20
	keepDays      = 14
	keepTotal     = 300 << 20
	ringLines     = 3000
	keepCrashFile = 20
)

// Manager 持有日志目录、级别和内存环形缓冲。
type Manager struct {
	Dir     string
	Version string
	Lvl     *slog.LevelVar
	Log     *slog.Logger

	rw   *rotWriter
	ring *ringBuf
}

var def *Manager

// NoStderrRedirect 为 true 时 Setup 不重定向 stderr(测试用)。
var NoStderrRedirect bool

// Default 返回 Setup 创建的全局管理器(没有创建时返回 nil,调用方要容忍)。
func Default() *Manager { return def }

// Setup 创建日志系统。level 取 debug / info / warn / error(空 = info)。同时把进程的 stderr 重定向到 stderr.log。
func Setup(dataDir, version, level string, mirror io.Writer) (*Manager, error) {
	dir := filepath.Join(dataDir, "logs")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	m := &Manager{Dir: dir, Version: version, Lvl: new(slog.LevelVar)}
	m.SetLevel(level)
	m.rw = &rotWriter{dir: dir}
	m.rw.cleanup()
	m.ring = &ringBuf{}
	var w io.Writer = io.MultiWriter(m.rw, m.ring)
	if mirror != nil {
		w = io.MultiWriter(m.rw, m.ring, mirror)
	}
	m.Log = slog.New(slog.NewTextHandler(w, &slog.HandlerOptions{Level: m.Lvl}))
	if !NoStderrRedirect {
		redirectStderr(dir)
	}
	def = m
	return m, nil
}

// Close 关闭日志文件(退出前或测试清理时调用)。
func (m *Manager) Close() {
	m.rw.mu.Lock()
	defer m.rw.mu.Unlock()
	if m.rw.f != nil {
		m.rw.f.Close()
		m.rw.f = nil
	}
}

func (m *Manager) SetLevel(level string) {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug":
		m.Lvl.Set(slog.LevelDebug)
	case "warn", "warning":
		m.Lvl.Set(slog.LevelWarn)
	case "error":
		m.Lvl.Set(slog.LevelError)
	default:
		m.Lvl.Set(slog.LevelInfo)
	}
}

// Tail 返回内存里最近的 n 行日志;level 非空时只要该级别及以上(debug / info / warn / error)。
func (m *Manager) Tail(n int, level string) []string {
	lines := m.ring.snapshot()
	min := levelRank(level)
	if min > 0 {
		var out []string
		for _, l := range lines {
			if lineRank(l) >= min {
				out = append(out, l)
			}
		}
		lines = out
	}
	if n > 0 && len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines
}

func levelRank(s string) int {
	switch strings.ToLower(s) {
	case "debug":
		return 1
	case "info":
		return 2
	case "warn":
		return 3
	case "error":
		return 4
	}
	return 0
}

func lineRank(l string) int {
	switch {
	case strings.Contains(l, " level=ERROR"):
		return 4
	case strings.Contains(l, " level=WARN"):
		return 3
	case strings.Contains(l, " level=INFO"):
		return 2
	}
	return 1
}

// ---------------------------------------------------------------- panic / 崩溃

// Go 在新协程里运行 fn,里面的 panic 被捕获(记日志 + 写崩溃报告),不会拖垮整个服务。
func Go(where string, fn func()) {
	go func() {
		defer Recover(where)
		fn()
	}()
}

// Recover 用在 defer 里:捕获 panic,记日志并写崩溃报告;服务继续运行。
func Recover(where string) {
	if r := recover(); r != nil {
		stack := debug.Stack()
		if def != nil {
			def.Log.Error("捕获到 panic(已记录,服务继续运行)", "where", where, "panic", fmt.Sprint(r))
			def.WriteCrash(where, r, stack)
		} else {
			fmt.Fprintf(os.Stderr, "panic in %s: %v\n%s\n", where, r, stack)
		}
	}
}

// WriteCrash 写 crash-YYYYMMDD-HHMMSS.txt:堆栈、运行环境、最近的日志。返回文件路径。
func (m *Manager) WriteCrash(where string, r any, stack []byte) string {
	name := "crash-" + time.Now().Format("20060102-150405") + ".txt"
	p := filepath.Join(m.Dir, name)
	var b strings.Builder
	fmt.Fprintf(&b, "=== MediaHub 崩溃报告 ===\n时间: %s\n位置: %s\npanic: %v\n\n%s\n=== 堆栈 ===\n%s\n", time.Now().Format(time.RFC3339), where, r, m.Environment(), stack)
	b.WriteString("\n=== 最近的日志 ===\n")
	for _, l := range m.Tail(300, "") {
		b.WriteString(l)
		b.WriteByte('\n')
	}
	_ = os.WriteFile(p, []byte(b.String()), 0o644)
	m.trimCrashFiles()
	return p
}

func (m *Manager) trimCrashFiles() {
	ents, _ := os.ReadDir(m.Dir)
	var crashes []string
	for _, e := range ents {
		if strings.HasPrefix(e.Name(), "crash-") {
			crashes = append(crashes, e.Name())
		}
	}
	sort.Strings(crashes)
	for len(crashes) > keepCrashFile {
		_ = os.Remove(filepath.Join(m.Dir, crashes[0]))
		crashes = crashes[1:]
	}
}

// Environment 运行环境说明(不含任何密码 / 令牌 / API 密钥)。
func (m *Manager) Environment() string {
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	host, _ := os.Hostname()
	return fmt.Sprintf("版本: %s\nGo: %s %s/%s\n主机: %s\nCPU 核数: %d  协程数: %d\n内存: 堆 %d MB,系统 %d MB\n日志目录: %s\n",
		m.Version, runtime.Version(), runtime.GOOS, runtime.GOARCH, host, runtime.NumCPU(), runtime.NumGoroutine(),
		ms.HeapAlloc>>20, ms.Sys>>20, m.Dir)
}

// CrashFiles 列出崩溃报告(旧 → 新)。
func (m *Manager) CrashFiles() []string {
	ents, _ := os.ReadDir(m.Dir)
	var out []string
	for _, e := range ents {
		if strings.HasPrefix(e.Name(), "crash-") {
			out = append(out, e.Name())
		}
	}
	sort.Strings(out)
	return out
}

// Bundle 把日志、崩溃报告和运行环境打成 zip 写入 w。
func (m *Manager) Bundle(w io.Writer) error {
	zw := zip.NewWriter(w)
	put := func(name string, data []byte) error {
		f, err := zw.Create(name)
		if err != nil {
			return err
		}
		_, err = f.Write(data)
		return err
	}
	if err := put("environment.txt", []byte(m.Environment())); err != nil {
		return err
	}
	ents, _ := os.ReadDir(m.Dir)
	for _, e := range ents {
		if e.IsDir() {
			continue
		}
		b, err := os.ReadFile(filepath.Join(m.Dir, e.Name()))
		if err != nil {
			continue
		}
		if err := put(e.Name(), b); err != nil {
			return err
		}
	}
	return zw.Close()
}

// ---------------------------------------------------------------- 文件轮转

type rotWriter struct {
	mu   sync.Mutex
	dir  string
	f    *os.File
	day  string
	size int64
}

func (w *rotWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	day := time.Now().Format("20060102")
	if w.f == nil || w.day != day || w.size > maxFileBytes {
		w.rotate(day)
	}
	if w.f == nil {
		return len(p), nil
	}
	n, err := w.f.Write(p)
	w.size += int64(n)
	return n, err
}

func (w *rotWriter) rotate(day string) {
	if w.f != nil {
		w.f.Close()
		if w.day == day { // 同一天内超过大小:把当前文件改名存档,再开新的
			base := filepath.Join(w.dir, "mediahub-"+day)
			for i := 1; ; i++ {
				arc := fmt.Sprintf("%s.%d.log", base, i)
				if _, err := os.Stat(arc); os.IsNotExist(err) {
					_ = os.Rename(base+".log", arc)
					break
				}
			}
		}
	}
	f, err := os.OpenFile(filepath.Join(w.dir, "mediahub-"+day+".log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		w.f = nil
		return
	}
	st, _ := f.Stat()
	w.f, w.day = f, day
	if st != nil {
		w.size = st.Size()
	}
	go w.cleanup()
}

// cleanup 删掉超过保留天数的日志,并把总量控制在上限内(先删最旧的)。崩溃报告和 stderr.log 不在此列。
func (w *rotWriter) cleanup() {
	ents, err := os.ReadDir(w.dir)
	if err != nil {
		return
	}
	type fi struct {
		name string
		mod  time.Time
		size int64
	}
	var logs []fi
	var total int64
	for _, e := range ents {
		if !strings.HasPrefix(e.Name(), "mediahub-") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		if time.Since(info.ModTime()) > keepDays*24*time.Hour {
			_ = os.Remove(filepath.Join(w.dir, e.Name()))
			continue
		}
		logs = append(logs, fi{e.Name(), info.ModTime(), info.Size()})
		total += info.Size()
	}
	sort.Slice(logs, func(i, j int) bool { return logs[i].mod.Before(logs[j].mod) })
	for i := 0; total > keepTotal && i < len(logs)-1; i++ {
		_ = os.Remove(filepath.Join(w.dir, logs[i].name))
		total -= logs[i].size
	}
}

// ---------------------------------------------------------------- 内存环形缓冲

type ringBuf struct {
	mu    sync.Mutex
	lines []string
	part  string
}

func (r *ringBuf) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	s := r.part + string(p)
	for {
		i := strings.IndexByte(s, '\n')
		if i < 0 {
			break
		}
		r.lines = append(r.lines, s[:i])
		s = s[i+1:]
	}
	r.part = s
	if len(r.lines) > ringLines*2 {
		r.lines = append([]string(nil), r.lines[len(r.lines)-ringLines:]...)
	}
	return len(p), nil
}

func (r *ringBuf) snapshot() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	lines := r.lines
	if len(lines) > ringLines {
		lines = lines[len(lines)-ringLines:]
	}
	return append([]string(nil), lines...)
}
