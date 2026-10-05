// Package api 实现 /api/v1 接口(见 docs/04-API与数据模型.md)。
package api

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"mediahub/internal/auth"
	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/index"
	"mediahub/internal/winfs"
)

const APIVersion = 1

type Server struct {
	DB      *sql.DB
	Cfg     *config.Config
	Auth    *auth.Service
	Idx     *index.Indexer
	Cache   *cache.Manager
	Poster  *Poster
	Log     *slog.Logger
	Version string
	// Rescan 由 main 提供:异步重新扫描(rootID=0 表示全部)
	Rescan func(rootID int64)
	// LoginDelay 登录失败后的延迟(测试里置 0)
	LoginDelay time.Duration
	// AdminKey:桌面管理程序用的本机密钥。仅当请求来自回环地址且带正确的 X-Admin-Key 时放行。
	AdminKey string
	// StartedAt:服务启动时间,用于状态页显示运行时长
	StartedAt time.Time
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
	mux.Handle("GET /api/v1/search", authd(s.search))
	mux.Handle("GET /api/v1/media/{id}/file", authd(s.mediaFile))
	mux.Handle("HEAD /api/v1/media/{id}/file", authd(s.mediaFile))
	mux.Handle("GET /api/v1/media/{id}/poster", authd(s.mediaPoster))
	mux.Handle("GET /api/v1/admin/status", authd(s.adminStatus))
	mux.Handle("POST /api/v1/admin/rescan", authd(s.adminRescan))
	return s.logging(mux)
}

func (s *Server) logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t0 := time.Now()
		sw := &statusWriter{ResponseWriter: w, code: 200}
		next.ServeHTTP(sw, r)
		s.Log.Debug("http", "m", r.Method, "path", r.URL.Path, "q", r.URL.RawQuery, "code", sw.code, "ms", time.Since(t0).Milliseconds())
	})
}

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (w *statusWriter) WriteHeader(c int) { w.code = c; w.ResponseWriter.WriteHeader(c) }

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
		uid, ok := s.Auth.Verify(strings.TrimSpace(tok))
		if !ok {
			writeErr(w, 401, "auth.expired", "令牌无效或已过期")
			return
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
	writeJSON(w, 200, map[string]any{"name": name, "version": s.Version, "apiVersion": APIVersion})
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
	tok, exp, uid, err := s.Auth.Login(in.Username, in.Password, in.DeviceName)
	if err != nil {
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
	_ = s.DB.QueryRow(`SELECT count(*) FROM media`).Scan(&nMedia)
	_ = s.DB.QueryRow(`SELECT count(*) FROM dialogs`).Scan(&nDialogs)
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
