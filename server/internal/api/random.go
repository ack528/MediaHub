package api

import (
	"context"
	"database/sql"
	"math/rand"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 每个根目录(盘符)里媒体 id 的范围。带条件的 MIN / MAX 要扫整张表(或这个根目录的全部行),大库上要几秒甚至几十秒,
// 所以服务端启动时就在后台算好([Server.WarmRandom]),之后每 10 分钟刷新一次;请求只查这张表,不碰数据库。
type randomState struct {
	sync.Mutex
	ready bool
	roots map[int64][2]int64 // 根目录 id → [最小 id, 最大 id]
	all   [2]int64           // 全部根目录
}

// refreshRanges 重新算一遍各根目录的 id 范围。
func (s *Server) refreshRanges(ctx context.Context) error {
	rows, err := s.DB.QueryContext(ctx, "SELECT root_id, MIN(id), MAX(id) FROM media GROUP BY root_id")
	if err != nil {
		return err
	}
	defer rows.Close()
	roots := map[int64][2]int64{}
	var all [2]int64
	first := true
	for rows.Next() {
		var id, lo, hi int64
		if err := rows.Scan(&id, &lo, &hi); err != nil {
			return err
		}
		roots[id] = [2]int64{lo, hi}
		if first || lo < all[0] {
			all[0] = lo
		}
		if first || hi > all[1] {
			all[1] = hi
		}
		first = false
	}
	if err := rows.Err(); err != nil {
		return err
	}
	s.rnd.Lock()
	s.rnd.roots, s.rnd.all, s.rnd.ready = roots, all, true
	s.rnd.Unlock()
	return nil
}

// WarmRandom 预热随机浏览:启动时在后台先把各根目录的 id 范围算好,再各取一批随机条目把数据库页读进缓存(第一次请求就不用等冷读盘),
// 之后每 10 分钟刷新范围(扫描进来的新文件才能被随机到)。立即返回,在 ctx 结束时停止。
func (s *Server) WarmRandom(ctx context.Context) {
	go func() {
		for {
			t0 := time.Now()
			wctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
			if err := s.refreshRanges(wctx); err != nil {
				if ctx.Err() != nil {
					cancel()
					return
				}
				s.Log.Warn("随机浏览预热失败", "err", err)
			} else {
				s.rnd.Lock()
				n := len(s.rnd.roots)
				s.rnd.Unlock()
				for _, ty := range [][]int{{0, 1}, {2}} { // 图片 + GIF、视频
					for i := 0; i < 2; i++ {
						s.pickRandom(wctx, ty, nil, 60)
					}
				}
				s.Log.Info("随机浏览已预热", "roots", n, "ms", time.Since(t0).Milliseconds())
			}
			cancel()
			select {
			case <-ctx.Done():
				return
			case <-time.After(10 * time.Minute):
			}
		}
	}()
}

// pickRandom 在 [最小 id, 最大 id] 里随机取点,每个点取 id >= 该点的第一条符合条件的:每条都是一次主键索引查找,取 60 条也只要几毫秒
// (不用 ORDER BY RANDOM():几十万行要全表排序)。id 有空洞时空洞后面的那一条被选中的概率偏大,对"随便看看"无所谓。
// roots 为空 = 所有根目录。最多花 4 秒,到点有多少返回多少。已损坏的文件和空文件不会出现。
func (s *Server) pickRandom(ctx context.Context, types []int, roots []int64, limit int) ([]Item, error) {
	// 条件列前面的 + 是"一元加":让 SQLite 不要选这些列上的索引(会按 type 取出全部再排序,几十万行很慢),固定沿着主键往后找
	cond := "+m.type IN " + inList(types) + " AND +m.state<>2 AND m.size>0 AND +m.root_id IN (SELECT id FROM roots WHERE enabled=1)"
	s.rnd.Lock()
	ready, all, per := s.rnd.ready, s.rnd.all, s.rnd.roots
	s.rnd.Unlock()
	if !ready { // 预热还没完成(刚启动就有请求):自己算一次
		if err := s.refreshRanges(ctx); err != nil {
			return nil, err
		}
		s.rnd.Lock()
		all, per = s.rnd.all, s.rnd.roots
		s.rnd.Unlock()
	}
	lo, hi := all[0], all[1]
	if len(roots) > 0 {
		ids := make([]string, len(roots))
		found := false
		for i, id := range roots {
			ids[i] = strconv.FormatInt(id, 10)
			if rg, ok := per[id]; ok {
				if !found || rg[0] < lo {
					lo = rg[0]
				}
				if !found || rg[1] > hi {
					hi = rg[1]
				}
				found = true
			}
		}
		if !found {
			return []Item{}, nil
		}
		cond += " AND +m.root_id IN (" + strings.Join(ids, ",") + ")"
	} else if len(per) == 0 {
		return []Item{}, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	items := []Item{}
	seen := map[string]bool{}
	for tries := 0; len(items) < limit && tries < limit*3 && ctx.Err() == nil; tries++ {
		pt := lo + rand.Int63n(hi-lo+1)
		it, err := scanItem(s.DB.QueryRowContext(ctx, "SELECT "+itemCols+" FROM media m WHERE "+cond+" AND m.id>=? ORDER BY m.id LIMIT 1", pt))
		if err == sql.ErrNoRows {
			continue
		}
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			return nil, err
		}
		if !seen[it.ID] {
			seen[it.ID] = true
			items = append(items, it)
		}
	}
	return items, nil
}

// random 随机取一批媒体(手机端「随机浏览」)。
// 参数:types(同 history,默认全部)、limit(默认 60,最多 120)、roots(可选,逗号分隔的根目录 id:只在这些根目录里随机,手机端「每个盘符各自随机」用)。
func (s *Server) random(w http.ResponseWriter, r *http.Request) {
	types, ok := parseTypes(r.URL.Query().Get("types"))
	if !ok {
		writeErr(w, 400, "bad.types", "types 无效")
		return
	}
	var roots []int64
	if v := r.URL.Query().Get("roots"); v != "" {
		for _, p := range strings.Split(v, ",") {
			id, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64)
			if err != nil {
				writeErr(w, 400, "bad.roots", "roots 无效")
				return
			}
			roots = append(roots, id)
		}
	}
	items, err := s.pickRandom(r.Context(), types, roots, limitParam(r, 60, 120))
	if err != nil {
		s.Log.Error("random", "err", err)
		writeErr(w, 500, "internal", "查询失败")
		return
	}
	writeJSON(w, 200, map[string]any{"items": items})
}
