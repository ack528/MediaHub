package api

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
)

// writeJSONETag 同 writeJSON,另外按内容算 ETag:手机带着上次的 If-None-Match 来,内容没变就只回 304(没有正文)。
// 经穿透 / 移动网络打开一个没变化的群组,省下整页 JSON 的下载;手机端同时先显示本地缓存的上次内容,304 就什么都不用换。
// ETag 是弱校验(W/"…"):gzip 中间件会改变传输编码,内容语义相同即可。
func writeJSONETag(w http.ResponseWriter, r *http.Request, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		writeErr(w, 500, "internal", "编码失败")
		return
	}
	sum := sha1.Sum(b)
	tag := `W/"` + hex.EncodeToString(sum[:8]) + `"`
	w.Header().Set("ETag", tag)
	w.Header().Set("Cache-Control", "private, no-cache") // 每次都要向服务器确认(带 If-None-Match),没变就 304
	if inm := r.Header.Get("If-None-Match"); inm != "" {
		for _, c := range strings.Split(inm, ",") {
			if strings.TrimSpace(c) == tag {
				w.WriteHeader(http.StatusNotModified)
				return
			}
		}
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(200)
	_, _ = w.Write(append(b, '\n'))
}
