package tunnel

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"sort"
	"strings"
	"time"
)

// 实时延迟:有人在看的时候(管理程序的穿透页、手机的设置页)每 2 秒量一次,没人看就不量(不白白占用穿透服务器的流量)。
//
//	ChainMs  整条链路的往返:通过外网地址向服务端自己发一个很小的 HTTP 请求,在已经建好的(保活)连接上量"发出请求 → 收到响应头";
//	         这是手机经穿透访问服务器时每个请求至少要付出的延迟。第一次要先建连(TCP + TLS),建连耗时不算进去。
//	ServerMs 到穿透服务器(frps)的 TCP 连接耗时,衡量"这台电脑到穿透服务器"这一段;传输协议是 kcp / quic(UDP)时 frps 的控制端口
//	         不一定同时开着 TCP,量不到就留空(-1)。
//
// 数据是循环保存的最近 90 次(3 分钟)。

// LatSample 是一次实时延迟采样(-1 = 这次失败 / 没有数据)。
type LatSample struct {
	At       int64 `json:"at"` // unix 毫秒
	ChainMs  int64 `json:"chainMs"`
	ServerMs int64 `json:"serverMs"`
}

// LatStats 是一组采样的统计。
type LatStats struct {
	Last   int64   `json:"last"` // 最近一次;-1 = 失败 / 没有
	Avg    float64 `json:"avg"`
	Min    int64   `json:"min"`
	Max    int64   `json:"max"`
	Jitter float64 `json:"jitter"` // 相邻两次差值的平均(抖动)
	P95    int64   `json:"p95"`
	LossPc float64 `json:"lossPct"` // 失败百分比
	N      int     `json:"n"`
}

// Latency 是给界面看的实时延迟。
type Latency struct {
	Watching bool        `json:"watching"`
	Chain    LatStats    `json:"chain"`
	Server   LatStats    `json:"server"`
	Samples  []LatSample `json:"samples"`
	Protocol string      `json:"protocol"` // 客户端到穿透服务器用的传输协议(tcp / kcp / quic / websocket / wss)
	UDP      bool        `json:"udp"`      // 这个协议是不是基于 UDP
}

const (
	latKeep        = 90
	latEvery       = 2 * time.Second
	watchFor       = 40 * time.Second // 最后一次有人看之后继续量多久
	latPingTimeout = 5 * time.Second
)

// Watch 登记"有人正在看实时延迟":之后 40 秒内持续采样。每次界面轮询都调用一次。
func (m *Manager) Watch() { m.watchUntil.Store(time.Now().Add(watchFor).UnixNano()) }

func (m *Manager) watching() bool { return time.Now().UnixNano() < m.watchUntil.Load() }

// UDPProtocol 报告一个 frp 传输协议是不是基于 UDP(kcp / quic)。
func UDPProtocol(p string) bool {
	p = strings.ToLower(strings.TrimSpace(p))
	return p == "kcp" || p == "quic"
}

func (m *Manager) latencyLoop(ctx context.Context) {
	var cl *http.Client
	var tr *http.Transport
	defer func() {
		if tr != nil {
			tr.CloseIdleConnections()
		}
	}()
	newClient := func() {
		if tr != nil {
			tr.CloseIdleConnections()
		}
		tr = &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, // 自签名证书:这里只量延迟,不校验
			MaxIdleConns:    1, IdleConnTimeout: 90 * time.Second, ForceAttemptHTTP2: false,
		}
		cl = &http.Client{Timeout: latPingTimeout, Transport: tr}
	}
	newClient()
	t := time.NewTicker(latEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		if !m.watching() {
			continue
		}
		s := LatSample{At: time.Now().UnixMilli(), ChainMs: -1, ServerMs: -1}
		if ms, ok := m.pingServer(ctx); ok {
			s.ServerMs = ms
		}
		if ms, ok := m.pingChain(ctx, cl); ok {
			s.ChainMs = ms
		} else {
			newClient() // 连接可能已经断了:下次重新建
		}
		m.mu.Lock()
		m.lat = append(m.lat, s)
		if len(m.lat) > latKeep {
			m.lat = m.lat[len(m.lat)-latKeep:]
		}
		m.mu.Unlock()
	}
}

// pingServer 量到穿透服务器 frps 的 TCP 连接耗时。
func (m *Manager) pingServer(ctx context.Context) (int64, bool) {
	if m.cfg.Type == "custom" || strings.TrimSpace(m.cfg.ServerAddr) == "" || m.cfg.ServerPort <= 0 {
		return 0, false
	}
	pctx, cancel := context.WithTimeout(ctx, latPingTimeout)
	defer cancel()
	t0 := time.Now()
	c, err := (&net.Dialer{}).DialContext(pctx, "tcp", net.JoinHostPort(m.cfg.ServerAddr, itoa(m.cfg.ServerPort)))
	if err != nil {
		return 0, false
	}
	ms := time.Since(t0).Milliseconds()
	_ = c.Close()
	return ms, true
}

// pingChain 通过外网地址在保活连接上发一个很小的请求,量往返。
func (m *Manager) pingChain(ctx context.Context, cl *http.Client) (int64, bool) {
	addr := m.publicHostPort()
	if addr == "" {
		return 0, false
	}
	scheme := "http"
	if m.tlsOn {
		scheme = "https"
	}
	pctx, cancel := context.WithTimeout(ctx, latPingTimeout)
	defer cancel()
	do := func() (int64, bool) {
		req, _ := http.NewRequestWithContext(pctx, "GET", scheme+"://"+addr+"/api/v1/server/info", nil)
		t0 := time.Now()
		resp, err := cl.Do(req)
		if err != nil {
			return 0, false
		}
		defer resp.Body.Close()
		ms := time.Since(t0).Milliseconds()
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		return ms, resp.StatusCode == 200
	}
	// 第一次(还没有保活连接)要先建连,耗时包含了 TCP + TLS 握手,不能代表往返:先发一次预热,再量
	if !m.warm.Load() {
		if _, ok := do(); !ok {
			return 0, false
		}
		m.warm.Store(true)
	}
	ms, ok := do()
	if !ok {
		m.warm.Store(false)
	}
	return ms, ok
}

// publicHostPort 外网地址的 host:port(没填返回空)。
func (m *Manager) publicHostPort() string {
	addr := strings.TrimSpace(m.cfg.PublicAddr)
	if addr == "" {
		return ""
	}
	addr = strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(addr, "https://"), "http://"), "/")
	if _, _, err := net.SplitHostPort(addr); err != nil {
		if m.tlsOn {
			addr += ":443"
		} else {
			addr += ":80"
		}
	}
	return addr
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [12]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

// Latency 当前的实时延迟(带最近的采样和统计)。
func (m *Manager) Latency() Latency {
	m.mu.Lock()
	samples := append([]LatSample(nil), m.lat...)
	m.mu.Unlock()
	l := Latency{Watching: m.watching(), Samples: samples, Protocol: orDefault(m.cfg.Protocol, "tcp")}
	l.UDP = UDPProtocol(l.Protocol)
	chain := make([]int64, len(samples))
	server := make([]int64, len(samples))
	for i, s := range samples {
		chain[i], server[i] = s.ChainMs, s.ServerMs
	}
	l.Chain, l.Server = stats(chain), stats(server)
	return l
}

func stats(v []int64) LatStats {
	st := LatStats{Last: -1, N: len(v)}
	if len(v) == 0 {
		return st
	}
	st.Last = v[len(v)-1]
	var ok []int64
	for _, x := range v {
		if x >= 0 {
			ok = append(ok, x)
		}
	}
	if len(ok) == 0 {
		st.LossPc = 100
		return st
	}
	var sum int64
	st.Min = ok[0]
	for _, x := range ok {
		sum += x
		if x < st.Min {
			st.Min = x
		}
		if x > st.Max {
			st.Max = x
		}
	}
	st.Avg = float64(sum) / float64(len(ok))
	var d float64
	for i := 1; i < len(ok); i++ {
		diff := ok[i] - ok[i-1]
		if diff < 0 {
			diff = -diff
		}
		d += float64(diff)
	}
	if len(ok) > 1 {
		st.Jitter = d / float64(len(ok)-1)
	}
	sorted := append([]int64(nil), ok...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	st.P95 = sorted[(len(sorted)*95)/100%len(sorted)]
	st.LossPc = float64(len(v)-len(ok)) * 100 / float64(len(v))
	return st
}

// Protocol 客户端到穿透服务器用的传输协议。
func (m *Manager) Protocol() string { return orDefault(m.cfg.Protocol, "tcp") }

// Type 穿透类型:frp / custom。
func (m *Manager) Type() string { return orDefault(m.cfg.Type, "frp") }
