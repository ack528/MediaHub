package api

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
)

type Counts struct {
	Photo int `json:"photo"`
	Video int `json:"video"`
	GIF   int `json:"gif"`
	Audio int `json:"audio"`
	File  int `json:"file"`
}

type Last struct {
	ID        string `json:"id"`
	Type      string `json:"type"`
	Name      string `json:"name"`
	TakenAt   string `json:"takenAt"`
	W         *int   `json:"w,omitempty"`
	H         *int   `json:"h,omitempty"`
	Thumbhash string `json:"thumbhash,omitempty"`
}

type DState struct {
	Pinned   bool `json:"pinned"`
	Archived bool `json:"archived"`
	Unread   int  `json:"unread"`
}

type Dialog struct {
	ID          string   `json:"id"`
	ParentID    string   `json:"parentId,omitempty"`
	RootID      string   `json:"rootId"`
	Title       string   `json:"title"`
	PathDisplay string   `json:"pathDisplay"`
	Counts      Counts   `json:"counts"`
	Recursive   int      `json:"recursiveCount"`
	Last        *Last    `json:"last,omitempty"`
	Cover       []string `json:"cover"`
	TopicsCount int      `json:"topicsCount"`
	State       DState   `json:"state"`
	Version     string   `json:"version"`
}

const dialogCols = `d.id, COALESCE(d.parent_id,0), d.root_id, d.title, d.rel_path, ro.label,
	d.cnt_photo, d.cnt_video, d.cnt_gif, d.cnt_audio, d.cnt_file, d.cnt_recursive, d.topics,
	COALESCE(d.last_item_id,0), d.version, COALESCE(ud.pinned,0), COALESCE(ud.archived,0), COALESCE(ud.last_read_taken,0)`

const dialogFrom = `FROM dialogs d JOIN roots ro ON ro.id=d.root_id LEFT JOIN user_dialog ud ON ud.dialog_id=d.id AND ud.user_id=?`

func (s *Server) scanDialog(sc scanner, uid int64) (Dialog, error) {
	var (
		d                                  Dialog
		id, parent, root, lastID, lastRead int64
		ver                                int64
		title, rel, label                  string
		cp, cv, cg, ca, cf, rec, topics    int
		pinned, archived                   int
	)
	if err := sc.Scan(&id, &parent, &root, &title, &rel, &label, &cp, &cv, &cg, &ca, &cf, &rec, &topics, &lastID, &ver, &pinned, &archived, &lastRead); err != nil {
		return d, err
	}
	d.ID, d.RootID, d.Title = sid(id), sid(root), title
	if parent != 0 {
		d.ParentID = sid(parent)
	}
	d.PathDisplay = label
	if rel != "" {
		d.PathDisplay += `\` + rel
	}
	d.Counts = Counts{cp, cv, cg, ca, cf}
	d.Recursive, d.TopicsCount = rec, topics
	d.Version = strconv.FormatInt(ver, 36)
	d.State = DState{Pinned: pinned == 1, Archived: archived == 1}
	d.Cover = []string{}
	if lastID != 0 {
		row := s.DB.QueryRow(`SELECT id,type,name,taken_eff,w,h,thumbhash FROM media WHERE id=?`, lastID)
		var lid, taken int64
		var typ int
		var name string
		var w, h sql.NullInt64
		var th []byte
		if row.Scan(&lid, &typ, &name, &taken, &w, &h, &th) == nil {
			l := &Last{ID: sid(lid), Type: typeName(typ), Name: name, TakenAt: rfc(taken)}
			if w.Valid && h.Valid {
				wi, hi := int(w.Int64), int(h.Int64)
				l.W, l.H = &wi, &hi
			}
			if len(th) > 0 {
				l.Thumbhash = base64.StdEncoding.EncodeToString(th)
			}
			d.Last = l
		}
		if rows, err := s.DB.Query(`SELECT id FROM media WHERE dialog_id=? AND type IN (0,1,2) ORDER BY taken_eff DESC, id DESC LIMIT 4`, id); err == nil {
			for rows.Next() {
				var cid int64
				if rows.Scan(&cid) == nil {
					d.Cover = append(d.Cover, sid(cid))
				}
			}
			rows.Close()
		}
	}
	if lastRead > 0 {
		_ = s.DB.QueryRow(`SELECT count(*) FROM media WHERE dialog_id=? AND type IN (0,1,2) AND taken_eff>?`, id, lastRead).Scan(&d.State.Unread)
	}
	return d, nil
}

func typeName(t int) string {
	return [...]string{"photo", "video", "gif", "audio", "file"}[t]
}

// 对话列表:含媒体(照片/视频/GIF/音频)的母文件夹;子文件夹不单独成群。
const hasMedia = `(d.cnt_photo+d.cnt_video+d.cnt_gif+d.cnt_audio)>0 AND d.parent_id IS NULL` // 只列母文件夹(及盘符根目录)

type dcursor struct {
	K  string `json:"k"`
	ID int64  `json:"id"`
}

func encCursor(v any) string {
	b, _ := json.Marshal(v)
	return base64.RawURLEncoding.EncodeToString(b)
}

func decCursor(s string, v any) bool {
	b, err := base64.RawURLEncoding.DecodeString(s)
	return err == nil && json.Unmarshal(b, v) == nil
}

func limitParam(r *http.Request, def, max int) int {
	n, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || n <= 0 {
		return def
	}
	if n > max {
		return max
	}
	return n
}

func (s *Server) listDialogs(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	q := r.URL.Query()
	limit := limitParam(r, 50, 200)
	sortBy := q.Get("sort")
	if sortBy == "" {
		sortBy = "last"
	}
	where := []string{hasMedia}
	args := []any{uid}
	switch g := q.Get("group"); {
	case g == "" || g == "all":
		where = append(where, "COALESCE(ud.archived,0)=0")
	case g == "pinned":
		where = append(where, "ud.pinned=1")
	case g == "archived":
		where = append(where, "ud.archived=1")
	case strings.HasPrefix(g, "root:"):
		rid, err := strconv.ParseInt(g[5:], 10, 64)
		if err != nil {
			writeErr(w, 400, "bad.group", "group 参数错误")
			return
		}
		where = append(where, "d.root_id=?", "COALESCE(ud.archived,0)=0")
		args = append(args, rid)
	default:
		writeErr(w, 400, "bad.group", "group 参数错误")
		return
	}
	var order string
	var keyExpr string
	switch sortBy {
	case "last":
		order, keyExpr = "d.last_taken DESC, d.id DESC", "d.last_taken"
	case "name":
		order, keyExpr = "d.title_sort ASC, d.id ASC", "d.title_sort"
	default:
		writeErr(w, 400, "bad.sort", "sort 只支持 last / name")
		return
	}
	if c := q.Get("cursor"); c != "" {
		var cur dcursor
		if !decCursor(c, &cur) {
			writeErr(w, 400, "bad.cursor", "cursor 无效")
			return
		}
		if sortBy == "last" {
			k, _ := strconv.ParseInt(cur.K, 10, 64)
			where = append(where, "("+keyExpr+", d.id) < (?, ?)")
			args = append(args, k, cur.ID)
		} else {
			where = append(where, "("+keyExpr+", d.id) > (?, ?)")
			args = append(args, cur.K, cur.ID)
		}
	}
	sqlq := `SELECT ` + dialogCols + `, ` + keyExpr + ` ` + dialogFrom + ` WHERE ` + strings.Join(where, " AND ") +
		` ORDER BY ` + order + ` LIMIT ?`
	args = append(args, limit+1)
	rows, err := s.DB.Query(sqlq, args...)
	if err != nil {
		s.Log.Error("listDialogs", "err", err)
		writeErr(w, 500, "internal", "查询失败")
		return
	}
	defer rows.Close()
	var out []Dialog
	var keys []string
	for rows.Next() {
		var key any
		d, err := s.scanDialog(rowsWithExtra{rows, &key}, uid)
		if err != nil {
			writeErr(w, 500, "internal", err.Error())
			return
		}
		out = append(out, d)
		keys = append(keys, keyString(key))
	}
	rows.Close()
	resp := map[string]any{}
	if len(out) > limit {
		out, keys = out[:limit], keys[:limit]
		id, _ := strconv.ParseInt(out[limit-1].ID, 10, 64)
		resp["nextCursor"] = encCursor(dcursor{K: keys[limit-1], ID: id})
	}
	if out == nil {
		out = []Dialog{}
	}
	resp["dialogs"] = out
	writeJSON(w, 200, resp)
}

// rowsWithExtra 把额外的排序键列拼到 Scan 的末尾。
type rowsWithExtra struct {
	r     *sql.Rows
	extra *any
}

func (x rowsWithExtra) Scan(dest ...any) error { return x.r.Scan(append(dest, x.extra)...) }

func keyString(v any) string {
	switch k := v.(type) {
	case int64:
		return strconv.FormatInt(k, 10)
	case string:
		return k
	case []byte:
		return string(k)
	case nil:
		return ""
	}
	return ""
}

func (s *Server) getDialog(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	uid := userID(r)
	d, err := s.scanDialog(s.DB.QueryRow(`SELECT `+dialogCols+` `+dialogFrom+` WHERE d.id=?`, uid, id), uid)
	if err == sql.ErrNoRows {
		writeErr(w, 404, "notfound", "对话不存在")
		return
	} else if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	writeJSON(w, 200, d)
}

func (s *Server) listTopics(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	uid := userID(r)
	rows, err := s.DB.Query(`SELECT `+dialogCols+` `+dialogFrom+` WHERE d.parent_id=? AND d.cnt_recursive>0 ORDER BY d.title_sort, d.id`, uid, id)
	if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	defer rows.Close()
	out := []Dialog{}
	for rows.Next() {
		d, err := s.scanDialog(rows, uid)
		if err != nil {
			writeErr(w, 500, "internal", err.Error())
			return
		}
		out = append(out, d)
	}
	writeJSON(w, 200, map[string]any{"dialogs": out})
}

func (s *Server) putDialogState(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	var in struct {
		Pinned          *bool  `json:"pinned"`
		Archived        *bool  `json:"archived"`
		LastReadTakenAt string `json:"lastReadTakenAt"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in); err != nil {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	uid := userID(r)
	_, _ = s.DB.Exec(`INSERT OR IGNORE INTO user_dialog(user_id,dialog_id) VALUES(?,?)`, uid, id)
	if in.Pinned != nil {
		_, _ = s.DB.Exec(`UPDATE user_dialog SET pinned=? WHERE user_id=? AND dialog_id=?`, b2i(*in.Pinned), uid, id)
	}
	if in.Archived != nil {
		_, _ = s.DB.Exec(`UPDATE user_dialog SET archived=? WHERE user_id=? AND dialog_id=?`, b2i(*in.Archived), uid, id)
	}
	if in.LastReadTakenAt != "" {
		if t, err := parseTime(in.LastReadTakenAt); err == nil {
			_, _ = s.DB.Exec(`UPDATE user_dialog SET last_read_taken=? WHERE user_id=? AND dialog_id=?`, t, uid, id)
		}
	}
	w.WriteHeader(204)
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}
