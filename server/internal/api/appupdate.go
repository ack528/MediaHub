package api

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// 手机端「检查更新」先问自己的服务器(update/app.go):
//
//	GET /api/v1/app/update[?check=1]   最新的手机端版本、是否已缓存好、SHA-256、大小;check=1 时上次检查超过 1 分钟就现在查一次(最多等 12 秒)
//	GET /api/v1/app/update.apk         下载缓存好的安装包(Range 断点续传;没缓存好返回 404)
func (s *Server) appUpdate(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeJSON(w, 200, map[string]any{"state": "disabled"})
		return
	}
	maxAge := 10 * time.Minute
	if r.URL.Query().Get("check") == "1" {
		maxAge = time.Minute
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	writeJSON(w, 200, s.Update.AppCheck(ctx, maxAge))
}

func (s *Server) appUpdateApk(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeErr(w, 404, "app.none", "服务器没有缓存手机端安装包")
		return
	}
	p, _ := s.Update.AppFile()
	if p == "" {
		writeErr(w, 404, "app.none", "服务器还没有下载好手机端安装包")
		return
	}
	f, err := os.Open(p)
	if err != nil {
		writeErr(w, 404, "app.none", "服务器上的安装包不见了")
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		writeErr(w, 500, "app.io", err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="`+filepath.Base(p)+`"`)
	http.ServeContent(w, r, filepath.Base(p), fi.ModTime(), f)
}
