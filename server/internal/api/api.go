// Package api 实现 /api/v1 接口(见 docs/04-API与数据模型.md)。
package api

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"runtime/debug"
	"sync"
	"strconv"
	"strings"
	"time"

	"mediahub/internal/auth"
	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/governor"
	"mediahub/internal/index"
	"mediahub/internal/jellyfin"
	"mediahub/internal/logx"
	"mediahub/internal/tunnel"
	"mediahub/internal/update"
	"mediahub/internal/winfs"
)

const APIVersion = 1

type Server struct {
	rnd randomState // 随机浏览用的各根目录 id 范围(见 random.go)
	opt optState    // 存储优化:重复文件检测、视频压缩(见 optimize_common.go)
	// Tunnel:内网穿透管理器(可为空,见 internal/tunnel)
	Tunnel *tunnel.Manager
	// Gov:按有无客户端调度后台任务(可为空 = 不让路);Clients:客户端连接追踪
	Gov     *governor.Governor
	Clients *Clients
	DB      *sql.DB
	Cfg     *config.Config
	Auth    *auth.Service
	Idx     *index.Indexer
	Cache   *cache.Manager
	Poster  *Poster
	Render  *Renderer
	JF      *jellyfin.Client // 转码引擎(可选)
	HLS     *HLS             // 内置 ffmpeg 转码(没有 Jellyfin 时使用)
	// TLSFingerprint 非空表示服务通过 HTTPS 提供(自签名证书的 SHA-256 指纹,手机首次连接时核对)
	TLSFingerprint string
	Log            *slog.Logger
	Version        string
	// Rescan 由 main 提供:异步重新扫描(rootID=0 表示全部)
	Rescan func(rootID int64)
	// LoginDelay 登录失败后的延迟(测试里置 0)
	LoginDelay time.Duration
	// LoginLock 任何设备登录失败后,所有设备在这段时间内都不能再验证密码(0 = 不锁定,见 loginlock.go)
	LoginLock time.Duration
	loginG    loginGuard
	compatOnce sync.Once // 兼容版本(只转需要转的部分),见 compat.go
	compatJ    *compatJobs
	posterWarned sync.Map // 封面生成失败已经警告过的媒体 id
	// AdminKey:桌面管理程序用的本机密钥。仅当请求来自回环地址且带正确的 X-Admin-Key 时放行。
	AdminKey string
	// StartedAt:服务启动时间,用于状态页显示运行时长
	StartedAt time.Time
	// Update:自动更新(可为空)
	Update *update.Updater
}

type ctxKey int

const userKey ctxKey = 1

func userID(r *http.Request) int64 {
	v, _ := r.Context().Value(userKey).(int64)
	return v
}

// Handler 构建路由。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	// 无需认证
	mux.HandleFunc("GET /api/v1/server/info", s.serverInfo)
	mux.HandleFunc("POST /api/v1/auth/login", s.login)
	// 需要认证
	authd := func(h http.HandlerFunc) http.Handler { return s.requireAuth(h) }
	mux.Handle("POST /api/v1/auth/logout", authd(s.logout))
	mux.Handle("GET /api/v1/dialogs", authd(s.listDialogs))
	mux.Handle("GET /api/v1/dialogs/{id}", authd(s.getDialog))
	mux.Handle("GET /api/v1/dialogs/{id}/topics", authd(s.listTopics))
	mux.Handle("PUT /api/v1/dialogs/{id}/state", authd(s.putDialogState))
	mux.Handle("GET /api/v1/dialogs/{id}/history", authd(s.history))
	mux.Handle("GET /api/v1/dialogs/{id}/buckets", authd(s.buckets))
	mux.Handle("GET /api/v1/dialogs/{id}/view", authd(s.getView))
	mux.Handle("PUT /api/v1/dialogs/{id}/view", authd(s.putView))
	mux.Handle("GET /api/v1/media/{id}/playback", authd(s.getPlayback))
	mux.Handle("PUT /api/v1/media/{id}/playback", authd(s.putPlayback))
	mux.Handle("DELETE /api/v1/state/views", authd(s.clearViews))
	mux.Handle("DELETE /api/v1/state/playback", authd(s.clearPlayback))
	mux.Handle("GET /api/v1/search", authd(s.search))
	mux.Handle("GET /api/v1/random", authd(s.random))
	mux.Handle("POST /api/v1/random/reset", authd(s.randomReset))
	mux.Handle("GET /api/v1/server/addresses", authd(s.serverAddresses))
	mux.Handle("GET /api/v1/admin/clients", s.adminOnly(s.adminClients))
	mux.Handle("POST /api/v1/admin/clients/kick", s.adminOnly(s.adminClientKick))
	mux.Handle("GET /api/v1/admin/tunnel", s.adminOnly(s.adminTunnelStatus))
	mux.Handle("POST /api/v1/admin/tunnel/{action}", s.adminOnly(s.adminTunnelAction))
	mux.Handle("GET /api/v1/speedtest/ping", authd(s.speedPing))
	mux.Handle("GET /api/v1/speedtest/down", authd(s.speedDown))
	mux.Handle("POST /api/v1/speedtest/up", authd(s.speedUp))
	mux.Handle("GET /api/v1/state/player", authd(s.getPlayerPref))
	mux.Handle("PUT /api/v1/state/player", authd(s.putPlayerPref))
	mux.Handle("POST /api/v1/media/{id}/hls/prepare", authd(s.mediaHLSPrepare))
	mux.Handle("GET /api/v1/media/{id}/compat", authd(s.mediaCompat))
	mux.Handle("POST /api/v1/media/{id}/compat/prepare", authd(s.mediaCompatPrepare))
	mux.Handle("GET /api/v1/media/{id}/file", authd(s.mediaFile))
	mux.Handle("HEAD /api/v1/media/{id}/file", authd(s.mediaFile))
	mux.Handle("GET /api/v1/media/{id}/poster", authd(s.mediaPoster))
	mux.Handle("GET /api/v1/media/{id}/render", authd(s.mediaRender))
	mux.Handle("GET /api/v1/media/{id}/hls/{rest...}", authd(s.mediaHLS))
	mux.Handle("DELETE /api/v1/media/{id}/hls", authd(s.mediaHLSStop))
	mux.Handle("GET /api/v1/admin/status", authd(s.adminStatus))
	mux.Handle("GET /api/v1/admin/backup", s.adminOnly(s.adminBackupList))
	mux.Handle("POST /api/v1/admin/backup/run", s.adminOnly(s.adminBackupRun))
	mux.Handle("POST /api/v1/admin/backup/restore", s.adminOnly(s.adminBackupRestore))
	mux.Handle("POST /api/v1/admin/pprof/cpu", s.adminOnly(s.adminPprofCPU))
	mux.Handle("POST /api/v1/admin/login-lock/clear", s.adminOnly(func(w http.ResponseWriter, r *http.Request) { s.loginG.clear(); w.WriteHeader(204) }))
	mux.Handle("POST /api/v1/admin/rescan", authd(s.adminRescan))
	mux.Handle("GET /api/v1/admin/update", s.adminOnly(s.adminUpdateStatus))
	mux.Handle("POST /api/v1/admin/update/check", s.adminOnly(s.adminUpdateCheck))
	mux.Handle("POST /api/v1/admin/update/apply", s.adminOnly(s.adminUpdateApply))
	mux.Handle("GET /api/v1/admin/update/mirrors", s.adminOnly(s.adminUpdateMirrors))
	// 存储优化(仅本机管理程序)
	mux.Handle("POST /api/v1/admin/dedupe/scan", s.adminOnly(s.adminDedupeScan))
	mux.Handle("GET /api/v1/admin/dedupe/status", s.adminOnly(s.adminDedupeStatus))
	mux.Handle("POST /api/v1/admin/dedupe/cancel", s.adminOnly(s.adminDedupeCancel))
	mux.Handle("GET /api/v1/admin/dedupe/groups", s.adminOnly(s.adminDedupeGroups))
	mux.Handle("POST /api/v1/admin/dedupe/trash", s.adminOnly(s.adminDedupeTrash))
	mux.Handle("GET /api/v1/admin/trash", s.adminOnly(s.adminTrashList))
	mux.Handle("POST /api/v1/admin/trash/restore", s.adminOnly(s.adminTrashRestore))
	mux.Handle("POST /api/v1/admin/trash/purge", s.adminOnly(s.adminTrashPurge))
	mux.Handle("GET /api/v1/admin/compress/caps", s.adminOnly(s.adminCompressCaps))
	mux.Handle("POST /api/v1/admin/compress/enqueue", s.adminOnly(s.adminCompressEnqueue))
	mux.Handle("GET /api/v1/admin/compress/jobs", s.adminOnly(s.adminCompressJobs))
	mux.Handle("POST /api/v1/admin/compress/cancel", s.adminOnly(s.adminCompressCancel))
	mux.Handle("POST /api/v1/admin/compress/remove", s.adminOnly(s.adminCompressRemove))
	mux.Handle("POST /api/v1/admin/compress/apply", s.adminOnly(s.adminCompressApply))
	mux.Handle("POST /api/v1/admin/compress/clear", s.adminOnly(s.adminCompressClear))
	mux.Handle("POST /api/v1/admin/compress/pause", s.adminOnly(s.adminCompressPause(true)))
	mux.Handle("POST /api/v1/admin/compress/resume", s.adminOnly(s.adminCompressPause(false)))
	mux.Handle("GET /api/v1/admin/logs", s.adminOnly(s.adminLogs))
	mux.Handle("GET /api/v1/admin/logs/bundle", s.adminOnly(s.adminLogBundle))
	mux.Handle("POST /api/v1/admin/logs/level", s.adminOnly(s.adminLogLevel))
	return s.logging(s.track(gzipJSON(mux)))
}

func (s *Server) logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t0 := time.Now()
		sw := &statusWriter{ResponseWriter: w, code: 200}
		// 请求里的 panic 不能拖垮服务:记日志、写崩溃报告,给客户端一个 500
		defer func() {
			if rec := recover(); rec != nil {
				s.Log.Error("请求处理中 panic", "m", r.Method, "path", r.URL.Path, "panic", fmt.Sprint(rec))
				if lm := logx.Default(); lm != nil {
					lm.WriteCrash("HTTP "+r.Method+" "+r.URL.Path, rec, debug.Stack())
				}
				if !sw.wrote {
					writeErr(sw, 500, "internal", "服务器内部错误(已记录到日志)")
				}
			}
		}()
		next.ServeHTTP(sw, r)
		ms := time.Since(t0).Milliseconds()
		attrs := []any{"m", r.Method, "path", r.URL.Path, "code", sw.code, "ms", ms, "ip", clientIP(r)}
		p := r.URL.Path
		stream := strings.Contains(p, "/file") || strings.Contains(p, "/hls/") || strings.Contains(p, "/speedtest/") // 播放 / 下载 / 测速本来就耗时,不算慢
		// 本来就可能慢的:封面首次生成、格式转换、随机序列(扫描时重算)、管理接口;门槛放宽,不然日志里全是"请求较慢"
		slowLimit := int64(5000)
		if strings.HasSuffix(p, "/poster") || strings.HasSuffix(p, "/render") || strings.HasPrefix(p, "/api/v1/random") {
			slowLimit = 30000
		} else if strings.Contains(p, "/admin/") {
			slowLimit = 15000
		}
		switch {
		case sw.code >= 500:
			s.Log.Error("HTTP 5xx", attrs...)
		case sw.code == 404 && (strings.HasSuffix(p, "/poster") || strings.HasSuffix(p, "/playback") || strings.HasSuffix(p, "/view")),
			sw.code == 416 && strings.HasSuffix(p, "/file"),
			sw.code == 401 && strings.HasSuffix(p, "/dialogs"):
			s.Log.Debug("http", attrs...) // 封面 / 播放进度 / 浏览位置"还没有记录"、令牌过期、拖到文件末尾之外这类是常态,不刷屏(以前一天几百条)
		case sw.code >= 400:
			s.Log.Warn("HTTP 4xx", append(attrs, "q", r.URL.RawQuery)...)
		case !stream && ms > slowLimit:
			s.Log.Warn("请求较慢", attrs...)
		default:
			s.Log.Debug("http", attrs...)
		}
	})
}

type statusWriter struct {
	http.ResponseWriter
	code  int
	wrote bool
}

func (w *statusWriter) WriteHeader(c int) { w.code, w.wrote = c, true; w.ResponseWriter.WriteHeader(c) }
func (w *statusWriter) Write(b []byte) (int, error) {
	w.wrote = true
	return w.ResponseWriter.Write(b)
}

func clientIP(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

// Flush/Unwrap 让反向代理与 ServeContent 能访问底层能力。
func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (s *Server) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if k := r.Header.Get("X-Admin-Key"); k != "" && s.AdminKey != "" && isLoopback(r.RemoteAddr) &&
			subtle.ConstantTimeCompare([]byte(k), []byte(s.AdminKey)) == 1 {
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userKey, int64(0))))
			return
		}
		h := r.Header.Get("Authorization")
		tok, ok := strings.CutPrefix(h, "Bearer ")
		if !ok {
			writeErr(w, 401, "auth.required", "需要登录")
			return
		}
		uid, tokID, device, ok := s.Auth.VerifyInfo(strings.TrimSpace(tok))
		if !ok {
			writeErr(w, 401, "auth.expired", "令牌无效或已过期")
			return
		}
		if ri := riFrom(r.Context()); ri != nil && s.Clients != nil { // 登记这个客户端,并通知 governor
			s.Clients.begin(ri, r, uid, tokID, device)
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userKey, uid)))
	})
}

// ---- JSON 工具 ----

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, c, msg string) {
	writeJSON(w, code, map[string]any{"code": c, "message": msg})
}

func idParam(r *http.Request, name string) (int64, bool) {
	n, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	return n, err == nil && n > 0
}

func sid(n int64) string { return strconv.FormatInt(n, 10) }

func rfc(ms int64) string { return time.UnixMilli(ms).UTC().Format(time.RFC3339) }

// ---- 基础接口 ----

func (s *Server) serverInfo(w http.ResponseWriter, r *http.Request) {
	name := "MediaHub"
	writeJSON(w, 200, map[string]any{"name": name, "version": s.Version, "apiVersion": APIVersion, "transcode": s.JF != nil || s.HLS != nil,
		"tls":  map[string]any{"enabled": s.TLSFingerprint != "", "fingerprint": s.TLSFingerprint},
		"caps": s.capabilities()})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Username   string `json:"username"`
		Password   string `json:"password"`
		DeviceName string `json:"deviceName"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in); err != nil {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	// 刚刚有设备登录失败:锁定期间不验证任何密码(不做 pbkdf2,直接 429)
	if wait := s.loginG.wait(); wait > 0 {
		s.Log.Warn("登录被拒绝:全局锁定中", "wait", wait.Round(time.Second), "ip", clientIP(r), "user", in.Username)
		lockedResponse(w, wait)
		return
	}
	tok, exp, uid, err := s.Auth.Login(in.Username, in.Password, in.DeviceName)
	if err != nil {
		s.loginG.fail(s.LoginLock, clientIP(r), in.Username, in.DeviceName)
		s.Log.Warn("登录失败", "ip", clientIP(r), "user", in.Username, "device", in.DeviceName, "lockSec", int(s.LoginLock.Seconds()))
		time.Sleep(s.LoginDelay)
		writeErr(w, 401, "auth.invalid", "用户名或密码错误")
		return
	}
	writeJSON(w, 200, map[string]any{"token": tok, "expiresAt": exp.UTC().Format(time.RFC3339),
		"user": map[string]any{"id": sid(uid), "name": in.Username}})
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	tok, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	s.Auth.Logout(strings.TrimSpace(tok))
	w.WriteHeader(204)
}

func (s *Server) adminStatus(w http.ResponseWriter, r *http.Request) {
	type cacheInfo struct {
		RootID string  `json:"rootId"`
		Dir    string  `json:"dir"`
		Mode   string  `json:"mode"`
		Reason string  `json:"reason,omitempty"`
		Used   int64   `json:"usedBytes"`
		Quota  int64   `json:"quotaBytes"`
		FreeGB float64 `json:"driveFreeGB"`
	}
	var cs []cacheInfo
	rows, _ := s.DB.Query(`SELECT id, path FROM roots`)
	paths := map[int64]string{}
	for rows != nil && rows.Next() {
		var id int64
		var p string
		if rows.Scan(&id, &p) == nil {
			paths[id] = p
		}
	}
	if rows != nil {
		rows.Close()
	}
	for _, rc := range s.Cache.All() {
		ci := cacheInfo{RootID: sid(rc.RootID), Dir: rc.Dir, Mode: rc.Mode, Reason: rc.Reason, Used: s.Cache.Usage(rc.RootID), Quota: rc.Quota}
		if f, err := winfs.FreeBytes(paths[rc.RootID]); err == nil {
			ci.FreeGB = float64(f) / (1 << 30)
		}
		cs = append(cs, ci)
	}
	var dataFree float64
	if f, err := winfs.FreeBytes(s.Cfg.DataDir); err == nil {
		dataFree = float64(f) / (1 << 30)
	}
	var nMedia, nDialogs int64
	_ = s.DB.QueryRow(`SELECT count(*) FROM media WHERE root_id IN (SELECT id FROM roots WHERE enabled=1)`).Scan(&nMedia)
	_ = s.DB.QueryRow(`SELECT count(*) FROM dialogs WHERE root_id IN (SELECT id FROM roots WHERE enabled=1)`).Scan(&nDialogs)
	warn := []string{}
	if dataFree > 0 && dataFree < 10 {
		warn = append(warn, "数据目录所在盘可用空间不足 10GB")
	}
	writeJSON(w, 200, map[string]any{
		"version": s.Version, "listen": s.Cfg.Listen, "startedAt": s.StartedAt.UTC().Format(time.RFC3339),
		"uptimeSec": int64(time.Since(s.StartedAt).Seconds()), "media": nMedia, "dialogs": nDialogs,
		"index": s.Idx.Progress(), "cache": cs, "dataDir": s.Cfg.DataDir, "dataDriveFreeGB": dataFree, "warnings": warn,
	})
}

func (s *Server) adminRescan(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RootID string `json:"rootId"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&in)
	id, _ := strconv.ParseInt(in.RootID, 10, 64)
	if s.Rescan != nil {
		s.Rescan(id)
	}
	w.WriteHeader(202)
}

func isLoopback(remote string) bool {
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// capabilities 服务端能力(手机端据此决定显示哪些选项):转码能输出的编码、QSV 是否可用、转码码率上限、是否支持测速 / 记住偏好 / 预热 / 重复检测与压缩。
func (s *Server) capabilities() map[string]any {
	codecs := []string{}
	qsv264, qsv265 := false, false
	probing := false
	if s.HLS != nil {
		if s.HLS.Probed() {
			codecs = s.HLS.Codecs()
			qsv264, qsv265 = s.HLS.QSV()
		} else {
			codecs, probing = []string{"h264"}, true
		}
	}
	max := s.Cfg.Video.MaxTranscodeBitrate
	if max <= 0 {
		max = 6_000_000
	}
	return map[string]any{
		"codecs": codecs, "qsvH264": qsv264, "qsvH265": qsv265, "maxTranscodeBitrate": max,
		"probing": probing, "speedtest": true, "playerPrefs": true, "hlsPrepare": true, "compat": s.Cfg.Tools.FFmpeg != "", "gzip": true, "optimize": true, "thumbWebp": s.Render != nil && s.Cfg.Images.FallbackConvert,
	}
}
