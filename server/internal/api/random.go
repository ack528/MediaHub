package api

import (
	"database/sql"
	"math/rand"
	"net/http"
)

// random 随机取一批媒体(手机端「随机浏览」)。
// 不用 ORDER BY RANDOM()(几十万行要全表排序):在 [最小 id, 最大 id] 里随机取点,每个点取 id >= 该点的第一条符合条件的,
// 每条都是一次主键索引查找,取 60 条也只要几毫秒。id 有空洞时空洞后面的那一条被选中的概率偏大,对"随便看看"无所谓。
// 参数:types(同 history,默认全部)、limit(默认 60,最多 120)。已损坏的文件和空文件不会出现。
func (s *Server) random(w http.ResponseWriter, r *http.Request) {
	types, ok := parseTypes(r.URL.Query().Get("types"))
	if !ok {
		writeErr(w, 400, "bad.types", "types 无效")
		return
	}
	limit := limitParam(r, 60, 120)
	cond := "m.type IN " + inList(types) + " AND m.state<>2 AND m.size>0 AND m.root_id IN (SELECT id FROM roots WHERE enabled=1)"
	var lo, hi sql.NullInt64
	if err := s.DB.QueryRow("SELECT MIN(m.id), MAX(m.id) FROM media m WHERE "+cond).Scan(&lo, &hi); err != nil {
		s.Log.Error("random", "err", err)
		writeErr(w, 500, "internal", "查询失败")
		return
	}
	items := []Item{}
	if !lo.Valid {
		writeJSON(w, 200, map[string]any{"items": items})
		return
	}
	seen := map[string]bool{}
	for tries := 0; len(items) < limit && tries < limit*3; tries++ {
		pt := lo.Int64 + rand.Int63n(hi.Int64-lo.Int64+1)
		row := s.DB.QueryRow("SELECT "+itemCols+" FROM media m WHERE "+cond+" AND m.id>=? ORDER BY m.id LIMIT 1", pt)
		it, err := scanItem(row)
		if err == sql.ErrNoRows {
			continue
		}
		if err != nil {
			s.Log.Error("random", "err", err)
			writeErr(w, 500, "internal", err.Error())
			return
		}
		if seen[it.ID] {
			continue
		}
		seen[it.ID] = true
		items = append(items, it)
	}
	// 总数跟 history / search 的返回保持一个形状,手机端不用区分
	writeJSON(w, 200, map[string]any{"items": items})
}
