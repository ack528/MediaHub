package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

)

// mediaDelete POST /api/v1/media/delete {ids:["…"]} —— 手机端删除(仅管理员账号)。文件不会被永久删除:移进所在盘的回收区
// (<根目录>\.mediahub\trash\delete-时间),可以在电脑上的管理程序里还原。受限账号看不到的文件当作不存在。
func (s *Server) mediaDelete(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IDs []json.Number `json:"ids"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in) != nil || len(in.IDs) == 0 || len(in.IDs) > 500 {
		writeErr(w, 400, "bad.request", "请求格式错误(一次最多 500 个)")
		return
	}
	uid := userID(r)
	vis, restricted := s.visibleRoots(uid)
	allowed := map[int64]bool{}
	for _, v := range vis {
		allowed[v] = true
	}
	batch := "delete-" + time.Now().Format("20060102-150405")
	failed := []string{}
	deleted := 0
	var freed int64
	roots := map[int64]bool{}
	var moved []int64
	for _, n := range in.IDs {
		id, err := n.Int64()
		if err != nil || id <= 0 {
			continue
		}
		m, err := s.mediaInfoByID(id)
		if err != nil || (restricted && !allowed[m.RootID]) {
			failed = append(failed, fmt.Sprintf("%d:文件不存在", id))
			continue
		}
		if _, err := os.Stat(m.full()); err != nil { // 文件已经不在了:只清索引
			moved = append(moved, id)
			continue
		}
		if err := moveToTrash(m, batch, "手机端删除"); err != nil {
			failed = append(failed, m.Name+":"+err.Error())
			continue
		}
		deleted++
		freed += m.Size
		moved = append(moved, id)
		roots[m.RootID] = true
	}
	if len(moved) > 0 {
		s.forgetMedia(moved...)
	}
	for rid := range roots {
		s.rescanSoon(rid)
	}
	if deleted > 0 {
		s.Log.Info("手机端删除(已移入回收区)", "user", s.Auth.UserName(uid), "n", deleted, "bytes", freed, "batch", batch, "ip", clientIP(r))
	}
	okIDs := make([]string, len(moved))
	for i, id := range moved {
		okIDs[i] = sid(id)
	}
	writeJSON(w, 200, map[string]any{"deleted": deleted, "freed": freed, "failed": failed, "batch": batch, "ids": okIDs})
}
