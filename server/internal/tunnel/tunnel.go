// Package tunnel 托管"内网穿透"客户端:服务端启动时按配置拉起 frpc(或任意自定义命令),掉线自动重连,
// 并定期通过外网地址探测整条链路的延迟,连续失败时自动重启穿透进程。
//
// 为什么放在服务端里:穿透客户端的配置(服务器地址、令牌、协议、连接池……)和 MediaHub 的端口、证书是一体的,
// 放进同一份配置、同一个管理界面,不用再单独维护一个 frpc 的窗口 / 开机自启;日志和状态也能在管理程序里一起看。
//
// 本包不内置 frpc:需要自己把 frpc.exe 放到 tools\frp\(或在配置里填路径)。frp 版本要求 0.52 及以上(用 TOML 配置)。
package tunnel

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"mediahub/internal/config"
	"mediahub/internal/proc"
)

// Status 是给管理程序看的状态。
type Status struct {
	Enabled    bool     `json:"enabled"`
	Type       string   `json:"type"`
	State      string   `json:"state"` // disabled | missing | stopped | starting | connecting | running | backoff | error
	Message    string   `json:"message,omitempty"`
	Exe        string   `json:"exe,omitempty"`
	PID        int      `json:"pid,omitempty"`
	StartedAt  int64    `json:"startedAt,omitempty"`
	Restarts   int      `json:"restarts"`
	PublicAddr string   `json:"publicAddr,omitempty"`
	Probes     []Probe  `json:"probes"`
	Logs       []string `json:"logs,omitempty"`
}

// Probe 是一次通过外网地址对服务端自己的探测。
type Probe struct {
	At        int64  `json:"at"`
	OK        bool   `json:"ok"`
	ConnectMs int64  `json:"connectMs"` // TCP 连接耗时(经过穿透服务器的往返)
	TTFBMs    int64  `json:"ttfbMs"`    // 发出 HTTP 请求到收到响应头的耗时(含 TLS 握手)
	Err       string `json:"err,omitempty"`
}

type Manager struct {
	cfg       config.Tunnel
	localPort int
	tlsOn     bool
	dataDir   string
	log       *slog.Logger

	// 以下几项测试里可以调小
	backoffMin, backoffMax time.Duration
	probeEvery             time.Duration
	probeFails             int
	minUptimeForRestart    time.Duration

	mu        sync.Mutex
	state     string
	message   string
	cmd       *exec.Cmd
	cancel    context.CancelFunc
	startedAt time.Time
	restarts  int
	logs      []string
	probes    []Probe
	wantStop  bool
	failRun   int
}

// New 创建管理器。localPort 是 MediaHub 自己监听的端口(穿透要转发到它);tlsOn 表示它是 HTTPS。
func New(cfg config.Tunnel, localPort int, tlsOn bool, dataDir string, log *slog.Logger) *Manager {
	return &Manager{
		cfg: cfg, localPort: localPort, tlsOn: tlsOn, dataDir: dataDir, log: log,
		backoffMin: 2 * time.Second, backoffMax: 60 * time.Second, probeEvery: 30 * time.Second, probeFails: 3, minUptimeForRestart: 2 * time.Minute,
		state: "stopped",
	}
}

// ---------------------------------------------------------------- 配置文件 / 命令行

func tomlStr(s string) string {
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`, "\r", "").Replace(s) + `"`
}

// FrpTOML 生成 frpc 的 TOML 配置(frp 0.52+)。
func FrpTOML(t config.Tunnel, localPort int) string {
	var b strings.Builder
	w := func(f string, a ...any) { fmt.Fprintf(&b, f+"\n", a...) }
	w("# 由 MediaHub 根据 config.json 的 tunnel 配置自动生成,每次启动穿透时重写,请不要手工修改")
	w("serverAddr = %s", tomlStr(t.ServerAddr))
	w("serverPort = %d", t.ServerPort)
	if t.User != "" {
		w("user = %s", tomlStr(t.User))
	}
	if t.Token != "" {
		w("auth.method = %s", tomlStr("token"))
		w("auth.token = %s", tomlStr(t.Token))
	}
	w("loginFailExit = false") // 连不上服务器时一直重试,不退出
	w("transport.protocol = %s", tomlStr(orDefault(t.Protocol, "tcp")))
	w("transport.tcpMux = %t", t.TCPMux)
	w("transport.poolCount = %d", t.PoolCount) // 预先建好的工作连接:新请求不用等"服务端通知客户端再建连接"这一个来回,图片缩略图这种并发小请求更快
	w("transport.heartbeatInterval = %d", orInt(t.HeartbeatSec, 10))
	w("transport.heartbeatTimeout = %d", orInt(t.HeartbeatSec, 10)*3)
	w("transport.dialServerTimeout = 10")
	w("transport.dialServerKeepalive = 30")
	w("transport.tls.enable = %t", t.TLS)
	w("")
	w("[[proxies]]")
	w("name = %s", tomlStr(orDefault(t.ProxyName, "mediahub")))
	pt := orDefault(t.ProxyType, "tcp")
	w("type = %s", tomlStr(pt))
	w("localIP = %s", tomlStr("127.0.0.1"))
	w("localPort = %d", localPort)
	switch pt {
	case "tcp":
		if t.RemotePort > 0 {
			w("remotePort = %d", t.RemotePort)
		}
	case "http", "https":
		if t.CustomDomain != "" {
			w("customDomains = [%s]", tomlStr(t.CustomDomain))
		}
		if t.Subdomain != "" {
			w("subdomain = %s", tomlStr(t.Subdomain))
		}
	}
	w("transport.useEncryption = %t", t.Encryption)   // frp 隧道内再加一层加密(MediaHub 已经是 HTTPS,没必要;关掉更省 CPU)
	w("transport.useCompression = %t", t.Compression) // 压缩:视频 / 图片已经是压缩格式,开了只耗 CPU;JSON 接口已经 gzip
	if t.BandwidthLimit != "" {
		w("transport.bandwidthLimit = %s", tomlStr(t.BandwidthLimit))
		w("transport.bandwidthLimitMode = %s", tomlStr("client"))
	}
	return b.String()
}

func orDefault(s, d string) string {
	if strings.TrimSpace(s) == "" {
		return d
	}
	return s
}
func orInt(v, d int) int {
	if v <= 0 {
		return d
	}
	return v
}

// SplitArgs 把命令行参数字符串拆成参数(支持双引号包住含空格的参数)。
func SplitArgs(s string) []string {
	var out []string
	var cur strings.Builder
	inQ, has := false, false
	for _, r := range s {
		switch {
		case r == '"':
			inQ, has = !inQ, true
		case (r == ' ' || r == '\t') && !inQ:
			if has {
				out = append(out, cur.String())
				cur.Reset()
				has = false
			}
		default:
			cur.WriteRune(r)
			has = true
		}
	}
	if has {
		out = append(out, cur.String())
	}
	return out
}

// resolveExe 返回要运行的程序和参数。frp:binary 或 tools\frp\frpc.exe;custom:command。
func (m *Manager) resolveExe() (exe string, args []string, workDir string, err error) {
	switch m.cfg.Type {
	case "custom":
		if strings.TrimSpace(m.cfg.Command) == "" {
			return "", nil, "", errors.New("没有填写自定义命令")
		}
		exe = m.cfg.Command
		for _, a := range SplitArgs(m.cfg.Args) {
			args = append(args, strings.ReplaceAll(a, "{port}", strconv.Itoa(m.localPort)))
		}
		workDir = m.cfg.WorkDir
		if workDir == "" {
			workDir = filepath.Dir(exe)
		}
	default:
		exe = m.cfg.Binary
		if exe == "" {
			exe = frpcDefault()
		}
		if m.cfg.ServerAddr == "" {
			return "", nil, "", errors.New("没有填写穿透服务器地址")
		}
		dir := filepath.Join(m.dataDir, "tunnel")
		if e := os.MkdirAll(dir, 0o700); e != nil {
			return "", nil, "", e
		}
		cfgPath := filepath.Join(dir, "frpc.toml")
		if e := os.WriteFile(cfgPath, []byte(FrpTOML(m.cfg, m.localPort)), 0o600); e != nil {
			return "", nil, "", e
		}
		args = []string{"-c", cfgPath}
		workDir = dir
	}
	if _, e := os.Stat(exe); e != nil {
		if p, e2 := exec.LookPath(exe); e2 == nil {
			exe = p
		} else {
			return exe, nil, workDir, fmt.Errorf("找不到穿透客户端程序:%s", exe)
		}
	}
	return exe, args, workDir, nil
}

// frpcDefault 默认位置:程序所在目录往上找 tools\frp\frpc.exe(开发时)或 runtime\frp\frpc.exe / 同目录(便携版)。
func frpcDefault() string {
	exePath, _ := os.Executable()
	d := filepath.Dir(exePath)
	for i := 0; i < 5; i++ {
		for _, rel := range []string{filepath.Join("tools", "frp", "frpc.exe"), filepath.Join("frp", "frpc.exe"), "frpc.exe"} {
			p := filepath.Join(d, rel)
			if st, err := os.Stat(p); err == nil && !st.IsDir() {
				return p
			}
		}
		d = filepath.Dir(d)
	}
	return filepath.Join("tools", "frp", "frpc.exe")
}

// ---------------------------------------------------------------- 运行

func (m *Manager) setState(st, msg string) {
	m.mu.Lock()
	m.state, m.message = st, msg
	m.mu.Unlock()
}

func (m *Manager) addLog(line string) {
	line = strings.TrimRight(line, "\r\n")
	if line == "" {
		return
	}
	if t := m.cfg.Token; t != "" {
		line = strings.ReplaceAll(line, t, "***")
	}
	m.mu.Lock()
	m.logs = append(m.logs, time.Now().Format("15:04:05 ")+line)
	if len(m.logs) > 300 {
		m.logs = m.logs[len(m.logs)-300:]
	}
	m.mu.Unlock()
	switch {
	case strings.Contains(line, "login to server success") || strings.Contains(line, "start proxy success"):
		m.setState("running", "已连接到穿透服务器")
		m.mu.Lock()
		m.failRun = 0
		m.mu.Unlock()
	case strings.Contains(line, "login to the server failed") || strings.Contains(line, "login to server failed") || strings.Contains(line, "connect to server error"):
		m.setState("connecting", "连接穿透服务器失败,正在重试:"+truncate(line, 160))
	}
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}

// Run 启动监督循环(立即返回)。ctx 结束时停止穿透进程。
func (m *Manager) Run(ctx context.Context) {
	if !m.cfg.Enabled {
		m.setState("disabled", "没有启用内网穿透")
		return
	}
	go m.supervise(ctx)
	if m.cfg.HealthCheck && m.cfg.PublicAddr != "" {
		go m.healthLoop(ctx)
	}
}

func (m *Manager) supervise(ctx context.Context) {
	backoff := m.backoffMin
	for ctx.Err() == nil {
		m.mu.Lock()
		stopped := m.wantStop
		m.mu.Unlock()
		if stopped {
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Second):
			}
			continue
		}
		exe, args, wd, err := m.resolveExe()
		if err != nil {
			m.setState("missing", err.Error())
			select {
			case <-ctx.Done():
				return
			case <-time.After(15 * time.Second): // 用户可能刚把 frpc.exe 放进去
			}
			continue
		}
		started := time.Now()
		m.runOnce(ctx, exe, args, wd)
		if ctx.Err() != nil {
			return
		}
		m.mu.Lock()
		stopped = m.wantStop
		m.mu.Unlock()
		if stopped {
			continue
		}
		if time.Since(started) > time.Minute { // 跑了一分钟以上才掉线:不算连续失败,退避从头开始
			backoff = m.backoffMin
		}
		m.mu.Lock()
		m.restarts++
		m.mu.Unlock()
		m.setState("backoff", fmt.Sprintf("穿透进程已退出,%v 后重新启动", backoff))
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff *= 2; backoff > m.backoffMax {
			backoff = m.backoffMax
		}
	}
}

func (m *Manager) runOnce(ctx context.Context, exe string, args []string, wd string) {
	cctx, cancel := context.WithCancel(ctx)
	defer cancel()
	cmd := proc.Hide(exec.CommandContext(cctx, exe, args...))
	cmd.Dir = wd
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()
	m.setState("starting", "正在启动穿透进程")
	if err := cmd.Start(); err != nil {
		m.setState("error", "启动失败:"+err.Error())
		m.log.Warn("内网穿透启动失败", "exe", exe, "err", err)
		return
	}
	m.mu.Lock()
	m.cmd, m.cancel, m.startedAt = cmd, cancel, time.Now()
	m.mu.Unlock()
	m.log.Info("内网穿透已启动", "exe", exe, "pid", cmd.Process.Pid, "type", m.cfg.Type)
	m.writePID(cmd.Process.Pid)
	var wg sync.WaitGroup
	scan := func(r io.Reader) {
		defer wg.Done()
		sc := bufio.NewScanner(r)
		sc.Buffer(make([]byte, 64<<10), 1<<20)
		for sc.Scan() {
			m.addLog(sc.Text())
		}
	}
	wg.Add(2)
	go scan(stdout)
	go scan(stderr)
	// 自定义命令不一定会输出 frp 那样的"登录成功"日志:活过 5 秒就当作已启动
	go func() {
		select {
		case <-cctx.Done():
		case <-time.After(5 * time.Second):
			m.mu.Lock()
			if m.cmd == cmd && m.state == "starting" {
				m.state, m.message = "running", "穿透进程运行中"
			}
			m.mu.Unlock()
		}
	}()
	wg.Wait()
	err := cmd.Wait()
	m.mu.Lock()
	if m.cmd == cmd {
		m.cmd, m.cancel = nil, nil
	}
	m.mu.Unlock()
	m.removePID()
	if cctx.Err() == nil {
		m.log.Warn("内网穿透进程退出", "err", err)
	}
}

func (m *Manager) pidFile() string { return filepath.Join(m.dataDir, "tunnel", "tunnel.pid") }
func (m *Manager) writePID(pid int) {
	_ = os.MkdirAll(filepath.Dir(m.pidFile()), 0o700)
	_ = os.WriteFile(m.pidFile(), []byte(strconv.Itoa(pid)), 0o600)
}
func (m *Manager) removePID() { _ = os.Remove(m.pidFile()) }

// KillStale 服务上次异常退出时,穿透进程可能还活着(占着远程端口):启动前先按 pid 文件清掉。
func (m *Manager) KillStale() {
	b, err := os.ReadFile(m.pidFile())
	if err != nil {
		return
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	if pid <= 0 {
		return
	}
	out, _ := proc.Hide(exec.Command("tasklist", "/FI", "PID eq "+strconv.Itoa(pid), "/FO", "CSV", "/NH")).Output()
	name := strings.ToLower(string(out))
	exeBase := strings.ToLower(strings.TrimSuffix(filepath.Base(m.cfg.Command), ".exe"))
	if strings.Contains(name, "frpc") || (m.cfg.Type == "custom" && exeBase != "" && strings.Contains(name, exeBase)) {
		_ = proc.Hide(exec.Command("taskkill", "/F", "/T", "/PID", strconv.Itoa(pid))).Run()
		m.log.Info("已清理上次残留的穿透进程", "pid", pid)
	}
	m.removePID()
}

// Stop 停止穿透进程并且不再自动拉起(直到 Start)。
func (m *Manager) Stop() {
	m.mu.Lock()
	m.wantStop = true
	cancel := m.cancel
	m.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	m.setState("stopped", "已手动停止")
}

// Start 取消"手动停止"。
func (m *Manager) Start() {
	m.mu.Lock()
	m.wantStop = false
	m.mu.Unlock()
}

// Restart 重启穿透进程(监督循环会立即重新拉起)。
func (m *Manager) Restart() {
	m.mu.Lock()
	m.wantStop = false
	cancel := m.cancel
	m.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// ---------------------------------------------------------------- 健康探测

// ProbeOnce 通过外网地址(穿透服务器那边的入口)探测服务端自己:TCP 连接耗时 + HTTP 首字节耗时。
func (m *Manager) ProbeOnce(ctx context.Context) Probe {
	p := Probe{At: time.Now().Unix()}
	addr := strings.TrimSpace(m.cfg.PublicAddr)
	if addr == "" {
		p.Err = "没有填写外网地址"
		return p
	}
	addr = strings.TrimPrefix(strings.TrimPrefix(addr, "https://"), "http://")
	addr = strings.TrimSuffix(addr, "/")
	if _, _, err := net.SplitHostPort(addr); err != nil {
		if m.tlsOn {
			addr += ":443"
		} else {
			addr += ":80"
		}
	}
	pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	t0 := time.Now()
	c, err := (&net.Dialer{}).DialContext(pctx, "tcp", addr)
	if err != nil {
		p.Err = "连接失败:" + err.Error()
		return p
	}
	p.ConnectMs = time.Since(t0).Milliseconds()
	_ = c.Close()
	scheme := "http"
	if m.tlsOn {
		scheme = "https"
	}
	cl := &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, DisableKeepAlives: true}} // 自签名证书:这里只是探测链路,不校验
	req, _ := http.NewRequestWithContext(pctx, "GET", scheme+"://"+addr+"/api/v1/server/info", nil)
	t1 := time.Now()
	resp, err := cl.Do(req)
	if err != nil {
		p.Err = "请求失败:" + err.Error()
		return p
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	p.TTFBMs = time.Since(t1).Milliseconds()
	p.OK = resp.StatusCode == 200
	if !p.OK {
		p.Err = "HTTP " + strconv.Itoa(resp.StatusCode)
	}
	return p
}

func (m *Manager) record(p Probe) {
	m.mu.Lock()
	m.probes = append(m.probes, p)
	if len(m.probes) > 20 {
		m.probes = m.probes[len(m.probes)-20:]
	}
	m.mu.Unlock()
}

// Probe 立即探测一次并记录。
func (m *Manager) Probe(ctx context.Context) Probe {
	p := m.ProbeOnce(ctx)
	m.record(p)
	return p
}

func (m *Manager) healthLoop(ctx context.Context) {
	fails := 0
	t := time.NewTicker(m.probeEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		m.mu.Lock()
		st, stopped, up := m.state, m.wantStop, time.Since(m.startedAt)
		m.mu.Unlock()
		if stopped || st == "missing" || st == "disabled" {
			continue
		}
		p := m.Probe(ctx)
		if p.OK {
			fails = 0
			continue
		}
		fails++
		m.log.Warn("内网穿透探测失败", "n", fails, "err", p.Err)
		if fails >= m.probeFails && m.cfg.RestartOnFail && up > m.minUptimeForRestart {
			m.log.Warn("内网穿透连续探测失败,重启穿透进程")
			m.addLog("连续 " + strconv.Itoa(fails) + " 次探测失败,自动重启穿透进程")
			fails = 0
			m.Restart()
		}
	}
}

// Status 当前状态(logs = 是否带上最近日志)。
func (m *Manager) Status(withLogs bool) Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := Status{
		Enabled: m.cfg.Enabled, Type: m.cfg.Type, State: m.state, Message: m.message, Restarts: m.restarts,
		PublicAddr: m.cfg.PublicAddr, Probes: append([]Probe(nil), m.probes...),
	}
	if m.cmd != nil && m.cmd.Process != nil {
		st.PID = m.cmd.Process.Pid
		st.StartedAt = m.startedAt.Unix()
	}
	if exe, _, _, err := (&Manager{cfg: m.cfg, dataDir: m.dataDir, localPort: m.localPort}).peekExe(); err == nil {
		st.Exe = exe
	}
	if withLogs {
		st.Logs = append([]string(nil), m.logs...)
	}
	return st
}

// peekExe 只看会运行哪个程序(不写配置文件、不报找不到)。
func (m *Manager) peekExe() (string, []string, string, error) {
	if m.cfg.Type == "custom" {
		return m.cfg.Command, nil, "", nil
	}
	if m.cfg.Binary != "" {
		return m.cfg.Binary, nil, "", nil
	}
	return frpcDefault(), nil, "", nil
}
