package api

import (
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"time"
)

// 播放器记住的转码偏好(每个用户一份,存在服务器上,多台手机共用):
//
//	GET /api/v1/state/player   → {json:{bitrate,codec,direct}, savedAt}
//	PUT /api/v1/state/player   body {bitrate,codec,direct,savedAt}
//
// 手机在播放器里选过画质(例如 1.5M 转码),服务端就记住它:之后手机请求转码时没带码率 / 编码,服务端直接按这个来;
// 切到下一个视频时手机也直接用它(并让服务端提前预热下一个视频,见 mediaHLSPrepare)。
// bitrate 为 0 / direct 为 true 表示"原画直接播放"。savedAt 毫秒时间戳,新的覆盖旧的。
type playerPref struct {
	Bitrate int    `json:"bitrate"`
	Codec   string `json:"codec"` // h264 | h265
	Direct  bool   `json:"direct"`
	SavedAt int64  `json:"savedAt"`
}

var prefCache = struct {
	sync.RWMutex
	m map[int64]playerPref
}{m: map[int64]playerPref{}}

// playerPrefs 当前用户的偏好;没有记录时 Codec 为 h264、Bitrate 为 0(由 HLS.effective 补默认值)。
func (s *Server) playerPrefs(uid int64) playerPref {
	prefCache.RLock()
	p, ok := prefCache.m[uid]
	prefCache.RUnlock()
	if ok {
		return p
	}
	p = playerPref{Codec: "h264"}
	var js string
	if err := s.DB.QueryRow(`SELECT json FROM user_player WHERE user_id=?`, uid).Scan(&js); err == nil {
		_ = json.Unmarshal([]byte(js), &p)
	}
	if p.Codec != "h265" {
		p.Codec = "h264"
	}
	prefCache.Lock()
	prefCache.m[uid] = p
	prefCache.Unlock()
	return p
}

func (s *Server) getPlayerPref(w http.ResponseWriter, r *http.Request) {
	var js string
	var at int64
	err := s.DB.QueryRow(`SELECT json, saved_at FROM user_player WHERE user_id=?`, userID(r)).Scan(&js, &at)
	if err == sql.ErrNoRows {
		writeErr(w, 404, "notfound", "没有记录")
		return
	} else if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"json": json.RawMessage(js), "savedAt": at})
}

func (s *Server) putPlayerPref(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4<<10))
	var in playerPref
	if err != nil || json.Unmarshal(body, &in) != nil {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	if in.Codec != "h265" {
		in.Codec = "h264"
	}
	if in.Bitrate < 0 {
		in.Bitrate = 0
	}
	if max := s.Cfg.Video.MaxTranscodeBitrate; max > 0 && in.Bitrate > max {
		in.Bitrate = max
	}
	if in.SavedAt <= 0 {
		in.SavedAt = time.Now().UnixMilli()
	}
	uid := userID(r)
	js, _ := json.Marshal(in)
	prefCache.Lock()
	prefCache.m[uid] = in // 先更新内存:紧接着的转码请求马上就能用上
	prefCache.Unlock()
	writeBehind(func() error {
		_, err := s.DB.Exec(`INSERT INTO user_player(user_id, json, saved_at) VALUES(?,?,?)
			ON CONFLICT(user_id) DO UPDATE SET json=excluded.json, saved_at=excluded.saved_at WHERE excluded.saved_at>=user_player.saved_at`,
			uid, string(js), in.SavedAt)
		return err
	})
	w.WriteHeader(204)
}
