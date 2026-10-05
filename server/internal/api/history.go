package api

import (
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"mediahub/internal/classify"
	"mediahub/internal/natsort"
)

type sortDef struct {
	col     string
	text    bool
	defDesc bool
	timeCol bool
}

var sorts = map[string]sortDef{
	"taken":    {"m.taken_eff", false, true, true},
	"modified": {"m.mtime", false, true, true},
	"created":  {"m.ctime", false, true, true},
	"name":     {"m.name_sort", true, false, false},
	"size":     {"m.size", false, true, false},
	"type":     {"m.ext", true, false, false},
}

// pos 是有序集合中的一个位置(排序键 + id)。
type pos struct {
	K  string `json:"k"`
	ID int64  `json:"id"`
	D  string `json:"d,omitempty"` // 游标方向:n=向后(更远),p=向前
}

func (p pos) arg(sd sortDef) any {
	if sd.text {
		return p.K
	}
	n, _ := strconv.ParseInt(p.K, 10, 64)
	return n
}

func parseTypes(v string) ([]int, bool) {
	if v == "" {
		return []int{0, 1, 2}, true
	}
	var out []int
	for _, p := range strings.Split(v, ",") {
		t, ok := classify.Parse(strings.TrimSpace(p))
		if !ok {
			return nil, false
		}
		out = append(out, int(t))
	}
	return out, true
}

func inList(ts []int) string {
	parts := make([]string, len(ts))
	for i, t := range ts {
		parts[i] = strconv.Itoa(t)
	}
	return "(" + strings.Join(parts, ",") + ")"
}

func parseTime(v string) (int64, error) {
	for _, l := range []string{time.RFC3339, "2006-01-02T15:04:05", "2006-01-02"} {
		if t, err := time.ParseInLocation(l, v, time.Local); err == nil {
			return t.UnixMilli(), nil
		}
	}
	return 0, fmt.Errorf("bad time %q", v)
}

type page struct {
	items []Item
	keys  []string
}

// query 取一段:op 是行值比较运算符(为空表示不限制起点);forward=false 时反向取并在返回前翻回。
func (s *Server) query(dialog int64, types []int, sd sortDef, desc bool, op string, at *pos, forward bool, limit int) (page, error) {
	where := "m.dialog_id=? AND m.type IN " + inList(types)
	args := []any{dialog}
	if op != "" {
		where += " AND (" + sd.col + ", m.id) " + op + " (?, ?)"
		args = append(args, at.arg(sd), at.ID)
	}
	dir := "ASC"
	if desc == forward { // 降序且向前,或升序且向后 → 保持;下面统一计算
		dir = "DESC"
	}
	// 顺序方向:forward 时与请求方向一致;否则相反
	ord := desc
	if !forward {
		ord = !desc
	}
	dir = "ASC"
	if ord {
		dir = "DESC"
	}
	q := "SELECT " + itemCols + ", " + sd.col + " FROM media m WHERE " + where +
		" ORDER BY " + sd.col + " " + dir + ", m.id " + dir + " LIMIT ?"
	args = append(args, limit)
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		return page{}, err
	}
	defer rows.Close()
	var pg page
	for rows.Next() {
		var key any
		it, err := scanItem(rows, &key)
		if err != nil {
			return page{}, err
		}
		pg.items = append(pg.items, it)
		pg.keys = append(pg.keys, keyString(key))
	}
	if !forward {
		for i, j := 0, len(pg.items)-1; i < j; i, j = i+1, j-1 {
			pg.items[i], pg.items[j] = pg.items[j], pg.items[i]
			pg.keys[i], pg.keys[j] = pg.keys[j], pg.keys[i]
		}
	}
	return pg, rows.Err()
}

func (s *Server) posOf(dialog int64, types []int, sd sortDef, mediaID int64) (pos, bool) {
	var key any
	err := s.DB.QueryRow("SELECT "+sd.col+" FROM media m WHERE m.id=? AND m.dialog_id=?", mediaID, dialog).Scan(&key)
	if err != nil {
		return pos{}, false
	}
	return pos{K: keyString(key), ID: mediaID}, true
}

// 比较运算符:相对"请求方向"的 严格之后 / 严格之前 / 含当前及之后
func ops(desc bool) (after, before, atOrAfter string) {
	if desc {
		return "<", ">", "<="
	}
	return ">", "<", ">="
}

func (s *Server) history(w http.ResponseWriter, r *http.Request) {
	did, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	q := r.URL.Query()
	sortName := q.Get("sort")
	if sortName == "" {
		sortName = "taken"
	}
	sd, ok := sorts[sortName]
	if !ok {
		writeErr(w, 400, "bad.sort", "sort 取值: taken|modified|created|name|size|type")
		return
	}
	desc := sd.defDesc
	switch q.Get("dir") {
	case "desc":
		desc = true
	case "asc":
		desc = false
	case "":
	default:
		writeErr(w, 400, "bad.dir", "dir 取值: asc|desc")
		return
	}
	types, ok := parseTypes(q.Get("types"))
	if !ok {
		writeErr(w, 400, "bad.types", "types 取值: photo,video,gif,audio,file")
		return
	}
	if q.Get("recursive") == "true" {
		writeErr(w, 501, "unsupported", "recursive 将在 M4 提供")
		return
	}
	limit := limitParam(r, 60, 200)
	after, before, atOrAfter := ops(desc)

	var total int
	if err := s.DB.QueryRow("SELECT count(*) FROM media m WHERE m.dialog_id=? AND m.type IN "+inList(types), did).Scan(&total); err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}

	var (
		items         []Item
		keys          []string
		nextC, prevC  string
		firstRank     = -1
		hasMoreAfter  bool
		hasMoreBefore bool
		errQ          error
	)
	mk := func(p pos, d string) string { p.D = d; return encCursor(p) }

	switch {
	case q.Get("around") != "" || q.Get("aroundDate") != "":
		var at pos
		if v := q.Get("around"); v != "" {
			mid, err := strconv.ParseInt(v, 10, 64)
			if err != nil {
				writeErr(w, 400, "bad.around", "around 无效")
				return
			}
			p, found := s.posOf(did, types, sd, mid)
			if !found {
				writeErr(w, 404, "notfound", "条目不在该对话中")
				return
			}
			at = p
		} else {
			if !sd.timeCol {
				writeErr(w, 400, "bad.around", "aroundDate 只用于按时间排序")
				return
			}
			ms, err := parseTime(q.Get("aroundDate"))
			if err != nil {
				writeErr(w, 400, "bad.around", "aroundDate 无效")
				return
			}
			// 降序:取 <= 该日末尾的第一项;升序:取 >= 该日起点的第一项
			if desc {
				ms += 24*3600*1000 - 1
				at = pos{K: strconv.FormatInt(ms, 10), ID: math.MaxInt64}
			} else {
				at = pos{K: strconv.FormatInt(ms, 10), ID: math.MinInt64}
			}
		}
		half := limit / 2
		bp, err := s.query(did, types, sd, desc, before, &at, false, half+1)
		if err != nil {
			errQ = err
			break
		}
		ap, err := s.query(did, types, sd, desc, atOrAfter, &at, true, limit-half+1)
		if err != nil {
			errQ = err
			break
		}
		if len(bp.items) > half {
			hasMoreBefore = true
			bp.items, bp.keys = bp.items[1:], bp.keys[1:]
		}
		if len(ap.items) > limit-half {
			hasMoreAfter = true
			ap.items, ap.keys = ap.items[:limit-half], ap.keys[:limit-half]
		}
		items = append(bp.items, ap.items...)
		keys = append(bp.keys, ap.keys...)
	case q.Get("cursor") != "":
		var c pos
		if !decCursor(q.Get("cursor"), &c) {
			writeErr(w, 400, "bad.cursor", "cursor 无效")
			return
		}
		if c.D == "p" {
			pg, err := s.query(did, types, sd, desc, before, &c, false, limit+1)
			if err != nil {
				errQ = err
				break
			}
			if len(pg.items) > limit {
				hasMoreBefore = true
				pg.items, pg.keys = pg.items[1:], pg.keys[1:]
			}
			items, keys = pg.items, pg.keys
			hasMoreAfter = true // 我们是从后面翻回来的,后面一定还有
		} else {
			pg, err := s.query(did, types, sd, desc, after, &c, true, limit+1)
			if err != nil {
				errQ = err
				break
			}
			if len(pg.items) > limit {
				hasMoreAfter = true
				pg.items, pg.keys = pg.items[:limit], pg.keys[:limit]
			}
			items, keys = pg.items, pg.keys
			hasMoreBefore = true
		}
	default:
		pg, err := s.query(did, types, sd, desc, "", nil, true, limit+1)
		if err != nil {
			errQ = err
			break
		}
		if len(pg.items) > limit {
			hasMoreAfter = true
			pg.items, pg.keys = pg.items[:limit], pg.keys[:limit]
		}
		items, keys = pg.items, pg.keys
		firstRank = 0
	}
	if errQ != nil {
		s.Log.Error("history", "err", errQ)
		writeErr(w, 500, "internal", "查询失败")
		return
	}
	if items == nil {
		items = []Item{}
	}
	if n := len(items); n > 0 {
		lastID, _ := strconv.ParseInt(items[n-1].ID, 10, 64)
		firstID, _ := strconv.ParseInt(items[0].ID, 10, 64)
		if hasMoreAfter {
			nextC = mk(pos{K: keys[n-1], ID: lastID}, "n")
		}
		if hasMoreBefore {
			prevC = mk(pos{K: keys[0], ID: firstID}, "p")
		}
		if firstRank < 0 { // around / cursor 情形:数出第一项之前有多少项
			var rk int
			e := s.DB.QueryRow("SELECT count(*) FROM media m WHERE m.dialog_id=? AND m.type IN "+inList(types)+
				" AND ("+sd.col+", m.id) "+before+" (?, ?)", did, pos{K: keys[0]}.arg(sd), firstID).Scan(&rk)
			if e == nil {
				firstRank = rk
			}
		}
	} else if firstRank < 0 {
		firstRank = 0
	}
	resp := map[string]any{"items": items, "total": total, "firstRank": firstRank}
	if nextC != "" {
		resp["nextCursor"] = nextC
	}
	if prevC != "" {
		resp["prevCursor"] = prevC
	}
	writeJSON(w, 200, resp)
}

type Bucket struct {
	Key    string `json:"key"`
	Count  int    `json:"count"`
	Offset int    `json:"offset"`
}

func (s *Server) buckets(w http.ResponseWriter, r *http.Request) {
	did, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	q := r.URL.Query()
	sortName := q.Get("sort")
	if sortName == "" {
		sortName = "taken"
	}
	sd, ok := sorts[sortName]
	if !ok || !sd.timeCol {
		writeJSON(w, 200, map[string]any{"buckets": []Bucket{}})
		return
	}
	types, ok := parseTypes(q.Get("types"))
	if !ok {
		writeErr(w, 400, "bad.types", "types 无效")
		return
	}
	format := map[string]string{"": "%Y-%m", "month": "%Y-%m", "day": "%Y-%m-%d", "year": "%Y"}[q.Get("by")]
	if format == "" {
		writeErr(w, 400, "bad.by", "by 取值: year|month|day")
		return
	}
	desc := sd.defDesc
	if q.Get("dir") == "asc" {
		desc = false
	} else if q.Get("dir") == "desc" {
		desc = true
	}
	order := "ASC"
	if desc {
		order = "DESC"
	}
	rows, err := s.DB.Query("SELECT strftime('"+format+"', "+sd.col+"/1000, 'unixepoch', 'localtime') AS k, count(*) FROM media m "+
		"WHERE m.dialog_id=? AND m.type IN "+inList(types)+" GROUP BY k ORDER BY k "+order, did)
	if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	defer rows.Close()
	out := []Bucket{}
	off := 0
	for rows.Next() {
		var b Bucket
		if rows.Scan(&b.Key, &b.Count) != nil {
			continue
		}
		b.Offset = off
		off += b.Count
		out = append(out, b)
	}
	writeJSON(w, 200, map[string]any{"buckets": out, "total": off})
}

func (s *Server) search(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	term := strings.TrimSpace(q.Get("q"))
	if term == "" {
		writeJSON(w, 200, map[string]any{"items": []Item{}})
		return
	}
	types, ok := parseTypes(q.Get("types"))
	if !ok {
		writeErr(w, 400, "bad.types", "types 无效")
		return
	}
	limit := limitParam(r, 50, 200)
	offset, _ := strconv.Atoi(q.Get("cursor"))
	args := []any{}
	cond := "m.type IN " + inList(types) + " AND m.root_id IN (SELECT id FROM roots WHERE enabled=1)"
	if d := q.Get("dialog"); d != "" {
		did, err := strconv.ParseInt(d, 10, 64)
		if err != nil {
			writeErr(w, 400, "bad.id", "dialog 无效")
			return
		}
		cond += " AND m.dialog_id=?"
		args = append(args, did)
	}
	var from string
	if len([]rune(term)) >= 3 {
		from = "media_fts f JOIN media m ON m.id=f.rowid"
		cond += " AND f.media_fts MATCH ?"
		args = append(args, `"`+strings.ReplaceAll(term, `"`, `""`)+`"`)
	} else { // trigram 至少 3 个字符,更短的用 LIKE
		from = "media m"
		cond += " AND m.name LIKE ? ESCAPE '\\'"
		args = append(args, "%"+strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(term)+"%")
	}
	_ = natsort.Key
	args = append(args, limit+1, offset)
	rows, err := s.DB.Query("SELECT "+itemCols+" FROM "+from+" WHERE "+cond+" ORDER BY m.taken_eff DESC, m.id DESC LIMIT ? OFFSET ?", args...)
	if err != nil {
		s.Log.Error("search", "err", err)
		writeErr(w, 500, "internal", "查询失败")
		return
	}
	defer rows.Close()
	items := []Item{}
	for rows.Next() {
		it, err := scanItem(rows)
		if err != nil {
			writeErr(w, 500, "internal", err.Error())
			return
		}
		items = append(items, it)
	}
	resp := map[string]any{}
	if len(items) > limit {
		items = items[:limit]
		resp["nextCursor"] = strconv.Itoa(offset + limit)
	}
	resp["items"] = items
	writeJSON(w, 200, resp)
}
