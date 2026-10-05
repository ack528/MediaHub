package api

import (
	"crypto/subtle"
	"net/http"
	"strconv"
	"strings"
	"time"

	"mediahub/internal/logx"
)

// adminOnly 只放行"回环地址 + 正确的 X-Admin-Key"(桌面管理程序),普通用户令牌不能看服务端日志。
func (s *Server) adminOnly(h http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		k := r.Header.Get("X-Admin-Key")
		if k == "" || s.AdminKey == "" || !isLoopback(r.RemoteAddr) || subtle.ConstantTimeCompare([]byte(k), []byte(s.AdminKey)) != 1 {
			writeErr(w, 403, "admin.only", "只有本机的管理程序可以查看服务端日志")
			return
		}
		h(w, r)
	})
}

// adminLogs GET /api/v1/admin/logs?lines=300&level=warn —— 内存里最近的日志(纯文本)。
func (s *Server) adminLogs(w http.ResponseWriter, r *http.Request) {
	lm := logx.Default()
	if lm == nil {
		writeErr(w, 503, "logs.unavailable", "日志系统未启用")
		return
	}
	n, _ := strconv.Atoi(r.URL.Query().Get("lines"))
	if n <= 0 || n > 3000 {
		n = 500
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte(strings.Join(lm.Tail(n, r.URL.Query().Get("level")), "\n")))
}

// adminLogBundle GET /api/v1/admin/logs/bundle —— 日志 + 崩溃报告 + 运行环境打成 zip 下载。
func (s *Server) adminLogBundle(w http.ResponseWriter, r *http.Request) {
	lm := logx.Default()
	if lm == nil {
		writeErr(w, 503, "logs.unavailable", "日志系统未启用")
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="mediahub-logs-`+time.Now().Format("20060102-150405")+`.zip"`)
	if err := lm.Bundle(w); err != nil {
		s.Log.Warn("打包日志失败", "err", err)
	}
}

// adminLogLevel POST /api/v1/admin/logs/level?level=debug —— 运行中临时调整日志级别(重启后以配置为准)。
func (s *Server) adminLogLevel(w http.ResponseWriter, r *http.Request) {
	if lm := logx.Default(); lm != nil {
		lm.SetLevel(r.URL.Query().Get("level"))
		s.Log.Info("日志级别已调整", "level", r.URL.Query().Get("level"))
	}
	w.WriteHeader(204)
}
