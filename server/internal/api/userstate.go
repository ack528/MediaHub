package api

import (
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"time"
)

// 每个用户的浏览记录 / 播放进度,存在服务器上:换一台设备打开同一个群或视频,回到上次退出的位置。
//
//	GET/PUT /api/v1/dialogs/{id}/view      群聊(文件夹)的浏览位置与视图设置(客户端定义的 JSON,服务器原样保存)
//	GET/PUT /api/v1/media/{id}/playback    视频播放进度(毫秒)
//	DELETE  /api/v1/state/views | /api/v1/state/playback   清空本人的全部记录
//
// 以 savedAt(毫秒时间戳)为准,后写入的覆盖先写入的;比已有记录旧的写入被忽略。

func (s *Server) getView(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	var js string
	var at int64
	err := s.DB.QueryRow(`SELECT json, saved_at FROM user_view WHERE user_id=? AND dialog_id=?`, userID(r), id).Scan(&js, &at)
	if err == sql.ErrNoRows {
		writeErr(w, 404, "notfound", "没有记录")
		return
	} else if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"json": json.RawMessage(js), "savedAt": at})
}

func (s *Server) putView(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 16<<10))
	if err != nil || !json.Valid(body) {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	var head struct {
		SavedAt int64 `json:"savedAt"`
	}
	_ = json.Unmarshal(body, &head)
	if head.SavedAt <= 0 {
		head.SavedAt = time.Now().UnixMilli()
	}
	if _, err := s.DB.Exec(`INSERT INTO user_view(user_id,dialog_id,json,saved_at) VALUES(?,?,?,?)
		ON CONFLICT(user_id,dialog_id) DO UPDATE SET json=excluded.json, saved_at=excluded.saved_at
		WHERE excluded.saved_at >= user_view.saved_at`, userID(r), id, string(body), head.SavedAt); err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	w.WriteHeader(204)
}

func (s *Server) getPlayback(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	var pos, at int64
	err := s.DB.QueryRow(`SELECT pos_ms, saved_at FROM user_playback WHERE user_id=? AND media_id=?`, userID(r), id).Scan(&pos, &at)
	if err == sql.ErrNoRows {
		writeErr(w, 404, "notfound", "没有记录")
		return
	} else if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"posMs": pos, "savedAt": at})
}

func (s *Server) putPlayback(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	var in struct {
		PosMs   int64 `json:"posMs"`
		SavedAt int64 `json:"savedAt"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&in); err != nil || in.PosMs < 0 {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	if in.SavedAt <= 0 {
		in.SavedAt = time.Now().UnixMilli()
	}
	// pos=0 也保存(表示"看完了 / 重新开始"),这样在另一台设备上也会从头播放
	if _, err := s.DB.Exec(`INSERT INTO user_playback(user_id,media_id,pos_ms,saved_at) VALUES(?,?,?,?)
		ON CONFLICT(user_id,media_id) DO UPDATE SET pos_ms=excluded.pos_ms, saved_at=excluded.saved_at
		WHERE excluded.saved_at >= user_playback.saved_at`, userID(r), id, in.PosMs, in.SavedAt); err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	w.WriteHeader(204)
}

func (s *Server) clearViews(w http.ResponseWriter, r *http.Request) {
	_, _ = s.DB.Exec(`DELETE FROM user_view WHERE user_id=?`, userID(r))
	w.WriteHeader(204)
}

func (s *Server) clearPlayback(w http.ResponseWriter, r *http.Request) {
	_, _ = s.DB.Exec(`DELETE FROM user_playback WHERE user_id=?`, userID(r))
	w.WriteHeader(204)
}
