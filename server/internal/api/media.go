package api

import (
	"database/sql"
	"errors"
	"net/http"
	"os"
	"strconv"
	"time"

	"mediahub/internal/winfs"
)

// mediaFile 原文件直出,支持 Range / If-Range / HEAD。
func (s *Server) mediaFile(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	var name, ext string
	var size, mtime int64
	err := s.DB.QueryRow(`SELECT name, ext, size, mtime FROM media WHERE id=?`, id).Scan(&name, &ext, &size, &mtime)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "notfound", "媒体不存在")
		return
	} else if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	full, err := MediaPath(s.DB, id)
	if err != nil {
		writeErr(w, 404, "notfound", "媒体不存在")
		return
	}
	f, err := winfs.Open(full)
	if err != nil {
		writeErr(w, 404, "gone", "文件已不存在(等待重新扫描)")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		writeErr(w, 404, "gone", "文件不可读")
		return
	}
	if st.Size() == 0 {
		writeErr(w, 422, "file.empty", "文件大小为 0 字节(多半是下载失败留下的空文件)")
		return
	}
	w.Header().Set("Content-Type", MimeByExt(ext))
	w.Header().Set("ETag", `"`+strconv.FormatInt(st.ModTime().UnixMilli(), 16)+"-"+strconv.FormatInt(st.Size(), 16)+`"`)
	if r.URL.Query().Get("v") != "" {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
	http.ServeContent(w, r, name, st.ModTime(), f)
}

func (s *Server) mediaPoster(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	res, err := s.Poster.Resolve(r.Context(), id)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		writeErr(w, 404, "notfound", "媒体不存在")
		return
	case err != nil && r.Context().Err() != nil:
		return // 客户端已取消
	case err != nil:
		s.Log.Warn("poster 失败", "id", id, "err", err)
		writeErr(w, 404, "poster.unavailable", "无法生成封面")
		return
	}
	w.Header().Set("X-Poster-Source", res.Source) // own | jellyfin,便于排查封面从哪来
	if r.URL.Query().Get("v") != "" {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
	if res.Data != nil { // Jellyfin 已生成的图:直接转发,不在我们这里再存一份
		w.Header().Set("Content-Type", res.ContentType)
		w.Header().Set("Content-Length", strconv.Itoa(len(res.Data)))
		_, _ = w.Write(res.Data)
		return
	}
	f, err := os.Open(res.Path)
	if err != nil {
		writeErr(w, 404, "poster.unavailable", "封面不可用")
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	w.Header().Set("Content-Type", "image/webp")
	http.ServeContent(w, r, "poster.webp", st.ModTime().Truncate(time.Second), f)
}
