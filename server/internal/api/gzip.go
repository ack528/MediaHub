package api

import (
	"compress/gzip"
	"net/http"
	"strings"
	"sync"
)

// 响应压缩:JSON 接口(群组列表、历史、搜索……)和 HLS 播放列表是文本,gzip 后只有原来的 1/5 ~ 1/10,
// 在手机经云中继 / 移动网络访问时,首屏列表的加载明显更快。图片 / 视频 / 测速等已经压缩过或不能压缩的内容不处理。
// OkHttp 会自动带 Accept-Encoding: gzip 并透明解压,手机端不需要改任何东西。
var gzPool = sync.Pool{New: func() any { return gzip.NewWriter(nil) }}

type gzResponse struct {
	http.ResponseWriter
	zw      *gzip.Writer
	decided bool
}

func compressible(ct string) bool {
	return strings.HasPrefix(ct, "application/json") || strings.HasPrefix(ct, "application/vnd.apple.mpegurl")
}

func (g *gzResponse) decide() {
	if g.decided {
		return
	}
	g.decided = true
	h := g.Header()
	if h.Get("Content-Encoding") != "" || h.Get("Content-Range") != "" || !compressible(h.Get("Content-Type")) {
		return
	}
	h.Del("Content-Length")
	h.Set("Content-Encoding", "gzip")
	h.Add("Vary", "Accept-Encoding")
	zw := gzPool.Get().(*gzip.Writer)
	zw.Reset(g.ResponseWriter)
	g.zw = zw
}

func (g *gzResponse) WriteHeader(code int) {
	g.decide()
	g.ResponseWriter.WriteHeader(code)
}

func (g *gzResponse) Write(b []byte) (int, error) {
	g.decide()
	if g.zw != nil {
		return g.zw.Write(b)
	}
	return g.ResponseWriter.Write(b)
}

func (g *gzResponse) Flush() {
	if g.zw != nil {
		_ = g.zw.Flush()
	}
	if f, ok := g.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (g *gzResponse) Unwrap() http.ResponseWriter { return g.ResponseWriter }

func (g *gzResponse) close() {
	if g.zw != nil {
		_ = g.zw.Close()
		gzPool.Put(g.zw)
		g.zw = nil
	}
}

// gzipJSON 包装一个 Handler:客户端接受 gzip、且响应是 JSON / HLS 播放列表时压缩。
func gzipJSON(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") || r.Header.Get("Range") != "" || strings.Contains(r.URL.Path, "/speedtest/") {
			next.ServeHTTP(w, r)
			return
		}
		g := &gzResponse{ResponseWriter: w}
		defer g.close()
		next.ServeHTTP(g, r)
	})
}
