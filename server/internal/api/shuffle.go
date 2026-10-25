package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

type shuffleCursor struct {
	Seed  int64  `json:"seed"`
	Scope string `json:"scope"`
	Key   int64  `json:"key"`
	ID    int64  `json:"id"`
}

// A seeded permutation with keyset pagination: no random page overlap and no whole-library materialization.
func (s *Server) shuffle(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	seed, err := strconv.ParseInt(q.Get("seed"), 10, 64)
	if err != nil || seed < 1 || seed > 2147483646 {
		writeErr(w, 400, "bad.seed", "seed 无效")
		return
	}
	types, ok := parseTypes(q.Get("types"))
	if !ok {
		writeErr(w, 400, "bad.types", "types 无效")
		return
	}
	for _, typ := range types {
		if typ < 0 || typ > 2 {
			writeErr(w, 400, "bad.types", "仅支持图片与视频")
			return
		}
	}
	cond := "m.type IN " + inList(types) + " AND m.state<>2 AND m.size>0 AND m.root_id IN (SELECT id FROM roots WHERE enabled=1)"
	args := []any{}
	if v := q.Get("dialog"); v != "" {
		id, e := strconv.ParseInt(v, 10, 64)
		if e != nil || id <= 0 {
			writeErr(w, 400, "bad.dialog", "dialog 无效")
			return
		}
		cond += " AND m.dialog_id=?"
		args = append(args, id)
	}
	if v := q.Get("roots"); v != "" {
		parts := strings.Split(v, ",")
		if len(parts) > 100 {
			writeErr(w, 400, "bad.roots", "roots 过多")
			return
		}
		marks := []string{}
		for _, part := range parts {
			id, e := strconv.ParseInt(strings.TrimSpace(part), 10, 64)
			if e != nil || id <= 0 {
				writeErr(w, 400, "bad.roots", "roots 无效")
				return
			}
			marks = append(marks, "?")
			args = append(args, id)
		}
		cond += " AND m.root_id IN (" + strings.Join(marks, ",") + ")"
	}
	if v := strings.TrimSpace(q.Get("q")); v != "" {
		if len(v) > 1024 {
			writeErr(w, 400, "bad.q", "搜索词过长")
			return
		}
		cond += " AND instr(lower(m.name),lower(?))>0"
		args = append(args, v)
	}
	scopeData, _ := json.Marshal([]any{cond, args})
	h := sha256.Sum256(scopeData)
	scope := hex.EncodeToString(h[:])
	// SQLite integers remain exact: both factors are below 2^31.
	multiplier := (seed*48271)%2147483646 + 1
	key := fmt.Sprintf("((((m.id %% 2147483647) * %d) %% 2147483647 + %d) %% 2147483647)", multiplier, seed)
	where := cond
	if v := q.Get("cursor"); v != "" {
		var c shuffleCursor
		if !decCursor(v, &c) || c.Seed != seed || c.Scope != scope || c.Key < 0 || c.ID <= 0 {
			writeErr(w, 400, "bad.cursor", "播放范围或随机序列已变化")
			return
		}
		where += " AND (" + key + ",m.id)>(?,?)"
		args = append(args, c.Key, c.ID)
	}
	limit := limitParam(r, 60, 120)
	args = append(args, limit+1)
	rows, err := s.DB.QueryContext(r.Context(), "SELECT m.id,"+key+" FROM media m WHERE "+where+" ORDER BY "+key+",m.id LIMIT ?", args...)
	if err != nil {
		writeErr(w, 500, "shuffle.query", "读取播放队列失败")
		return
	}
	ids := []int64{}
	keys := []int64{}
	for rows.Next() {
		var id, k int64
		if err = rows.Scan(&id, &k); err != nil {
			break
		}
		ids = append(ids, id)
		keys = append(keys, k)
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		writeErr(w, 500, "shuffle.query", "读取播放队列失败")
		return
	}
	next := ""
	if len(ids) > limit {
		ids = ids[:limit]
		keys = keys[:limit]
		next = encCursor(shuffleCursor{seed, scope, keys[limit-1], ids[limit-1]})
	}
	items, err := s.itemsByID(r.Context(), ids)
	if err != nil {
		writeErr(w, 500, "shuffle.items", "读取媒体信息失败")
		return
	}
	if items == nil {
		items = []Item{}
	}
	var cursor any
	if next != "" {
		cursor = next
	}
	writeJSON(w, 200, map[string]any{"items": items, "nextCursor": cursor})
}
