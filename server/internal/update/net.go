package update

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// 备用 DNS(DNS over HTTPS):系统 DNS 解析 github.com / 镜像域名失败时依次尝试。
// 这些 DoH 服务器的域名本身也要解析,所以用固定的 IP 连接(bootstrap),TLS 仍按域名校验证书。
// path:各家 JSON 接口的路径不同(alidns / google / adguard 是 /resolve,doh.pub / cloudflare / dns.sb 是 /dns-query)。
var dohServers = []struct{ host, ip, path string }{
	{"dns.alidns.com", "223.5.5.5", "/resolve"},
	{"doh.pub", "1.12.12.12", "/dns-query"},
	{"cloudflare-dns.com", "1.1.1.1", "/dns-query"},
	{"dns.google", "8.8.8.8", "/resolve"},
	{"dns.adguard-dns.com", "94.140.14.14", "/resolve"},
	{"doh.dns.sb", "185.222.222.222", "/dns-query"},
}

// DoHNames 返回内置 DoH 服务器的域名(给界面显示)。
func DoHNames() []string {
	out := make([]string, len(dohServers))
	for i, d := range dohServers {
		out[i] = d.host
	}
	return out
}

var dohClient = &http.Client{
	Timeout: 5 * time.Second,
	Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, _ := net.SplitHostPort(addr)
			for _, d := range dohServers {
				if host == d.host {
					addr = net.JoinHostPort(d.ip, port)
				}
			}
			return (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, network, addr)
		},
	},
}

type dnsCache struct {
	mu sync.Mutex
	m  map[string]dnsEntry
}

type dnsEntry struct {
	ips []string
	at  time.Time
}

var dnsMem = &dnsCache{m: map[string]dnsEntry{}}

// resolve 解析域名:缓存(10 分钟)→ DoH → 系统 DNS;都失败就用上次成功的结果。
func resolve(ctx context.Context, host string) ([]string, error) {
	if net.ParseIP(host) != nil {
		return []string{host}, nil
	}
	if ips, ok := dnsMem.fresh(host); ok {
		return ips, nil
	}
	// DoH 优先:系统 DNS 在部分网络里会返回被污染的地址而不报错,所以不能只在它失败时才换
	for _, d := range dohServers {
		ips, err := dohLookup(ctx, d.host, host)
		if err == nil && len(ips) > 0 {
			dnsMem.put(host, ips)
			return ips, nil
		}
	}
	if ips, err := net.DefaultResolver.LookupHost(ctx, host); err == nil && len(ips) > 0 {
		dnsMem.put(host, ips)
		return ips, nil
	}
	if old, ok := dnsMem.stale(host); ok {
		return old, nil
	}
	return nil, fmt.Errorf("解析 %s 失败", host)
}

func (c *dnsCache) put(host string, ips []string) {
	c.mu.Lock()
	c.m[host] = dnsEntry{ips, time.Now()}
	c.mu.Unlock()
}

func (c *dnsCache) fresh(host string) ([]string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[host]
	if !ok || time.Since(e.at) > 10*time.Minute {
		return nil, false
	}
	return e.ips, true
}

// stale 返回任何时候缓存过的结果(解析全部失败时的最后手段)。
func (c *dnsCache) stale(host string) ([]string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[host]
	return e.ips, ok
}

// dohLookup 用 Google 风格的 JSON DoH(alidns / doh.pub / cloudflare / google 都支持 ?name=&type=A)。
func dohLookup(ctx context.Context, server, host string) ([]string, error) {
	path := "/resolve"
	for _, d := range dohServers {
		if d.host == server {
			path = d.path
		}
	}
	req, _ := http.NewRequestWithContext(ctx, "GET", "https://"+server+path+"?name="+host+"&type=A", nil)
	req.Header.Set("Accept", "application/dns-json")
	resp, err := dohClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var r struct {
		Status int `json:"Status"`
		Answer []struct {
			Type int    `json:"type"`
			Data string `json:"data"`
		} `json:"Answer"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&r); err != nil {
		return nil, err
	}
	var out []string
	for _, a := range r.Answer {
		if a.Type == 1 && net.ParseIP(a.Data) != nil {
			out = append(out, a.Data)
		}
	}
	if r.Status != 0 || len(out) == 0 {
		return nil, fmt.Errorf("DoH %s 没有 %s 的记录", server, host)
	}
	return out, nil
}

// newHTTPClient 返回一个使用上面 resolve 的客户端:每个 IP 依次尝试,连不上换下一个。
func newHTTPClient(timeout time.Duration) *http.Client {
	d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy:               http.ProxyFromEnvironment,
			ForceAttemptHTTP2:   true,
			MaxIdleConnsPerHost: 4,
			IdleConnTimeout:     90 * time.Second,
			TLSHandshakeTimeout: 10 * time.Second,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				host, port, err := net.SplitHostPort(addr)
				if err != nil {
					return nil, err
				}
				ips, err := resolve(ctx, host)
				if err != nil {
					return nil, err
				}
				ips = orderByLatency(ctx, ips, port)
				var last error
				for _, ip := range ips {
					c, err := d.DialContext(ctx, network, net.JoinHostPort(ip, port))
					if err == nil {
						return c, nil
					}
					last = err
				}
				return nil, last
			},
		},
	}
}

// candidates 返回一个 GitHub 下载地址的全部候选:先直连 github.com,再依次是各个镜像(镜像前缀 + 原地址)。
// 镜像列表里有用户自己设置的一项时排在最前面。
func candidates(url string, mirrors []string) []string {
	out := []string{url}
	seen := map[string]bool{url: true}
	for _, m := range mirrors {
		m = strings.TrimSpace(m)
		if m == "" {
			continue
		}
		if !strings.HasSuffix(m, "/") {
			m += "/"
		}
		if strings.Contains(m, "github.com") { // 误填成 github.com 本身:不是镜像,跳过
			continue
		}
		u := m + url
		if !seen[u] {
			seen[u] = true
			out = append(out, u)
		}
	}
	return out
}

// race 同时对每个候选地址发一个 1 字节的 Range 请求,谁先返回 200 / 206 就用谁(通常是最快、最通的那个)。
// 全部失败时返回 candidates 的第一个,由调用方按顺序回退。
func race(ctx context.Context, client *http.Client, urls []string, ua string) string {
	if len(urls) == 1 {
		return urls[0]
	}
	rctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	type res struct{ url string }
	ch := make(chan res, len(urls))
	for _, u := range urls {
		go func(u string) {
			req, _ := http.NewRequestWithContext(rctx, "GET", u, nil)
			req.Header.Set("Range", "bytes=0-0")
			req.Header.Set("User-Agent", ua)
			resp, err := client.Do(req)
			if err != nil {
				ch <- res{""}
				return
			}
			resp.Body.Close()
			if resp.StatusCode == 200 || resp.StatusCode == 206 {
				ch <- res{u}
				return
			}
			ch <- res{""}
		}(u)
	}
	for range urls {
		if r := <-ch; r.url != "" {
			cancel() // 赢家已经有了,其余的直接取消
			return r.url
		}
	}
	return urls[0]
}

// orderByLatency 把同一个域名的多个 IP 按 TCP 连接耗时从快到慢排序(Watt Toolkit 的思路:同一个域名解析出多个地址时选最近的那个)。
// 只对 443 端口、地址不止一个时探测;并发探测,每个最多 1.5 秒,结果缓存 10 分钟。
func orderByLatency(ctx context.Context, ips []string, port string) []string {
	if len(ips) < 2 || port != "443" {
		return ips
	}
	key := strings.Join(ips, ",")
	if v, ok := latCache.fresh(key); ok {
		return v
	}
	type r struct {
		ip string
		d  time.Duration
	}
	ch := make(chan r, len(ips))
	for _, ip := range ips {
		go func(ip string) {
			t0 := time.Now()
			c, err := (&net.Dialer{Timeout: 1500 * time.Millisecond}).DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
			if err != nil {
				ch <- r{ip, time.Hour}
				return
			}
			c.Close()
			ch <- r{ip, time.Since(t0)}
		}(ip)
	}
	rs := make([]r, 0, len(ips))
	for range ips {
		rs = append(rs, <-ch)
	}
	sort.Slice(rs, func(i, j int) bool { return rs[i].d < rs[j].d })
	out := make([]string, len(rs))
	for i, x := range rs {
		out[i] = x.ip
	}
	latCache.put(key, out)
	return out
}

var latCache = &dnsCache{m: map[string]dnsEntry{}}

// MirrorStatus 是一个下载源(直连或镜像)的探测结果。
type MirrorStatus struct {
	Name string `json:"name"` // 直连 = "直连 github.com";镜像 = 镜像域名
	URL  string `json:"url"`  // 镜像前缀;直连为空
	OK   bool   `json:"ok"`
	Ms   int64  `json:"ms"` // 首字节耗时;不通时为 0
	Code int    `json:"code,omitempty"`
	Err  string `json:"err,omitempty"`
}

// ProbeSources 同时探测直连和所有镜像:对 probeURL 发 1 字节的 Range 请求,并检查返回的是不是文件内容(而不是镜像站的拦截页)。
func ProbeSources(ctx context.Context, client *http.Client, probeURL string, mirrors []string, ua string) []MirrorStatus {
	srcs := []struct{ name, prefix string }{{"直连 github.com", ""}}
	for _, m := range mirrors {
		m = strings.TrimSpace(m)
		if m == "" || strings.Contains(m, "github.com") {
			continue
		}
		if !strings.HasSuffix(m, "/") {
			m += "/"
		}
		host := strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(m, "https://"), "http://"), "/")
		srcs = append(srcs, struct{ name, prefix string }{host, m})
	}
	out := make([]MirrorStatus, len(srcs))
	var wg sync.WaitGroup
	for i, sc := range srcs {
		wg.Add(1)
		go func(i int, name, prefix string) {
			defer wg.Done()
			out[i] = MirrorStatus{Name: name, URL: prefix}
			rctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			defer cancel()
			req, _ := http.NewRequestWithContext(rctx, "GET", prefix+probeURL, nil)
			req.Header.Set("Range", "bytes=0-63")
			req.Header.Set("User-Agent", ua)
			t0 := time.Now()
			resp, err := client.Do(req)
			if err != nil {
				out[i].Err = err.Error()
				return
			}
			defer resp.Body.Close()
			b, _ := io.ReadAll(io.LimitReader(resp.Body, 64))
			out[i].Ms = time.Since(t0).Milliseconds()
			out[i].Code = resp.StatusCode
			// 返回的应该是 SHA256SUMS.txt 的开头:64 个十六进制字符。镜像站的拦截页 / 错误页是 HTML,不算通
			head := strings.TrimSpace(string(b))
			out[i].OK = (resp.StatusCode == 200 || resp.StatusCode == 206) && len(head) >= 32 && isHex(head[:32])
			if !out[i].OK && out[i].Err == "" {
				out[i].Err = "返回的不是文件内容"
				out[i].Ms = 0
			}
		}(i, sc.name, sc.prefix)
	}
	wg.Wait()
	return out
}

func isHex(s string) bool {
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}
