package api

import (
	"context"
	crand "crypto/rand"
	"database/sql"
	"encoding/binary"
	"hash/fnv"
	"math/rand"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 随机浏览(手机端「随机浏览」)。
//
// 数据怎么准备:
//   - 每个根目录(盘符)里媒体 id 的范围([Server.refreshRanges]):带条件的 MIN / MAX 要扫整张表,大库上要几秒甚至几十秒,所以启动时就算好,之后每 10 分钟刷新;
//   - 「随机序列」:[randSlots] 份固定的序列(槽位),每份 [seqLen] 个条目 id。范围(全部 / 每个根目录)× 类型(全部 / 图片 / 视频)× 槽位,
//     启动时就在后台全部预先生成好([Server.WarmRandom]),请求只是切片 + 按 id 取行,不做随机查找。序列由(范围、类型、槽位)确定性生成,
//     重启、重新生成后只要库里的文件没变就是同一份;只有这个范围的 id 区间变了(扫进了新文件)才会重新生成。
//   - 手机端「重新洗牌」= 换一个槽位;不点就一直用同一个槽位,序列不变。超过 [seqLen] 的批次实时按同样的规则确定性生成。
const (
	randSlots = 12  // 每个范围 × 类型预先准备几份序列
	seqLen    = 600 // 每份序列预先生成多少个条目(10 批 × 60)
)

type randomState struct {
	sync.Mutex
	ready  bool
	roots  map[int64][2]int64 // 根目录 id → [最小 id, 最大 id]
	all    [2]int64           // 全部根目录
	seqs   map[string]*seqEntry
	salt   int64 // 随机序列的盐:不同的盐 = 完全不同的一套序列;存在 kv 表里,重启后不变。「重新生成随机序列」时换新的
	saltOK bool
	kick   chan struct{} // 叫醒预热协程立刻重新生成
}

type seqEntry struct {
	lo, hi int64
	ids    []int64
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

// scope 把请求的根目录集合变成取点范围、缓存键和 SQL 条件。roots 为空 = 所有根目录。找不到任何一个根目录时 ok=false(结果为空)。
func (s *Server) scope(ctx context.Context, roots []int64) (lo, hi int64, key, cond string, ok bool, err error) {
	s.rnd.Lock()
	ready := s.rnd.ready
	s.rnd.Unlock()
	if !ready { // 预热还没完成(刚启动就有请求):自己算一次
		if err = s.refreshRanges(ctx); err != nil {
			return
		}
	}
	s.rnd.Lock()
	defer s.rnd.Unlock()
	if len(roots) == 0 {
		return s.rnd.all[0], s.rnd.all[1], "*", "", len(s.rnd.roots) > 0, nil
	}
	ids := make([]int64, 0, len(roots))
	found := false
	for _, id := range roots {
		ids = append(ids, id)
		if rg, has := s.rnd.roots[id]; has {
			if !found || rg[0] < lo {
				lo = rg[0]
			}
			if !found || rg[1] > hi {
				hi = rg[1]
			}
			found = true
		}
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	parts := make([]string, len(ids))
	for i, id := range ids {
		parts[i] = strconv.FormatInt(id, 10)
	}
	key = strings.Join(parts, ",")
	return lo, hi, key, " AND +m.root_id IN (" + key + ")", found, nil
}

func typesKey(types []int) string {
	t := append([]int(nil), types...)
	sort.Ints(t)
	parts := make([]string, len(t))
	for i, x := range t {
		parts[i] = strconv.Itoa(x)
	}
	return strings.Join(parts, ",")
}

// 条件列前面的 + 是"一元加":让 SQLite 不要选这些列上的索引(会按 type 取出全部再排序,几十万行很慢),固定沿着主键往后找。
func randCond(types []int) string {
	return "+m.type IN " + inList(types) + " AND +m.state<>2 AND m.size>0 AND +m.root_id IN (SELECT id FROM roots WHERE enabled=1)"
}

// seqRNG:(盐、范围、类型、槽位、批号)→ 确定性的随机数发生器。批号 -1 = 预先生成的整份序列。
func seqRNG(salt int64, scopeKey, tkey string, slot, batch int) *rand.Rand {
	h := fnv.New64a()
	h.Write([]byte(scopeKey + "|" + tkey))
	return rand.New(rand.NewSource(int64(h.Sum64() + uint64(salt) + uint64(slot)*0x9E3779B97F4A7C15 + uint64(batch+2)*0xBF58476D1CE4E5B9)))
}

// randomSalt 取当前的盐(第一次从 kv 表读,没有记录 = 0)。
func (s *Server) randomSalt() int64 {
	s.rnd.Lock()
	defer s.rnd.Unlock()
	if !s.rnd.saltOK {
		var v string
		if err := s.DB.QueryRow("SELECT v FROM kv WHERE k='random_salt'").Scan(&v); err == nil {
			s.rnd.salt, _ = strconv.ParseInt(v, 10, 64)
		}
		s.rnd.saltOK = true
	}
	return s.rnd.salt
}

// ResetRandom 清除所有历史随机序列:换一个新的盐(存进 kv 表)、清空内存里的序列并叫醒预热协程重新生成。
// 之后每份序列都和以前完全不同;手机端正在看的序列也会变(它们下次取数据时就是新序列)。
func (s *Server) ResetRandom() error {
	var b [8]byte
	if _, err := crand.Read(b[:]); err != nil {
		return err
	}
	salt := int64(binary.LittleEndian.Uint64(b[:]) >> 1)
	if _, err := s.DB.Exec("INSERT INTO kv(k,v) VALUES('random_salt',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", strconv.FormatInt(salt, 10)); err != nil {
		return err
	}
	s.rnd.Lock()
	s.rnd.salt, s.rnd.saltOK = salt, true
	s.rnd.seqs = map[string]*seqEntry{}
	if s.rnd.kick == nil {
		s.rnd.kick = make(chan struct{}, 1)
	}
	kick := s.rnd.kick
	s.rnd.Unlock()
	select {
	case kick <- struct{}{}:
	default:
	}
	return nil
}

func (s *Server) randomReset(w http.ResponseWriter, r *http.Request) {
	if err := s.ResetRandom(); err != nil {
		s.Log.Error("重新生成随机序列失败", "err", err)
		writeErr(w, 500, "internal", "重新生成失败")
		return
	}
	s.Log.Info("已清除所有随机序列,正在重新生成")
	writeJSON(w, 200, map[string]any{"ok": true})
}

// pickIDs 在 [lo, hi] 里随机取点,每个点取 id >= 该点的第一条符合条件的(主键索引查找,每条几十微秒到几毫秒;不用 ORDER BY RANDOM():几十万行要全表排序)。
// id 有空洞时空洞后面那一条被选中的概率偏大,对"随便看看"无所谓。rng 为 nil 用全局随机数。最多花 timeout,到点有多少返回多少。
func (s *Server) pickIDs(ctx context.Context, cond string, lo, hi int64, n int, rng *rand.Rand, timeout time.Duration) ([]int64, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	ids := make([]int64, 0, n)
	seen := map[int64]bool{}
	for tries := 0; len(ids) < n && tries < n*3 && ctx.Err() == nil; tries++ {
		var r int64
		if rng != nil {
			r = rng.Int63n(hi - lo + 1)
		} else {
			r = rand.Int63n(hi - lo + 1)
		}
		var id int64
		err := s.DB.QueryRowContext(ctx, "SELECT m.id FROM media m WHERE "+cond+" AND m.id>=? ORDER BY m.id LIMIT 1", lo+r).Scan(&id)
		if err == sql.ErrNoRows {
			continue
		}
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			return nil, err
		}
		if !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	return ids, nil
}

// sequence 返回(范围、类型、槽位)这一份预先生成的序列;还没生成(或这个范围的 id 区间变了)就现在生成并缓存。
func (s *Server) sequence(ctx context.Context, types []int, roots []int64, slot int) ([]int64, error) {
	lo, hi, skey, scond, ok, err := s.scope(ctx, roots)
	if err != nil || !ok {
		return nil, err
	}
	tkey := typesKey(types)
	salt := s.randomSalt()
	ckey := strconv.FormatInt(salt, 10) + "|" + skey + "|" + tkey + "|" + strconv.Itoa(slot)
	s.rnd.Lock()
	e := s.rnd.seqs[ckey]
	s.rnd.Unlock()
	if e != nil && e.lo == lo && e.hi == hi {
		return e.ids, nil
	}
	ids, err := s.pickIDs(ctx, randCond(types)+scond, lo, hi, seqLen, seqRNG(salt, skey, tkey, slot, -1), 60*time.Second)
	if err != nil {
		return nil, err
	}
	s.rnd.Lock()
	if s.rnd.seqs == nil {
		s.rnd.seqs = map[string]*seqEntry{}
	}
	if s.rnd.salt == salt { // 生成期间如果又重置了(盐变了),这份旧的不要存
		s.rnd.seqs[ckey] = &seqEntry{lo, hi, ids}
	}
	s.rnd.Unlock()
	return ids, nil
}

// WarmRandom 预热随机浏览:启动时在后台先算好各根目录的 id 范围,再把「全部」和每个根目录(盘符)× 三种类型(全部 / 图片 / 视频)× [randSlots] 份序列全部生成好,
// 之后每 10 分钟刷新范围并补齐变化了的序列。立即返回,在 ctx 结束时停止。
func (s *Server) WarmRandom(ctx context.Context) {
	s.rnd.Lock()
	if s.rnd.kick == nil {
		s.rnd.kick = make(chan struct{}, 1)
	}
	kick := s.rnd.kick
	s.rnd.Unlock()
	go func() {
		for {
			t0 := time.Now()
			if err := s.refreshRanges(ctx); err != nil {
				if ctx.Err() != nil {
					return
				}
				s.Log.Warn("随机浏览预热失败", "err", err)
			} else {
				s.rnd.Lock()
				scopes := [][]int64{nil}
				for id := range s.rnd.roots {
					scopes = append(scopes, []int64{id})
				}
				s.rnd.Unlock()
				n := 0
				for _, roots := range scopes {
					for _, ty := range [][]int{{0, 1, 2}, {0, 2}, {1}} { // 全部 / 图片(含 GIF) / 视频
						for slot := 0; slot < randSlots; slot++ {
							if ctx.Err() != nil {
								return
							}
							if _, err := s.sequence(ctx, ty, roots, slot); err != nil {
								s.Log.Warn("随机序列生成失败", "err", err)
							}
							n++
						}
					}
				}
				s.Log.Info("随机浏览已预热", "范围", len(scopes), "序列", n, "ms", time.Since(t0).Milliseconds())
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(10 * time.Minute):
			case <-kick: // 手机端点了「重新生成随机序列」
			}
		}
	}()
}

// itemsByID 按给定的 id 顺序取条目(已经不存在的跳过)。
func (s *Server) itemsByID(ctx context.Context, ids []int64) ([]Item, error) {
	items := make([]Item, 0, len(ids))
	if len(ids) == 0 {
		return items, nil
	}
	parts := make([]string, len(ids))
	for i, id := range ids {
		parts[i] = strconv.FormatInt(id, 10)
	}
	rows, err := s.DB.QueryContext(ctx, "SELECT "+itemCols+" FROM media m WHERE m.id IN ("+strings.Join(parts, ",")+")")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byID := map[string]Item{}
	for rows.Next() {
		it, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		byID[it.ID] = it
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, id := range ids {
		if it, ok := byID[sid(id)]; ok {
			items = append(items, it)
		}
	}
	return items, nil
}

// random 随机取一批媒体。
// 参数:types(同 history,默认全部)、limit(默认 60,最多 120)、roots(可选,逗号分隔的根目录 id:只在这些根目录里随机)、
// seed + batch(固定的随机序列:seed 决定用哪一份预先准备好的序列,同一个 seed 的第 batch 批永远是同一批,换 seed 才换序列;不带 seed = 每次都不同)。
func (s *Server) random(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	types, ok := parseTypes(q.Get("types"))
	if !ok {
		writeErr(w, 400, "bad.types", "types 无效")
		return
	}
	var roots []int64
	if v := q.Get("roots"); v != "" {
		for _, p := range strings.Split(v, ",") {
			id, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64)
			if err != nil {
				writeErr(w, 400, "bad.roots", "roots 无效")
				return
			}
			roots = append(roots, id)
		}
	}
	limit := limitParam(r, 60, 120)
	var ids []int64
	var err error
	if v := q.Get("seed"); v != "" {
		seed, e1 := strconv.ParseInt(v, 10, 64)
		batch, e2 := int64(0), error(nil)
		if b := q.Get("batch"); b != "" {
			batch, e2 = strconv.ParseInt(b, 10, 64)
		}
		if e1 != nil || e2 != nil || batch < 0 || batch > 1_000_000 {
			writeErr(w, 400, "bad.seed", "seed / batch 无效")
			return
		}
		slot := int(uint64(seed) % randSlots)
		from, to := int(batch)*limit, (int(batch)+1)*limit
		var seq []int64
		if seq, err = s.sequence(r.Context(), types, roots, slot); err == nil {
			switch {
			case to <= len(seq):
				ids = seq[from:to]
			case len(seq) >= seqLen: // 超出预先生成的长度:按同样的规则实时确定性生成这一批
				lo, hi, skey, scond, found, e := s.scope(r.Context(), roots)
				if err = e; err == nil && found {
					ids, err = s.pickIDs(r.Context(), randCond(types)+scond, lo, hi, limit, seqRNG(s.randomSalt(), skey, typesKey(types), slot, int(batch)), 4*time.Second)
				}
			case from < len(seq): // 库很小,序列本来就没有 seqLen 那么长
				ids = seq[from:]
			}
		}
	} else {
		lo, hi, _, scond, found, e := s.scope(r.Context(), roots)
		if err = e; err == nil && found {
			ids, err = s.pickIDs(r.Context(), randCond(types)+scond, lo, hi, limit, nil, 4*time.Second)
		}
	}
	var items []Item
	if err == nil {
		items, err = s.itemsByID(r.Context(), ids)
	}
	if err != nil {
		s.Log.Error("random", "err", err)
		writeErr(w, 500, "internal", "查询失败")
		return
	}
	writeJSON(w, 200, map[string]any{"items": items})
}
