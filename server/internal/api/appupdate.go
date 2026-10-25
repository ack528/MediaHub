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
//	GET /api/v1/app/update[?check=1]   最新的手机端版本、是否已缓存好、SHA-256、大小;立即返回缓存，check=1 时后台刷新
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
	status := s.Update.AppStatus()
	if status.CheckedAt.IsZero() || time.Since(status.CheckedAt) >= maxAge {
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
			defer cancel()
			s.Update.AppCheck(ctx, maxAge)
		}()
	}
	writeJSON(w, 200, status)
}

func (s *Server) appUpdateApk(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeErr(w, 404, "app.none", "服务器没有缓存手机端安装包")
		return
	}
	p, version := s.Update.AppFile()
	if want := r.URL.Query().Get("version"); want != "" && want != version {
		writeErr(w, 409, "app.changed", "缓存版本已改变，请重新检查更新")
		return
	}
	if want := r.URL.Query().Get("sha256"); want != "" && want != s.Update.AppStatus().Sha256 {
		writeErr(w, 409, "app.changed", "缓存校验值已改变")
		return
	}
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
