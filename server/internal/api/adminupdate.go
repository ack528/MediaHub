package api

import (
	"context"
	"net/http"
	"time"
)

// 管理程序的"软件更新"接口(仅本机 + 管理密钥):
//
//	GET  /api/v1/admin/update          当前版本、最新版本、状态、下载进度
//	POST /api/v1/admin/update/check    立即检查一次(只检查,不安装)
//	POST /api/v1/admin/update/apply    立即检查并安装(发现新版本就下载、替换、重启服务)

func (s *Server) adminUpdateStatus(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeJSON(w, 200, map[string]any{"current": s.Version, "state": "disabled", "message": "自动更新没有启用"})
		return
	}
	writeJSON(w, 200, s.Update.Status())
}

func (s *Server) adminUpdateCheck(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeErr(w, 400, "update.disabled", "自动更新没有启用")
		return
	}
	go s.Update.Check(context.Background(), false)
	w.WriteHeader(202)
}

func (s *Server) adminUpdateApply(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeErr(w, 400, "update.disabled", "自动更新没有启用")
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 40*time.Minute)
		defer cancel()
		s.Update.Check(ctx, true)
	}()
	w.WriteHeader(202)
}
