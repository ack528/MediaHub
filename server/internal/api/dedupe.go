package api

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/bits"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"mediahub/internal/governor"
	"mediahub/internal/proc"
	"mediahub/internal/winfs"
)

// 重复文件检测(管理程序「存储优化 → 重复文件」)。三种重复:
//
//	exact  内容完全相同(大小相同 → 头 / 中 / 尾的快速指纹相同 → 全文件 SHA-256 相同),不管文件名、放在哪个文件夹
//	image  看起来一样的图片(缩放 / 重新压缩 / 加了水印以外的轻微差别):dHash 64 位感知哈希,汉明距离 ≤ 阈值,宽高比接近
//	video  看起来一样的视频(重新编码 / 转了分辨率):时长相近,并且 20% / 50% / 80% 三个位置的画面 dHash 都接近
//
// 感知哈希用 ffmpeg 取一帧缩到 9×8 灰度(自动处理方向和各种格式),指纹存在 media_hash 表里,文件没变就不会重算,所以第二次扫描很快。
// 这里只"找",不删:删除要在管理程序里选好、确认,而且是移进 .mediahub\trash(见 optimize_common.go)。

const (
	ddImageMaxDist = 10 // 图片阈值上限(界面可调 0 ~ 10,默认 5)
	ddVideoMaxDur  = 0.03
)

type dedupeStatus struct {
	State      string `json:"state"` // idle | running | done | error | cancelled
	Phase      string `json:"phase"`
	Done       int64  `json:"done"`
	Total      int64  `json:"total"`
	Message    string `json:"message,omitempty"`
	StartedAt  int64  `json:"startedAt"`
	FinishedAt int64  `json:"finishedAt"`
	Exact      int    `json:"exact"`
	Image      int    `json:"image"`
	Video      int    `json:"video"`
	Wasted     int64  `json:"wasted"` // 所有重复组里"可以删掉的多余副本"总字节数(按推荐保留的一个算)
}

type dedupeGroup struct {
	ID     int          `json:"id"`
	Kind   string       `json:"kind"`
	Wasted int64        `json:"wasted"`
	Items  []dedupeItem `json:"items"`
	ids    map[int64]bool
}

type dedupeItem struct {
	ID      int64  `json:"id"`
	Name    string `json:"name"`
	Dir     string `json:"dir"`
	Root    string `json:"root"`
	RootID  int64  `json:"rootId"`
	Type    string `json:"type"`
	Size    int64  `json:"size"`
	W       int    `json:"w"`
	H       int    `json:"h"`
	DurMs   int64  `json:"durMs"`
	Mtime   int64  `json:"mtime"`
	TakenAt int64  `json:"takenAt"`
	Codec   string `json:"codec,omitempty"`
	Keep    bool   `json:"keep"` // 推荐保留
}

type dedupeState struct {
	mu     sync.Mutex
	st     dedupeStatus
	cancel context.CancelFunc
	groups map[string][]*dedupeGroup // kind → 分组
	byID   map[int64]*dedupeGroup    // 媒体 id → 所在的组(用来在删除时保证每组至少留一个)
	dist   int
}

func (d *dedupeState) status() dedupeStatus {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.st
}

func (d *dedupeState) set(f func(*dedupeStatus)) {
	d.mu.Lock()
	f(&d.st)
	d.mu.Unlock()
}

// ---------------------------------------------------------------- 指纹

// quickHead:大小 + 头 / 中 / 尾各 64KB 的 SHA-256。大小相同、这个也相同的文件才值得算全文件哈希。
func quickHead(path string, size int64) (string, error) {
	f, err := winfs.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	var sz [8]byte
	binary.LittleEndian.PutUint64(sz[:], uint64(size))
	h.Write(sz[:])
	buf := make([]byte, 64<<10)
	for _, off := range []int64{0, size / 2, size - int64(len(buf))} {
		if off < 0 {
			off = 0
		}
		n, err := f.ReadAt(buf, off)
		if err != nil && err != io.EOF {
			return "", err
		}
		h.Write(buf[:n])
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func fullSHA(ctx context.Context, path string, gov *governor.Governor) (string, error) {
	f, err := winfs.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	buf := make([]byte, 1<<20)
	for {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		gov.YieldChunk(ctx) // 正在传输 / 播放时,读大文件每块让一下磁盘
		n, err := f.Read(buf)
		if n > 0 {
			h.Write(buf[:n])
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", err
		}
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// dHash64 用 ffmpeg 在 at 秒处取一帧(图片 at = 0 且不 seek),缩成 9×8 灰度,每行比较相邻像素得到 64 位。画面几乎是纯色时返回 ok=false。
func dHash64(ctx context.Context, ffmpeg, path string, at float64, seek bool) (uint64, bool, error) {
	args := []string{"-v", "error", "-nostdin"}
	if seek && at > 0 {
		args = append(args, "-ss", strconv.FormatFloat(at, 'f', 3, 64))
	}
	args = append(args, "-i", path, "-frames:v", "1", "-vf", "scale=9:8:flags=area,format=gray", "-f", "rawvideo", "-")
	cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	out, err := proc.Background(exec.CommandContext(cctx, ffmpeg, args...)).Output()
	if err != nil || len(out) < 72 {
		return 0, false, fmt.Errorf("取帧失败")
	}
	var h uint64
	minV, maxV := byte(255), byte(0)
	for y := 0; y < 8; y++ {
		for x := 0; x < 8; x++ {
			a, b := out[y*9+x], out[y*9+x+1]
			h <<= 1
			if a > b {
				h |= 1
			}
			if a < minV {
				minV = a
			}
			if a > maxV {
				maxV = a
			}
		}
	}
	return h, maxV-minV >= 6, nil // 对比度太低(纯黑 / 纯白 / 渐变不明显)的画面哈希不可靠,不参与匹配
}

func hamming(a, b uint64) int { return bits.OnesCount64(a ^ b) }

// ---------------------------------------------------------------- 扫描

func (s *Server) dedupeStart(roots []int64, thr int) error {
	d := &s.opt.dd
	d.mu.Lock()
	if d.st.State == "running" {
		d.mu.Unlock()
		return fmt.Errorf("已经有一个检测在运行")
	}
	ctx, cancel := context.WithCancel(context.Background())
	d.cancel = cancel
	if thr < 0 || thr > ddImageMaxDist {
		thr = 5
	}
	d.dist = thr
	d.st = dedupeStatus{State: "running", Phase: "准备", StartedAt: time.Now().Unix()}
	d.mu.Unlock()
	go func() {
		defer func() {
			if r := recover(); r != nil {
				d.set(func(st *dedupeStatus) { st.State, st.Message = "error", fmt.Sprint(r) })
			}
		}()
		err := s.dedupeRun(ctx, roots, thr)
		d.set(func(st *dedupeStatus) {
			st.FinishedAt = time.Now().Unix()
			switch {
			case err == context.Canceled:
				st.State, st.Message = "cancelled", "已取消"
			case err != nil:
				st.State, st.Message = "error", err.Error()
			default:
				st.State = "done"
			}
		})
	}()
	return nil
}

func rootFilterSQL(roots []int64) string {
	if len(roots) == 0 {
		return ""
	}
	parts := make([]string, len(roots))
	for i, r := range roots {
		parts[i] = strconv.FormatInt(r, 10)
	}
	return " AND m.root_id IN (" + strings.Join(parts, ",") + ")"
}

func (s *Server) dedupeRun(ctx context.Context, roots []int64, thr int) error {
	d := &s.opt.dd
	rf := rootFilterSQL(roots)
	base := ` AND m.state<>2 AND m.size>0 AND m.root_id IN (SELECT id FROM roots WHERE enabled=1)`
	workers := 3

	// ---- 阶段 1:精确重复(大小相同 → 快速指纹 → 全文件 SHA-256)
	d.set(func(st *dedupeStatus) { st.Phase, st.Done, st.Total = "精确重复:按大小筛选", 0, 0 })
	rows, err := s.DB.Query(mediaInfoSQL + ` WHERE m.type IN (0,1,2)` + base + rf + `
		AND m.size IN (SELECT size FROM media WHERE type IN (0,1,2) AND size>0 GROUP BY size HAVING COUNT(*)>1)`)
	if err != nil {
		return err
	}
	var cands []mediaInfo
	for rows.Next() {
		m, err := scanMediaInfo(rows)
		if err != nil {
			rows.Close()
			return err
		}
		cands = append(cands, m)
	}
	rows.Close()
	d.set(func(st *dedupeStatus) { st.Phase, st.Total = "精确重复:快速指纹", int64(len(cands)) })
	if err := s.parallel(ctx, workers, len(cands), func(i int) {
		m := cands[i]
		defer d.set(func(st *dedupeStatus) { st.Done++ })
		if h, ok := s.loadHash(m.ID, m.Size, m.Mtime); ok && h.head != "" {
			return
		}
		head, err := quickHead(m.full(), m.Size)
		if err != nil {
			return
		}
		s.saveHash(m, "head", head)
	}); err != nil {
		return err
	}
	// 快速指纹相同的才算全文件哈希
	rows, err = s.DB.Query(`SELECT h.media_id FROM media_hash h JOIN media m ON m.id=h.media_id
		WHERE h.head IS NOT NULL AND (h.sha IS NULL OR h.sha='') AND (h.size, h.head) IN
		(SELECT size, head FROM media_hash WHERE head IS NOT NULL GROUP BY size, head HAVING COUNT(*)>1)` + base + rf)
	if err != nil {
		return err
	}
	var needFull []int64
	for rows.Next() {
		var id int64
		_ = rows.Scan(&id)
		needFull = append(needFull, id)
	}
	rows.Close()
	d.set(func(st *dedupeStatus) {
		st.Phase, st.Done, st.Total = "精确重复:全文件校验", 0, int64(len(needFull))
	})
	if err := s.parallel(ctx, 2, len(needFull), func(i int) {
		defer d.set(func(st *dedupeStatus) { st.Done++ })
		m, err := s.mediaInfoByID(needFull[i])
		if err != nil {
			return
		}
		sha, err := fullSHA(ctx, m.full(), s.Gov)
		if err != nil {
			return
		}
		s.saveHash(m, "sha", sha)
	}); err != nil {
		return err
	}

	// ---- 阶段 2:图片感知哈希
	s.hashPerceptual(ctx, rf, base, 0, workers, "相似图片:计算指纹")
	if ctx.Err() != nil {
		return ctx.Err()
	}
	// ---- 阶段 3:视频感知哈希
	s.hashPerceptual(ctx, rf, base, 1, 2, "相似视频:计算指纹")
	if ctx.Err() != nil {
		return ctx.Err()
	}
	d.set(func(st *dedupeStatus) { st.Phase = "分组" })
	return s.dedupeBuild(roots, thr)
}

type hashRow struct{ head, sha, phash string }

func (s *Server) loadHash(id, size, mtime int64) (hashRow, bool) {
	var h hashRow
	var hd, sh, ph sql.NullString
	var sz, mt int64
	err := s.DB.QueryRow(`SELECT size, mtime, head, sha, phash FROM media_hash WHERE media_id=?`, id).Scan(&sz, &mt, &hd, &sh, &ph)
	if err != nil || sz != size || mt != mtime { // 文件变了:旧指纹作废
		return h, false
	}
	return hashRow{hd.String, sh.String, ph.String}, true
}

// saveHash 写一个指纹字段(head / sha / phash);文件大小或修改时间和库里不一致时先清掉旧指纹。
func (s *Server) saveHash(m mediaInfo, col, val string) {
	if _, ok := s.loadHash(m.ID, m.Size, m.Mtime); !ok {
		_, _ = s.DB.Exec(`DELETE FROM media_hash WHERE media_id=?`, m.ID)
	}
	_, _ = s.DB.Exec(`INSERT INTO media_hash(media_id, size, mtime, dur_ms, updated) VALUES(?,?,?,?,?) ON CONFLICT(media_id) DO NOTHING`,
		m.ID, m.Size, m.Mtime, m.DurMs, time.Now().Unix())
	_, _ = s.DB.Exec(`UPDATE media_hash SET `+col+`=?, updated=? WHERE media_id=?`, val, time.Now().Unix(), m.ID)
}

// hashPerceptual 给 type=typ(0 图片 / 1 视频)的所有文件算感知哈希(已经算过且文件没变的跳过)。
func (s *Server) hashPerceptual(ctx context.Context, rf, base string, typ, workers int, phase string) {
	d := &s.opt.dd
	rows, err := s.DB.Query(mediaInfoSQL+` WHERE m.type=?`+base+rf+` AND COALESCE(m.w,0)>=32 AND COALESCE(m.h,0)>=32`, typ)
	if err != nil {
		return
	}
	var all []mediaInfo
	for rows.Next() {
		m, err := scanMediaInfo(rows)
		if err == nil {
			all = append(all, m)
		}
	}
	rows.Close()
	var todo []mediaInfo
	for _, m := range all {
		if h, ok := s.loadHash(m.ID, m.Size, m.Mtime); ok && h.phash != "" {
			continue
		}
		todo = append(todo, m)
	}
	d.set(func(st *dedupeStatus) { st.Phase, st.Done, st.Total = phase, 0, int64(len(todo)) })
	_ = s.parallel(ctx, workers, len(todo), func(i int) {
		m := todo[i]
		defer d.set(func(st *dedupeStatus) { st.Done++ })
		path := m.full()
		if typ == 0 {
			h, ok, err := dHash64(ctx, s.Cfg.Tools.FFmpeg, path, 0, false)
			if err != nil {
				s.saveHash(m, "phash", "-") // 解码不了(RAW / 损坏):记个占位,下次不再重试
				return
			}
			if !ok {
				s.saveHash(m, "phash", "-")
				return
			}
			s.saveHash(m, "phash", fmt.Sprintf("%016x", h))
			return
		}
		dur := float64(m.DurMs) / 1000
		if dur <= 0 {
			if ms := probeDurationMs(s.Cfg.Tools.FFprobe, path); ms > 0 {
				dur = float64(ms) / 1000
				m.DurMs = ms
			}
		}
		if dur < 2 {
			s.saveHash(m, "phash", "-")
			return
		}
		var hs []string
		for _, f := range []float64{0.2, 0.5, 0.8} {
			h, _, err := dHash64(ctx, s.Cfg.Tools.FFmpeg, path, dur*f, true)
			if err != nil {
				s.saveHash(m, "phash", "-")
				return
			}
			hs = append(hs, fmt.Sprintf("%016x", h))
		}
		s.saveHash(m, "phash", strings.Join(hs, ","))
	})
}

// parallel 用 workers 个协程跑 n 个任务;ctx 取消后不再取新任务。
func (s *Server) parallel(ctx context.Context, workers, n int, fn func(i int)) error {
	var next atomic.Int64
	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { _ = recover() }()
			for ctx.Err() == nil {
				i := int(next.Add(1) - 1)
				if i >= n {
					return
				}
				t0 := time.Now()
				fn(i)
				s.Gov.Yield(ctx, time.Since(t0)) // 有客户端连着:每处理完一个文件让出时间(Active 约一半,Streaming 只用约 1/5)
			}
		}()
	}
	wg.Wait()
	return ctx.Err()
}

// ---------------------------------------------------------------- 分组

type dsu []int

func newDSU(n int) dsu {
	d := make(dsu, n)
	for i := range d {
		d[i] = i
	}
	return d
}
func (d dsu) find(x int) int {
	for d[x] != x {
		d[x] = d[d[x]]
		x = d[x]
	}
	return x
}
func (d dsu) union(a, b int) { d[d.find(a)] = d.find(b) }

// dedupeBuild 用库里的指纹重新计算三种分组(扫描结束时调用;删除文件后也会重新算)。
func (s *Server) dedupeBuild(roots []int64, thr int) error {
	d := &s.opt.dd
	rf := rootFilterSQL(roots)
	base := ` AND m.state<>2 AND m.size>0 AND m.root_id IN (SELECT id FROM roots WHERE enabled=1)`
	groups := map[string][]*dedupeGroup{}
	byID := map[int64]*dedupeGroup{}
	var wasted int64
	next := 1
	add := func(kind string, members []mediaInfo) {
		if len(members) < 2 {
			return
		}
		// 推荐保留:分辨率最高 → 文件最大 → 修改时间最早
		sort.SliceStable(members, func(i, j int) bool {
			a, b := members[i], members[j]
			if pa, pb := a.W*a.H, b.W*b.H; pa != pb {
				return pa > pb
			}
			if a.Size != b.Size {
				return a.Size > b.Size
			}
			return a.Mtime < b.Mtime
		})
		g := &dedupeGroup{ID: next, Kind: kind, ids: map[int64]bool{}}
		next++
		for i, m := range members {
			it := dedupeItem{ID: m.ID, Name: m.Name, Dir: m.DirRel, Root: m.RootName, RootID: m.RootID, Size: m.Size, W: m.W, H: m.H, DurMs: m.DurMs,
				Mtime: m.Mtime, TakenAt: m.TakenAt, Codec: m.Vcodec, Keep: i == 0}
			switch m.Type {
			case 1:
				it.Type = "video"
			case 2:
				it.Type = "gif"
			default:
				it.Type = "photo"
			}
			g.Items = append(g.Items, it)
			g.ids[m.ID] = true
			if i > 0 {
				g.Wasted += m.Size
			}
		}
		wasted += g.Wasted
		groups[kind] = append(groups[kind], g)
		for id := range g.ids {
			if _, dup := byID[id]; !dup {
				byID[id] = g
			}
		}
	}
	load := func(ids []int64) []mediaInfo {
		var out []mediaInfo
		for _, id := range ids {
			if m, err := s.mediaInfoByID(id); err == nil {
				out = append(out, m)
			}
		}
		return out
	}

	// exact
	rows, err := s.DB.Query(`SELECT h.sha, GROUP_CONCAT(h.media_id) FROM media_hash h JOIN media m ON m.id=h.media_id
		WHERE h.sha IS NOT NULL AND h.sha<>'' AND h.size=m.size AND h.mtime=m.mtime` + base + rf + ` GROUP BY h.sha HAVING COUNT(*)>1`)
	if err != nil {
		return err
	}
	exactOf := map[int64]string{}
	for rows.Next() {
		var sha, ids string
		if rows.Scan(&sha, &ids) != nil {
			continue
		}
		var idl []int64
		for _, p := range strings.Split(ids, ",") {
			if v, err := strconv.ParseInt(p, 10, 64); err == nil {
				idl = append(idl, v)
				exactOf[v] = sha
			}
		}
		add("exact", load(idl))
	}
	rows.Close()

	// image / video:加载感知哈希
	type ph struct {
		info mediaInfo
		h    [3]uint64
		n    int
	}
	loadPH := func(typ int) []ph {
		rs, err := s.DB.Query(`SELECT h.media_id, h.phash FROM media_hash h JOIN media m ON m.id=h.media_id
			WHERE m.type=? AND h.phash IS NOT NULL AND h.phash NOT IN ('','-') AND h.size=m.size AND h.mtime=m.mtime`+base+rf, typ)
		if err != nil {
			return nil
		}
		defer rs.Close()
		var out []ph
		for rs.Next() {
			var id int64
			var p string
			if rs.Scan(&id, &p) != nil {
				continue
			}
			m, err := s.mediaInfoByID(id)
			if err != nil {
				continue
			}
			x := ph{info: m}
			for _, part := range strings.Split(p, ",") {
				if x.n < 3 {
					v, err := strconv.ParseUint(part, 16, 64)
					if err != nil {
						x.n = -1
						break
					}
					x.h[x.n] = v
					x.n++
				}
			}
			if x.n > 0 {
				out = append(out, x)
			}
		}
		return out
	}
	sameExact := func(a, b int64) bool { sa, ok := exactOf[a]; return ok && sa == exactOf[b] }
	aspectClose := func(a, b mediaInfo) bool {
		if a.W <= 0 || a.H <= 0 || b.W <= 0 || b.H <= 0 {
			return true
		}
		ra, rb := float64(a.W)/float64(a.H), float64(b.W)/float64(b.H)
		return ra/rb > 0.96 && ra/rb < 1.04
	}
	collect := func(kind string, items []ph, link func(i, j int) bool, candidates func(emit func(i, j int))) {
		du := newDSU(len(items))
		candidates(func(i, j int) {
			if link(i, j) {
				du.union(i, j)
			}
		})
		comp := map[int][]int{}
		for i := range items {
			comp[du.find(i)] = append(comp[du.find(i)], i)
		}
		for _, mem := range comp {
			if len(mem) < 2 {
				continue
			}
			allSame := true // 组里全是同一个精确重复组的成员:已经在"完全相同"里了
			for _, i := range mem[1:] {
				if !sameExact(items[mem[0]].info.ID, items[i].info.ID) {
					allSame = false
					break
				}
			}
			if allSame {
				continue
			}
			ms := make([]mediaInfo, len(mem))
			for k, i := range mem {
				ms[k] = items[i].info
			}
			add(kind, ms)
		}
	}
	// 图片:多索引哈希(把 64 位切成 thr+1 段,汉明距离 ≤ thr 的两个哈希至少有一段完全相同)找候选,再精确比较
	imgs := loadPH(0)
	collect("image", imgs, func(i, j int) bool {
		return hamming(imgs[i].h[0], imgs[j].h[0]) <= thr && aspectClose(imgs[i].info, imgs[j].info)
	}, func(emit func(i, j int)) {
		chunks := thr + 1
		for c := 0; c < chunks; c++ {
			lo, hi := c*64/chunks, (c+1)*64/chunks
			mask := (uint64(1)<<(hi-lo) - 1) << lo
			buckets := map[uint64][]int{}
			for i, x := range imgs {
				k := x.h[0] & mask
				buckets[k] = append(buckets[k], i)
			}
			for _, b := range buckets {
				if len(b) > 400 { // 一个桶特别大(大量相似的纯色 / 截图):只和相邻的比,避免 O(n²)
					for a := 0; a+1 < len(b); a++ {
						for z := a + 1; z < len(b) && z < a+40; z++ {
							emit(b[a], b[z])
						}
					}
					continue
				}
				for a := 0; a < len(b); a++ {
					for z := a + 1; z < len(b); z++ {
						emit(b[a], b[z])
					}
				}
			}
		}
	})
	// 视频:按时长排序,只比较时长相近的(±3%);三个位置的画面哈希都要接近
	vids := loadPH(1)
	sort.Slice(vids, func(i, j int) bool { return vids[i].info.DurMs < vids[j].info.DurMs })
	vthr := thr + 3
	collect("video", vids, func(i, j int) bool {
		a, b := vids[i], vids[j]
		if a.n != 3 || b.n != 3 {
			return false
		}
		for k := 0; k < 3; k++ {
			if hamming(a.h[k], b.h[k]) > vthr {
				return false
			}
		}
		return aspectClose(a.info, b.info)
	}, func(emit func(i, j int)) {
		for i := range vids {
			for j := i + 1; j < len(vids); j++ {
				da, db := float64(vids[i].info.DurMs), float64(vids[j].info.DurMs)
				if db-da > da*ddVideoMaxDur+1500 {
					break
				}
				emit(i, j)
			}
		}
	})

	for _, g := range groups {
		sort.Slice(g, func(i, j int) bool { return g[i].Wasted > g[j].Wasted })
	}
	d.mu.Lock()
	d.groups, d.byID, d.dist = groups, byID, thr
	d.st.Exact, d.st.Image, d.st.Video, d.st.Wasted = len(groups["exact"]), len(groups["image"]), len(groups["video"]), wasted
	d.mu.Unlock()
	return nil
}

// ---------------------------------------------------------------- 接口

func (s *Server) adminDedupeScan(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Roots     []int64 `json:"roots"`
		Threshold int     `json:"threshold"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in)
	if in.Threshold == 0 {
		in.Threshold = 5
	}
	if err := s.dedupeStart(in.Roots, in.Threshold); err != nil {
		writeErr(w, 409, "dedupe.running", err.Error())
		return
	}
	w.WriteHeader(202)
}

func (s *Server) adminDedupeStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.opt.dd.status())
}

func (s *Server) adminDedupeCancel(w http.ResponseWriter, r *http.Request) {
	d := &s.opt.dd
	d.mu.Lock()
	if d.cancel != nil {
		d.cancel()
	}
	d.mu.Unlock()
	w.WriteHeader(204)
}

func (s *Server) adminDedupeGroups(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	kind := q.Get("kind")
	off, _ := strconv.Atoi(q.Get("offset"))
	lim := limitParam(r, 20, 100)
	d := &s.opt.dd
	d.mu.Lock()
	all := d.groups[kind]
	d.mu.Unlock()
	if off < 0 || off > len(all) {
		off = len(all)
	}
	end := off + lim
	if end > len(all) {
		end = len(all)
	}
	writeJSON(w, 200, map[string]any{"total": len(all), "groups": all[off:end]})
}

// adminDedupeTrash POST /api/v1/admin/dedupe/trash {ids:[媒体 id]} —— 把选中的文件移进回收区。
// 每个重复组至少要留下一个文件(整组都选中会被拒绝),避免把一份内容全删光。
func (s *Server) adminDedupeTrash(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IDs []int64 `json:"ids"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in); err != nil || len(in.IDs) == 0 {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	d := &s.opt.dd
	d.mu.Lock()
	sel := map[int64]bool{}
	for _, id := range in.IDs {
		sel[id] = true
	}
	seen := map[*dedupeGroup]bool{}
	for _, id := range in.IDs {
		g := d.byID[id]
		if g == nil || seen[g] {
			continue
		}
		seen[g] = true
		left := 0
		for mid := range g.ids {
			if !sel[mid] {
				left++
			}
		}
		if left == 0 {
			d.mu.Unlock()
			writeErr(w, 400, "dedupe.keepOne", "有一组被整组选中了:每组至少要保留一个文件")
			return
		}
	}
	d.mu.Unlock()
	batch := "dedupe-" + time.Now().Format("20060102-150405")
	var moved []int64
	var freed int64
	var failed []string
	roots := map[int64]bool{}
	for _, id := range in.IDs {
		m, err := s.mediaInfoByID(id)
		if err != nil {
			failed = append(failed, fmt.Sprintf("%d: 不在索引里", id))
			continue
		}
		if err := moveToTrash(m, batch, "重复文件"); err != nil {
			failed = append(failed, m.Name+": "+err.Error())
			continue
		}
		moved = append(moved, id)
		freed += m.Size
		roots[m.RootID] = true
	}
	s.forgetMedia(moved...)
	if len(moved) > 0 {
		s.Log.Info("重复文件已移入回收区", "n", len(moved), "bytes", freed, "batch", batch)
		for rid := range roots {
			if s.Rescan != nil {
				s.Rescan(rid)
			}
		}
		_ = s.dedupeBuild(nil, s.opt.dd.dist)
	}
	writeJSON(w, 200, map[string]any{"moved": len(moved), "freed": freed, "failed": failed, "batch": batch})
}

// ---------------------------------------------------------------- 回收区接口

func (s *Server) adminTrashList(w http.ResponseWriter, r *http.Request) {
	rows, err := s.DB.Query(`SELECT id, path, COALESCE(label,'') FROM roots WHERE enabled=1`)
	if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	defer rows.Close()
	type batchInfo struct {
		RootID  int64  `json:"rootId"`
		Root    string `json:"root"`
		Batch   string `json:"batch"`
		Reason  string `json:"reason"`
		Files   int    `json:"files"`
		Bytes   int64  `json:"bytes"`
		Created int64  `json:"created"`
	}
	out := []batchInfo{}
	for rows.Next() {
		var id int64
		var p, label string
		if rows.Scan(&id, &p, &label) != nil {
			continue
		}
		ents, err := os.ReadDir(winfs.Path(trashRoot(p)))
		if err != nil {
			continue
		}
		for _, e := range ents {
			if !e.IsDir() {
				continue
			}
			bytes, files := dirSize(filepath.Join(trashRoot(p), e.Name()))
			bi := batchInfo{RootID: id, Root: label, Batch: e.Name(), Bytes: bytes, Files: files}
			if bi.Root == "" {
				bi.Root = filepath.VolumeName(p)
			}
			if b, err := os.ReadFile(winfs.Path(filepath.Join(trashRoot(p), e.Name(), "manifest.json"))); err == nil {
				var mf trashManifest
				if json.Unmarshal(b, &mf) == nil {
					bi.Reason, bi.Created = mf.Reason, mf.Created
					bi.Files-- // manifest.json 自己不算
				}
			}
			out = append(out, bi)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Batch > out[j].Batch })
	writeJSON(w, 200, map[string]any{"batches": out})
}

type trashReq struct {
	RootID  int64    `json:"rootId"`
	Batches []string `json:"batches"`
}

func validBatch(b string) bool {
	return b != "" && !strings.ContainsAny(b, `/\:`) && b != "." && b != ".."
}

// adminTrashRestore 把批次里的文件还原到原来的位置(原位置已经有同名文件的跳过)。
func (s *Server) adminTrashRestore(w http.ResponseWriter, r *http.Request) {
	var in trashReq
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in) != nil {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	root, err := rootPathByID(s.DB, in.RootID)
	if err != nil {
		writeErr(w, 404, "notfound", "根目录不存在")
		return
	}
	restored, skipped := 0, 0
	for _, b := range in.Batches {
		if !validBatch(b) {
			continue
		}
		dir := filepath.Join(trashRoot(root), b)
		raw, err := os.ReadFile(winfs.Path(filepath.Join(dir, "manifest.json")))
		if err != nil {
			continue
		}
		var mf trashManifest
		if json.Unmarshal(raw, &mf) != nil {
			continue
		}
		for _, f := range mf.Files {
			from := filepath.Join(dir, f.Rel)
			if underTrash(root, from) != nil {
				continue
			}
			to := filepath.Join(root, f.Rel)
			if _, err := os.Stat(winfs.Path(to)); err == nil {
				skipped++
				continue
			}
			if os.MkdirAll(winfs.Path(filepath.Dir(to)), 0o755) != nil || os.Rename(winfs.Path(from), winfs.Path(to)) != nil {
				skipped++
				continue
			}
			restored++
		}
		_ = os.RemoveAll(winfs.Path(dir)) // 剩下的只有 manifest.json 和空文件夹
	}
	if restored > 0 && s.Rescan != nil {
		s.Rescan(in.RootID)
	}
	writeJSON(w, 200, map[string]any{"restored": restored, "skipped": skipped})
}

// adminTrashPurge 永久删除回收区里的批次(释放空间)。这是唯一会真正删除媒体文件的地方,只有管理程序里明确确认后才会调用。
func (s *Server) adminTrashPurge(w http.ResponseWriter, r *http.Request) {
	var in trashReq
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in) != nil {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	root, err := rootPathByID(s.DB, in.RootID)
	if err != nil {
		writeErr(w, 404, "notfound", "根目录不存在")
		return
	}
	var freed int64
	n := 0
	for _, b := range in.Batches {
		if !validBatch(b) {
			continue
		}
		dir := filepath.Join(trashRoot(root), b)
		if underTrash(root, dir) != nil {
			continue
		}
		sz, _ := dirSize(dir)
		if os.RemoveAll(winfs.Path(dir)) == nil {
			freed += sz
			n++
		}
	}
	s.Log.Info("回收区已清空", "batches", n, "freed", freed)
	writeJSON(w, 200, map[string]any{"purged": n, "freed": freed})
}
