package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
)

// 多用户可见范围:管理员给某个账号指定能看哪些根目录(盘符)。没有设置 = 能看全部;管理员和本机管理程序不受限。
// 限制作用在:文件夹列表 / 搜索 / 随机 / 随机播放的结果,以及所有按文件夹 id、媒体 id 访问的接口(看不到的一律当作不存在)。

// visibleRoots 返回账号能看的根目录 id;restricted=false 表示不受限。
func (s *Server) visibleRoots(uid int64) (ids []int64, restricted bool) {
	if uid <= 0 {
		return nil, false
	}
	var admin int
	if s.DB.QueryRow(`SELECT is_admin FROM users WHERE id=?`, uid).Scan(&admin) == nil && admin == 1 {
		return nil, false
	}
	rows, err := s.DB.Query(`SELECT root_id FROM user_roots WHERE user_id=?`, uid)
	if err != nil {
		return nil, false
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	return ids, len(ids) > 0
}

// rootCond 受限账号的根目录条件(col 是列名,如 "m.root_id");不受限返回空串。
func (s *Server) rootCond(uid int64, col string) (string, []any) {
	ids, restricted := s.visibleRoots(uid)
	if !restricted {
		return "", nil
	}
	marks := make([]string, len(ids))
	args := make([]any, len(ids))
	for i, id := range ids {
		marks[i], args[i] = "?", id
	}
	return " AND " + col + " IN (" + strings.Join(marks, ",") + ")", args
}

// pathDenied 请求的文件夹 / 媒体所在的根目录是否对这个账号不可见。
func (s *Server) pathDenied(r *http.Request, uid int64) bool {
	id := r.PathValue("id")
	if id == "" {
		return false
	}
	n, err := strconv.ParseInt(id, 10, 64)
	if err != nil {
		return false
	}
	ids, restricted := s.visibleRoots(uid)
	if !restricted {
		return false
	}
	var q string
	switch {
	case strings.HasPrefix(r.URL.Path, "/api/v1/dialogs/"):
		q = `SELECT root_id FROM dialogs WHERE id=?`
	case strings.HasPrefix(r.URL.Path, "/api/v1/media/"):
		q = `SELECT root_id FROM media WHERE id=?`
	default:
		return false
	}
	var root int64
	if s.DB.QueryRow(q, n).Scan(&root) != nil {
		return false // 不存在:交给原来的处理返回 404
	}
	for _, v := range ids {
		if v == root {
			return false
		}
	}
	return true
}

// adminUserRoots GET /api/v1/admin/user-roots?user=名字 → 全部根目录和这个账号可见的根目录;POST {user, roots:[id]} 设置(空 = 不限制)。
func (s *Server) adminUserRoots(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("user")
	var in struct {
		User  string  `json:"user"`
		Roots []int64 `json:"roots"`
	}
	if r.Method == "POST" {
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&in) != nil {
			writeErr(w, 400, "bad.request", "请求格式错误")
			return
		}
		name = in.User
	}
	var uid int64
	if s.DB.QueryRow(`SELECT id FROM users WHERE name=?`, name).Scan(&uid) != nil {
		writeErr(w, 404, "notfound", "用户不存在")
		return
	}
	if r.Method == "POST" {
		tx, err := s.DB.Begin()
		if err != nil {
			writeErr(w, 500, "internal", err.Error())
			return
		}
		defer tx.Rollback()
		_, _ = tx.Exec(`DELETE FROM user_roots WHERE user_id=?`, uid)
		for _, id := range in.Roots {
			if _, err := tx.Exec(`INSERT OR IGNORE INTO user_roots(user_id, root_id) VALUES(?,?)`, uid, id); err != nil {
				writeErr(w, 500, "internal", err.Error())
				return
			}
		}
		if err := tx.Commit(); err != nil {
			writeErr(w, 500, "internal", err.Error())
			return
		}
		w.WriteHeader(204)
		return
	}
	type rootInfo struct {
		ID    int64  `json:"id"`
		Label string `json:"label"`
		Path  string `json:"path"`
	}
	roots := []rootInfo{}
	if rows, err := s.DB.Query(`SELECT id, COALESCE(label,''), path FROM roots WHERE enabled=1 ORDER BY id`); err == nil {
		for rows.Next() {
			var x rootInfo
			if rows.Scan(&x.ID, &x.Label, &x.Path) == nil {
				roots = append(roots, x)
			}
		}
		rows.Close()
	}
	ids := []int64{}
	if rows, err := s.DB.Query(`SELECT root_id FROM user_roots WHERE user_id=?`, uid); err == nil {
		for rows.Next() {
			var id int64
			if rows.Scan(&id) == nil {
				ids = append(ids, id)
			}
		}
		rows.Close()
	}
	writeJSON(w, 200, map[string]any{"roots": roots, "visible": ids})
}
