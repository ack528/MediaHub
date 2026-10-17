package api

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"mediahub/internal/governor"
)

// 客户端连接追踪(管理程序「客户端连接」页):每个登录令牌 = 一台设备。
//
// 记录:设备名、用户、IP 和线路(内网直连 / 经内网穿透 / 公网直连)、App 版本、首次和最近活动时间、请求数 / 出错数、发送 / 接收字节、
// 当前速度、正在做什么(播放哪个视频 + 转码 / 原画、测速、加载图片、浏览)。
// 同时把"有客户端在用 / 正在传输"告诉 [governor.Governor],让它调度后台任务(扫描、压缩、重复检测……)给传输和播放让路。
//
// 线路判断:内网穿透(frpc)是从本机连进来的,服务端看到的来源是回环地址;私有地址 = 局域网直连;其它 = 公网(例如 IPv6 直连)。
// 管理程序(带管理密钥的请求)不算客户端。

const (
	clientOnlineWindow = 90 * time.Second // 最近这么久有请求就算"在线"
	clientKeep         = 24 * time.Hour   // 内存里保留多久(之后只在令牌表里显示为"离线")
)

type activity struct {
	Kind    string // hls | file | image | speedtest | api
	Name    string // 媒体文件名
	Detail  string // 转码 1.5M H.265 / 原画播放 / 下载 / 测速(下载)……
	MediaID int64
	At      time.Time
}

type clientRec struct {
	ID            string
	UserID        int64
	User          string
	Device        string
	UA            string
	IP            string
	Route         string // lan | tunnel | wan
	First         time.Time
	Last          time.Time
	now           activity
	rate          float64 // 字节 / 秒,每秒采样平滑
	lastOut       int64
	Requests      int64
	Errors        int64
	BytesOut      int64
	BytesIn       int64
	ActiveStreams int32
	ActiveReqs    int32
}

type mediaMeta struct {
	name string
	typ  int
}

type Clients struct {
	s    *Server
	mu   sync.Mutex
	m    map[string]*clientRec
	meta map[int64]mediaMeta
}

func newClients(s *Server) *Clients {
	return &Clients{s: s, m: map[string]*clientRec{}, meta: map[int64]mediaMeta{}}
}

// StartClients 启动速度采样和过期清理(main 里调用一次)。
func (s *Server) StartClients(ctx context.Context) {
	if s.Clients == nil {
		s.Clients = newClients(s)
	}
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				s.Clients.sample()
			}
		}
	}()
}

// sample 每秒一次:算每个客户端的当前速度(指数平滑),清掉太久没动静的。
func (c *Clients) sample() {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for id, r := range c.m {
		out := atomic.LoadInt64(&r.BytesOut)
		r.rate = r.rate*0.5 + float64(out-r.lastOut)*0.5
		r.lastOut = out
		if now.Sub(r.Last) > clientKeep && atomic.LoadInt32(&r.ActiveReqs) == 0 {
			delete(c.m, id)
		}
	}
}

// reqInfo 一次请求的追踪信息(放在请求的 context 里:最外层创建,requireAuth 验证令牌后填上客户端)。
type reqInfo struct {
	rec   *clientRec
	heavy bool
	in    int64
	gov   *governor.Governor
}

type riKey struct{}

func riFrom(ctx context.Context) *reqInfo {
	ri, _ := ctx.Value(riKey{}).(*reqInfo)
	return ri
}

func routeOf(remote string) string {
	h, _, err := net.SplitHostPort(remote)
	if err != nil {
		h = remote
	}
	ip := net.ParseIP(h)
	switch {
	case ip == nil:
		return "wan"
	case ip.IsLoopback():
		return "tunnel"
	case ip.IsPrivate() || ip.IsLinkLocalUnicast():
		return "lan"
	}
	return "wan"
}

func (c *Clients) mediaMetaOf(id int64) mediaMeta {
	c.mu.Lock()
	m, ok := c.meta[id]
	c.mu.Unlock()
	if ok {
		return m
	}
	var name string
	var typ int
	if err := c.s.DB.QueryRow(`SELECT name, type FROM media WHERE id=?`, id).Scan(&name, &typ); err != nil {
		return mediaMeta{typ: -1}
	}
	m = mediaMeta{name, typ}
	c.mu.Lock()
	if len(c.meta) > 4096 {
		c.meta = map[int64]mediaMeta{}
	}
	c.meta[id] = m
	c.mu.Unlock()
	return m
}

func fmtMbps(bps int) string {
	if bps >= 1_000_000 {
		f := float64(bps) / 1e6
		if f == float64(int(f)) {
			return strconv.Itoa(int(f)) + "M"
		}
		return strconv.FormatFloat(f, 'f', 1, 64) + "M"
	}
	return strconv.Itoa(bps/1000) + "K"
}

// classify 判断这次请求是什么,是不是"重传输"(视频文件 / HLS 转码段 / 测速):重传输会让服务端进入 Streaming 调度级别。
func (c *Clients) classify(r *http.Request) (act activity, heavy bool) {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/") // api v1 media {id} file
	act.Kind = "api"
	if len(parts) < 3 {
		return
	}
	switch parts[2] {
	case "speedtest":
		if len(parts) >= 4 && parts[3] != "ping" {
			act.Kind, act.Detail, heavy = "speedtest", "测速(下载)", true
			if parts[3] == "up" {
				act.Detail = "测速(上传)"
			}
		}
	case "media":
		if len(parts) < 5 {
			return
		}
		id, _ := strconv.ParseInt(parts[3], 10, 64)
		act.MediaID = id
		switch parts[4] {
		case "hls":
			act.Kind = "hls"
			if len(parts) > 5 && strings.HasPrefix(parts[5], "seg") {
				heavy = true
			}
			q := r.URL.Query()
			d := "转码"
			if b, _ := strconv.Atoi(q.Get("maxBitrate")); b > 0 {
				d += " " + fmtMbps(b)
			}
			if cd := q.Get("codec"); cd == "h265" || cd == "hevc" {
				d += " H.265"
			} else if cd == "h264" {
				d += " H.264"
			}
			act.Detail = d
			act.Name = c.mediaMetaOf(id).name
		case "file":
			m := c.mediaMetaOf(id)
			act.Name = m.name
			if m.typ == 1 || m.typ == 3 { // 视频 / 音频:大文件流
				act.Kind, heavy = "file", true
				if r.Header.Get("Range") != "" {
					act.Detail = "原画播放"
				} else {
					act.Detail = "下载 / 保存"
				}
			} else {
				act.Kind, act.Detail = "image", "加载图片"
			}
		case "render", "poster":
			act.Kind, act.Detail = "image", "加载图片"
		}
	}
	return
}

// begin 验证令牌之后调用:登记 / 更新这个客户端,并按请求类型通知 governor。
func (c *Clients) begin(ri *reqInfo, r *http.Request, uid int64, id, device string) {
	now := time.Now()
	ip := r.RemoteAddr
	if h, _, err := net.SplitHostPort(ip); err == nil {
		ip = h
	}
	ua := r.Header.Get("User-Agent")
	if len(ua) > 160 {
		ua = ua[:160]
	}
	c.mu.Lock()
	rec := c.m[id]
	if rec == nil {
		rec = &clientRec{ID: id, UserID: uid, Device: device, First: now}
		c.m[id] = rec
	}
	rec.Last, rec.IP, rec.Route = now, ip, routeOf(r.RemoteAddr)
	if ua != "" {
		rec.UA = ua
	}
	needUser := rec.User == ""
	c.mu.Unlock()
	if needUser {
		name := c.s.Auth.UserName(uid)
		c.mu.Lock()
		rec.User = name
		c.mu.Unlock()
	}
	act, heavy := c.classify(r)
	atomic.AddInt64(&rec.Requests, 1)
	atomic.AddInt32(&rec.ActiveReqs, 1)
	if heavy {
		atomic.AddInt32(&rec.ActiveStreams, 1)
		c.s.Gov.StreamStart()
	} else {
		c.s.Gov.ClientActivity()
	}
	if act.Kind != "api" {
		act.At = now
		c.mu.Lock()
		rec.now = act
		c.mu.Unlock()
	}
	ri.rec, ri.heavy = rec, heavy
}

func (c *Clients) end(ri *reqInfo, status int) {
	rec := ri.rec
	if rec == nil {
		return
	}
	atomic.AddInt32(&rec.ActiveReqs, -1)
	if ri.heavy {
		atomic.AddInt32(&rec.ActiveStreams, -1)
		c.s.Gov.StreamEnd()
	}
	atomic.AddInt64(&rec.BytesIn, ri.in)
	if status >= 500 {
		atomic.AddInt64(&rec.Errors, 1)
	}
	c.mu.Lock()
	rec.Last = time.Now()
	c.mu.Unlock()
}

// ---------------------------------------------------------------- 中间件

type countWriter struct {
	http.ResponseWriter
	ri   *reqInfo
	code int
}

func (w *countWriter) WriteHeader(c int) { w.code = c; w.ResponseWriter.WriteHeader(c) }
func (w *countWriter) Write(b []byte) (int, error) {
	n, err := w.ResponseWriter.Write(b)
	if rec := w.ri.rec; rec != nil {
		atomic.AddInt64(&rec.BytesOut, int64(n))
		if w.ri.heavy {
			w.ri.gov.Touch() // 重传输期间数据在流动:刷新"最近重传输"时间
		}
	}
	return n, err
}
func (w *countWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}
func (w *countWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

type countReader struct {
	io.ReadCloser
	n *int64
}

func (r countReader) Read(p []byte) (int, error) {
	n, err := r.ReadCloser.Read(p)
	atomic.AddInt64(r.n, int64(n))
	return n, err
}

// track 最外层之一:给每个请求建 reqInfo,统计响应 / 请求字节,结束时登记。
func (s *Server) track(next http.Handler) http.Handler {
	if s.Clients == nil {
		s.Clients = newClients(s)
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ri := &reqInfo{gov: s.Gov}
		if r.Body != nil && (r.Method == "POST" || r.Method == "PUT") {
			r.Body = countReader{r.Body, &ri.in}
		}
		cw := &countWriter{ResponseWriter: w, ri: ri, code: 200}
		next.ServeHTTP(cw, r.WithContext(context.WithValue(r.Context(), riKey{}, ri)))
		s.Clients.end(ri, cw.code)
	})
}

// ---------------------------------------------------------------- 接口

type clientView struct {
	ID            string `json:"id"`
	User          string `json:"user"`
	Device        string `json:"device"`
	UA            string `json:"ua,omitempty"`
	IP            string `json:"ip,omitempty"`
	Route         string `json:"route,omitempty"`
	State         string `json:"state"` // playing | transfer | browsing | idle | offline
	Online        bool   `json:"online"`
	First         int64  `json:"firstSeen,omitempty"`
	Last          int64  `json:"lastSeen"`
	Requests      int64  `json:"requests"`
	Errors        int64  `json:"errors"`
	BytesOut      int64  `json:"bytesOut"`
	BytesIn       int64  `json:"bytesIn"`
	RateBps       int64  `json:"rateBps"` // 当前发送速度(比特 / 秒)
	ActiveStreams int    `json:"activeStreams"`
	ActiveReqs    int    `json:"activeReqs"`
	NowKind       string `json:"nowKind,omitempty"`
	NowName       string `json:"nowName,omitempty"`
	NowDetail     string `json:"nowDetail,omitempty"`
	NowMediaID    int64  `json:"nowMediaId,omitempty"`
}

type bgTask struct {
	Name  string `json:"name"`
	State string `json:"state"` // running | throttled | paused | idle
	Note  string `json:"note"`
}

func (c *Clients) snapshot() (list []clientView, online int, rate int64, bytes int64) {
	now := time.Now()
	c.mu.Lock()
	seen := map[string]bool{}
	for id, r := range c.m {
		v := clientView{
			ID: id, User: r.User, Device: r.Device, UA: r.UA, IP: r.IP, Route: r.Route, First: r.First.Unix(), Last: r.Last.Unix(),
			Requests: atomic.LoadInt64(&r.Requests), Errors: atomic.LoadInt64(&r.Errors), BytesOut: atomic.LoadInt64(&r.BytesOut), BytesIn: atomic.LoadInt64(&r.BytesIn),
			RateBps: int64(r.rate * 8), ActiveStreams: int(atomic.LoadInt32(&r.ActiveStreams)), ActiveReqs: int(atomic.LoadInt32(&r.ActiveReqs)),
		}
		v.Online = v.ActiveReqs > 0 || now.Sub(r.Last) < clientOnlineWindow
		recent := now.Sub(r.now.At) < 10*time.Second || v.ActiveStreams > 0
		if recent && r.now.Kind != "" {
			v.NowKind, v.NowName, v.NowDetail, v.NowMediaID = r.now.Kind, r.now.Name, r.now.Detail, r.now.MediaID
		}
		switch {
		case !v.Online:
			v.State = "offline"
		case v.ActiveStreams > 0 && (r.now.Kind == "hls" || r.now.Kind == "file"):
			v.State = "playing"
		case v.ActiveStreams > 0:
			v.State = "transfer"
		case now.Sub(r.Last) < 15*time.Second:
			v.State = "browsing"
		default:
			v.State = "idle"
		}
		if v.Online {
			online++
		}
		rate += v.RateBps
		bytes += v.BytesOut
		seen[id] = true
		list = append(list, v)
	}
	c.mu.Unlock()
	// 登录过但本次服务运行后还没有请求的设备:从令牌表补上,显示为离线
	for _, t := range c.s.Auth.RecentTokens(30) {
		if seen[t.ID] {
			continue
		}
		list = append(list, clientView{ID: t.ID, User: t.User, Device: t.Device, State: "offline", Last: t.LastSeen})
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].Online != list[j].Online {
			return list[i].Online
		}
		return list[i].Last > list[j].Last
	})
	return
}

// GET /api/v1/admin/clients —— 客户端列表、调度级别、后台任务状态。
func (s *Server) adminClients(w http.ResponseWriter, r *http.Request) {
	if s.Clients == nil {
		s.Clients = newClients(s)
	}
	list, online, rate, bytes := s.Clients.snapshot()
	if list == nil {
		list = []clientView{}
	}
	writeJSON(w, 200, map[string]any{
		"clients": list, "online": online, "rateBps": rate, "bytesOut": bytes,
		"governor": s.Gov.Snapshot(), "background": s.backgroundTasks(),
	})
}

// backgroundTasks 各后台任务现在的状态(给页面显示"为什么慢了 / 为什么暂停了")。
func (s *Server) backgroundTasks() []bgTask {
	lvl := s.Gov.Level()
	busy := lvl != governor.Idle
	var out []bgTask
	// 压缩队列
	c := &s.opt.cp
	c.mu.Lock()
	cur, paused := c.cur, c.paused
	c.mu.Unlock()
	var queued int
	_ = s.DB.QueryRow(`SELECT COUNT(*) FROM opt_job WHERE state='queued'`).Scan(&queued)
	switch {
	case cur != 0 && s.Gov.PauseCompress():
		out = append(out, bgTask{"视频压缩", "paused", "有客户端连接,压缩进程已暂停;没有客户端后自动继续"})
	case cur != 0:
		out = append(out, bgTask{"视频压缩", "running", "正在压缩"})
	case queued > 0 && paused:
		out = append(out, bgTask{"视频压缩", "paused", "队列已手动暂停"})
	case queued > 0 && s.Gov.PauseCompress():
		out = append(out, bgTask{"视频压缩", "paused", "有客户端连接,等没有客户端时再开始"})
	case queued > 0:
		out = append(out, bgTask{"视频压缩", "running", "即将开始"})
	default:
		out = append(out, bgTask{"视频压缩", "idle", "队列是空的"})
	}
	// 重复检测
	if st := s.opt.dd.status(); st.State == "running" {
		note := "正在检测:" + st.Phase
		state := "running"
		if busy {
			state, note = "throttled", "有客户端连接,已降速让路:"+st.Phase
		}
		out = append(out, bgTask{"重复文件检测", state, note})
	} else {
		out = append(out, bgTask{"重复文件检测", "idle", "没有在运行"})
	}
	// 媒体库扫描 / 补全
	scanning := 0
	if s.Idx != nil {
		for _, p := range s.Idx.Progress() {
			if p.State == "scanning" || p.State == "enriching" {
				scanning++
			}
		}
	}
	switch {
	case scanning > 0 && busy:
		out = append(out, bgTask{"媒体库扫描 / 补全元数据", "throttled", "有客户端连接,已降速让路"})
	case scanning > 0:
		out = append(out, bgTask{"媒体库扫描 / 补全元数据", "running", "正在扫描 / 补全"})
	default:
		out = append(out, bgTask{"媒体库扫描 / 补全元数据", "idle", "没有在运行"})
	}
	return out
}

// POST /api/v1/admin/clients/kick {id} —— 撤销这台设备的登录(它下次请求会收到 401,回到登录页)。
func (s *Server) adminClientKick(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&in) != nil || in.ID == "" {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	n := s.Auth.RevokeByID(in.ID)
	if n == 0 {
		writeErr(w, 404, "notfound", "没有这台设备的登录记录(可能已经退出)")
		return
	}
	if s.Clients != nil {
		s.Clients.mu.Lock()
		delete(s.Clients.m, in.ID)
		s.Clients.mu.Unlock()
	}
	s.Log.Info("管理程序撤销了一台设备的登录", "id", in.ID)
	writeJSON(w, 200, map[string]any{"revoked": n})
}
