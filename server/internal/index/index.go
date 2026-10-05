// Package index 负责扫描媒体根目录,把"文件夹=对话、文件=消息"写进 SQLite。
//
// 分组规则:根目录通常选盘符,盘符下的每个"母文件夹"是一个对话(群组),名称就是文件夹名;
// 母文件夹里无论嵌套多少层子文件夹都不再单独成组(也不显示子文件夹名),其中所有媒体平铺进这个群组;
// 盘符根目录下直接放的媒体归入以盘符命名的群组。
//
// 扫描可续扫:每个母文件夹扫完后记下"已扫完的扫描代号"。扫到一半被关闭,下次启动接着扫(跳过已扫完的母文件夹),
// 而不是从头再来;扫描完整结束后,启动时在设定时限内不再重复扫描(见 ScanDue)。
//
// 三个阶段(见 docs/02-服务端设计.md §3.2):
//  1. ScanRoot:文件系统级字段(名称/大小/时间/文件 ID),完成后对话列表即可使用;
//  2. Enrich:  元数据(照片 EXIF 时间与尺寸,视频 ffprobe);
//  3. Finalize:统计各对话的数量、最新条目与版本号。
package index

import (
	"context"
	"database/sql"
	"fmt"
	"hash/fnv"
	"log/slog"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"mediahub/internal/classify"
	"mediahub/internal/config"
	"mediahub/internal/logx"
	"mediahub/internal/natsort"
	"mediahub/internal/winfs"
)

type Root struct {
	ID     int64
	Path   string
	Label  string
	Serial uint32
	// Reused:这个根目录以前扫描过(曾经添加过、后来从配置里移除又加回来),已有索引数据可直接使用,启动时不必重新扫描
	Reused bool
}

// Progress 是某个根目录当前的索引进度(JSON 供管理程序显示)。
type Progress struct {
	RootID   int64     `json:"rootId"`
	Label    string    `json:"label"`
	State    string    `json:"state"` // idle | scanning | enriching | error
	Dirs     int64     `json:"dirs"`
	Files    int64     `json:"files"`
	Enriched int64     `json:"enriched"`
	Errors   int64     `json:"errors"`
	Started  time.Time `json:"started"`
	Finished time.Time `json:"finished"`
	Message  string    `json:"message,omitempty"`

	// 实时速度:每秒采样一次的指数平滑值
	// Skipped:因无权限 / 目录已消失而跳过的目录数(属于正常现象,不算错误);FailedDirs:最近的跳过 / 失败明细
	Skipped    int64        `json:"skipped"`
	FailedDirs []DirFailure `json:"failedDirs,omitempty"`

	ScanRate    float64 `json:"scanRate"`    // 扫描速度,文件/秒
	EnrichRate  float64 `json:"enrichRate"`  // 元数据读取速度,个/秒
	EnrichTotal int64   `json:"enrichTotal"` // 本轮需要读取元数据的总数
	ETASec      int64   `json:"etaSec"`      // 预计剩余秒数(元数据阶段),未知为 0
	Resumed     bool    `json:"resumed"`     // 本次是接着上次被中断的扫描继续

	// 采样用的内部状态
	lastFiles, lastEnriched int64
	lastT                   time.Time
}

// DirFailure 一个读取失败(或被跳过)的目录及原因。
type DirFailure struct {
	Path   string `json:"path"`
	Kind   string `json:"kind"` // denied | gone | offline | io | other
	Code   int    `json:"code,omitempty"`
	Reason string `json:"reason"`
}

// 无论配置怎么写,这些系统保护目录总是跳过(读它们只会得到"没有权限")。
var builtinExclude = []string{
	"system volume information", "$recycle.bin", "recovery", "config.msi", "$winreagent", "$sysreset",
	"$windows.~bt", "$windows.~ws", "windows.old", "found.000", "msocache", ".mediahub",
}

type Indexer struct {
	DB  *sql.DB
	Cfg *config.Config
	Log *slog.Logger

	mu       sync.Mutex
	progress map[int64]*Progress
	exclude  map[string]bool
}

func New(db *sql.DB, cfg *config.Config, log *slog.Logger) *Indexer {
	ex := map[string]bool{}
	for _, n := range builtinExclude {
		ex[n] = true
	}
	for _, n := range cfg.Exclude {
		ex[strings.ToLower(n)] = true
	}
	ix := &Indexer{DB: db, Cfg: cfg, Log: log, progress: map[int64]*Progress{}, exclude: ex}
	logx.Go("扫描速度采样", ix.sampler)
	return ix
}

// sampler 每秒计算一次扫描 / 元数据读取速度。
func (ix *Indexer) sampler() {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for now := range t.C {
		ix.mu.Lock()
		for _, p := range ix.progress {
			files := atomic.LoadInt64(&p.Files)
			enr := atomic.LoadInt64(&p.Enriched)
			if !p.lastT.IsZero() {
				dt := now.Sub(p.lastT).Seconds()
				if dt > 0 {
					scan, enrich := 0.0, 0.0
					if p.State == "scanning" {
						scan = float64(files-p.lastFiles) / dt
					}
					if p.State == "enriching" {
						enrich = float64(enr-p.lastEnriched) / dt
					}
					p.ScanRate = smooth(p.ScanRate, scan)
					p.EnrichRate = smooth(p.EnrichRate, enrich)
				}
			}
			p.lastFiles, p.lastEnriched, p.lastT = files, enr, now
			p.ETASec = 0
			if p.State == "enriching" && p.EnrichRate > 0.01 {
				if left := atomic.LoadInt64(&p.EnrichTotal) - enr; left > 0 {
					p.ETASec = int64(float64(left) / p.EnrichRate)
				}
			}
		}
		ix.mu.Unlock()
	}
}

func smooth(old, cur float64) float64 {
	if cur == 0 && old < 0.5 {
		return 0
	}
	return old*0.6 + cur*0.4
}

func (ix *Indexer) Progress() []Progress {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	out := make([]Progress, 0, len(ix.progress))
	for _, p := range ix.progress {
		out = append(out, Progress{
			RootID: p.RootID, Label: p.Label, State: p.State,
			Dirs: atomic.LoadInt64(&p.Dirs), Files: atomic.LoadInt64(&p.Files),
			Enriched: atomic.LoadInt64(&p.Enriched), Errors: atomic.LoadInt64(&p.Errors),
			Started: p.Started, Finished: p.Finished, Message: p.Message,
			Skipped: atomic.LoadInt64(&p.Skipped), FailedDirs: append([]DirFailure(nil), p.FailedDirs...),
			ScanRate: p.ScanRate, EnrichRate: p.EnrichRate, EnrichTotal: atomic.LoadInt64(&p.EnrichTotal),
			ETASec: p.ETASec, Resumed: p.Resumed,
		})
	}
	return out
}

func (ix *Indexer) prog(r Root) *Progress {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	p := ix.progress[r.ID]
	if p == nil {
		p = &Progress{RootID: r.ID, Label: r.Label, State: "idle"}
		ix.progress[r.ID] = p
	}
	return p
}

func (ix *Indexer) setState(p *Progress, s, msg string) {
	ix.mu.Lock()
	p.State, p.Message = s, msg
	ix.mu.Unlock()
}

// SyncRoots 把配置里的根目录登记到数据库并返回。
// 不在配置里的根目录只是"停用"(enabled=0,手机上不再显示),它的索引数据保留;再次添加时直接复用,不必重新扫描。
func (ix *Indexer) SyncRoots() ([]Root, error) {
	var out []Root
	prev := map[string]bool{} // 路径(小写) → 之前是否处于启用状态
	if rows, err := ix.DB.Query(`SELECT path, enabled FROM roots`); err == nil {
		for rows.Next() {
			var p string
			var en int
			if rows.Scan(&p, &en) == nil {
				prev[strings.ToLower(p)] = en == 1
			}
		}
		rows.Close()
	}
	if _, err := ix.DB.Exec(`UPDATE roots SET enabled=0`); err != nil {
		return nil, err
	}
	for _, rc := range ix.Cfg.Roots {
		path := filepath.Clean(rc.Path)
		if !strings.HasSuffix(path, `\`) && len(path) == 2 && path[1] == ':' {
			path += `\`
		}
		serial, guid, err := winfs.VolumeInfo(path)
		if err != nil {
			ix.Log.Warn("根目录不可用,跳过", "path", path, "err", err)
			continue
		}
		// 已有记录(路径不区分大小写):盘符没变就复用;盘换了(卷序列号不同)文件 ID 全变,旧数据作废,当新的扫
		var id, oldSerial int64
		var state string
		err = ix.DB.QueryRow(`SELECT id, volume_serial, scan_state FROM roots WHERE path=? COLLATE NOCASE`, path).Scan(&id, &oldSerial, &state)
		reused := false
		switch {
		case err == nil && oldSerial == int64(serial):
			_, err = ix.DB.Exec(`UPDATE roots SET path=?, label=?, volume_guid=?, enabled=1 WHERE id=?`, path, rc.Label, guid, id)
			reused = !prev[strings.ToLower(path)] && state == "complete" // 重新添加:之前停用过且扫描完整
		case err == nil:
			ix.Log.Warn("根目录所在的磁盘已更换(卷序列号变化),旧索引作废,重新扫描", "path", path)
			_, err = ix.DB.Exec(`UPDATE roots SET label=?, volume_serial=?, volume_guid=?, enabled=1, scan_state='', scan_gen=0, last_scan=NULL WHERE id=?`,
				rc.Label, int64(serial), guid, id)
		default:
			err = ix.DB.QueryRow(`INSERT INTO roots(path,label,volume_serial,volume_guid,enabled) VALUES(?,?,?,?,1) RETURNING id`,
				path, rc.Label, int64(serial), guid).Scan(&id)
		}
		if err != nil {
			return nil, err
		}
		out = append(out, Root{ID: id, Path: path, Label: rc.Label, Serial: serial, Reused: reused})
	}
	return out, nil
}

func (ix *Indexer) excluded(name string) bool { return ix.exclude[strings.ToLower(name)] }

// ScanDue 判断启动时这个根目录是否需要扫描:
// 从未扫描过、上次被中断(需要续扫)、或距上次完整扫描已超过 maxAge,都需要;maxAge <= 0 表示总是扫描。
func (ix *Indexer) ScanDue(r Root, maxAge time.Duration) bool {
	if maxAge <= 0 {
		return true
	}
	var state string
	var last sql.NullInt64
	if err := ix.DB.QueryRow(`SELECT scan_state, last_scan FROM roots WHERE id=?`, r.ID).Scan(&state, &last); err != nil {
		return true
	}
	if state != "complete" || !last.Valid {
		return true
	}
	return time.Since(time.Unix(last.Int64, 0)) > maxAge
}

// ResetScan 丢弃"被中断的扫描"记录,让下一次 ScanRoot 从头开始(用户手动点"重新扫描"时用)。
func (ix *Indexer) ResetScan(rootID int64) {
	_, _ = ix.DB.Exec(`UPDATE roots SET scan_state='', scan_gen=0 WHERE id=?`, rootID)
}

// ---- 批量写入 ----

type batch struct {
	db *sql.DB
	tx *sql.Tx
	n  int

	dialog, media, ftsDel, ftsIns *sql.Stmt
}

func (b *batch) begin() error {
	tx, err := b.db.Begin()
	if err != nil {
		return err
	}
	b.tx, b.n = tx, 0
	prep := func(q string) *sql.Stmt {
		if err != nil {
			return nil
		}
		var s *sql.Stmt
		s, err = tx.Prepare(q)
		return s
	}
	b.dialog = prep(`INSERT INTO dialogs(id,root_id,parent_id,file_id,rel_path,title,title_sort,gen) VALUES(?,?,?,?,?,?,?,?)
		ON CONFLICT(id) DO UPDATE SET root_id=excluded.root_id, parent_id=excluded.parent_id, rel_path=excluded.rel_path,
		  title=excluded.title, title_sort=excluded.title_sort, gen=excluded.gen`)
	b.media = prep(`INSERT INTO media(id,dialog_id,root_id,file_id,name,name_sort,ext,type,size,mtime,ctime,taken_eff,fingerprint,dir_rel,state,gen)
		VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)
		ON CONFLICT(id) DO UPDATE SET dialog_id=excluded.dialog_id, root_id=excluded.root_id, dir_rel=excluded.dir_rel, name=excluded.name,
		  name_sort=excluded.name_sort, ext=excluded.ext, type=excluded.type, size=excluded.size, mtime=excluded.mtime,
		  ctime=excluded.ctime, gen=excluded.gen,
		  state    = CASE WHEN media.fingerprint = excluded.fingerprint THEN media.state ELSE 0 END,
		  taken_at = CASE WHEN media.fingerprint = excluded.fingerprint THEN media.taken_at ELSE NULL END,
		  taken_eff= CASE WHEN media.fingerprint = excluded.fingerprint THEN media.taken_eff ELSE excluded.taken_eff END,
		  fingerprint = excluded.fingerprint`)
	b.ftsDel = prep(`DELETE FROM media_fts WHERE rowid=?`)
	b.ftsIns = prep(`INSERT INTO media_fts(rowid,name) VALUES(?,?)`)
	return err
}

func (b *batch) commit() error {
	if b.tx == nil {
		return nil
	}
	for _, s := range []*sql.Stmt{b.dialog, b.media, b.ftsDel, b.ftsIns} {
		if s != nil {
			s.Close()
		}
	}
	err := b.tx.Commit()
	b.tx = nil
	return err
}

func (b *batch) tick() error {
	b.n++
	if b.n >= 4000 {
		if err := b.commit(); err != nil {
			return err
		}
		return b.begin()
	}
	return nil
}

type frame struct {
	path  string
	rel   string // 相对根目录的真实路径(用来拼出文件的绝对路径)
	id    int64
	title string
	depth int   // 0 = 根目录,1 = 母文件夹,更深 = 母文件夹里的子文件夹
	group int64 // 本目录里的媒体归属的对话 id:深度 ≤1 时是自己,更深时是所在的母文件夹
}

// ScanRoot 扫描一个根目录(阶段 1)。上次被中断的扫描会接着做,否则开始新一轮。
func (ix *Indexer) ScanRoot(ctx context.Context, r Root) error {
	p := ix.prog(r)
	atomic.StoreInt64(&p.Dirs, 0)
	atomic.StoreInt64(&p.Files, 0)
	atomic.StoreInt64(&p.Errors, 0)
	atomic.StoreInt64(&p.Skipped, 0)
	ix.mu.Lock()
	p.FailedDirs = nil
	ix.mu.Unlock()

	// 续扫还是新扫:上次状态为 scanning 说明被中断了,沿用同一个扫描代号
	var state string
	var oldGen int64
	_ = ix.DB.QueryRow(`SELECT scan_state, scan_gen FROM roots WHERE id=?`, r.ID).Scan(&state, &oldGen)
	resume := state == "scanning" && oldGen != 0
	gen := time.Now().UnixNano()
	if resume {
		gen = oldGen
	} else if _, err := ix.DB.Exec(`UPDATE roots SET scan_state='scanning', scan_gen=? WHERE id=?`, gen, r.ID); err != nil {
		return err
	}
	ix.mu.Lock()
	p.Started, p.Finished, p.Resumed = time.Now(), time.Time{}, resume
	ix.mu.Unlock()
	ix.setState(p, "scanning", "")
	if resume {
		ix.Log.Info("接着上次中断的扫描继续", "root", r.Path)
	}

	rootFID, err := winfs.FileID(r.Path)
	if err != nil {
		ix.setState(p, "error", err.Error())
		return err
	}
	b := &batch{db: ix.DB}
	defer b.commit()
	rootID := winfs.Stable(r.Serial, rootFID)

	// 本根目录已有条目的名称(用于维护全文索引),一次读入
	existing := map[int64]string{}
	if rows, err := ix.DB.Query(`SELECT id,name FROM media WHERE root_id=?`, r.ID); err == nil {
		for rows.Next() {
			var id int64
			var name string
			if rows.Scan(&id, &name) == nil {
				existing[id] = name
			}
		}
		rows.Close()
	}

	// scanDir 处理一个目录:写入它的媒体,返回其下的子目录(由调用方决定遍历顺序)
	scanDir := func(f frame) ([]frame, error) {
		entries, err := winfs.ListDir(f.path)
		if err != nil {
			ix.recordDirFailure(p, f.path, err)
			ix.preserveSubtree(r.ID, f, gen)
			return nil, nil
		}
		// 目录读取(磁盘 I/O)已完成,现在才开写事务;处理完这个目录立即提交
		if err := b.begin(); err != nil {
			return nil, err
		}
		if f.depth <= 1 { // 根目录与母文件夹才是对话;更深的子文件夹不单独成组
			if _, err := b.dialog.Exec(f.id, r.ID, nil, nil, f.rel, f.title, natsort.Key(f.title), gen); err != nil {
				return nil, err
			}
		}
		atomic.AddInt64(&p.Dirs, 1)

		var subs []frame
		for _, e := range entries {
			if e.Reparse || ix.excluded(e.Name) || (e.Hidden && e.System) {
				continue
			}
			if e.IsDir {
				rel := f.rel
				if rel != "" {
					rel += `\`
				}
				nf := frame{path: filepath.Join(f.path, e.Name), rel: rel + e.Name,
					id: winfs.Stable(r.Serial, e.FileID), title: e.Name, depth: f.depth + 1}
				if nf.depth == 1 {
					nf.group = nf.id
				} else {
					nf.group = f.group
				}
				subs = append(subs, nf)
				continue
			}
			t, known := classify.ByName(e.Name)
			if !known && !ix.Cfg.IndexOtherFiles {
				continue
			}
			id := winfs.Stable(r.Serial, e.FileID)
			mtime := e.ModTime.UnixMilli()
			fp := fmt.Sprintf("%d:%d", e.Size, mtime)
			if _, err := b.media.Exec(id, f.group, r.ID, nil, e.Name, natsort.Key(e.Name), classify.Ext(e.Name), int(t),
				e.Size, mtime, e.Created.UnixMilli(), mtime, fp, f.rel, gen); err != nil {
				return nil, err
			}
			if old, ok := existing[id]; !ok || old != e.Name {
				existing[id] = e.Name
				if _, err := b.ftsDel.Exec(id); err != nil {
					return nil, err
				}
				if _, err := b.ftsIns.Exec(id, e.Name); err != nil {
					return nil, err
				}
			}
			atomic.AddInt64(&p.Files, 1)
			if err := b.tick(); err != nil {
				return nil, err
			}
		}
		if err := b.commit(); err != nil {
			return nil, err
		}
		return subs, nil
	}

	// 先处理根目录本身(盘符根目录下直接放的媒体),得到所有母文件夹
	tops, err := scanDir(frame{path: r.Path, rel: "", id: rootID, title: r.Label, depth: 0, group: rootID})
	if err != nil {
		return err
	}
	ix.markScanned(rootID, gen)

	// 逐个母文件夹扫描(其下子文件夹深度优先);扫完一个就记一笔,中断后可据此跳过
	for _, top := range tops {
		if err := ctx.Err(); err != nil {
			return err
		}
		if resume && ix.scannedGen(top.id) == gen {
			var n int64
			_ = ix.DB.QueryRow(`SELECT count(*) FROM media WHERE dialog_id=?`, top.id).Scan(&n)
			atomic.AddInt64(&p.Files, n)
			atomic.AddInt64(&p.Dirs, 1)
			continue
		}
		stack := []frame{top}
		for len(stack) > 0 {
			if err := ctx.Err(); err != nil {
				return err
			}
			f := stack[len(stack)-1]
			stack = stack[:len(stack)-1]
			subs, err := scanDir(f)
			if err != nil {
				return err
			}
			stack = append(stack, subs...)
		}
		ix.markScanned(top.id, gen)
	}

	// 删除这次没见到的条目(文件被删除/移出)
	tx, err := ix.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM media_fts WHERE rowid IN (SELECT id FROM media WHERE root_id=? AND gen<>?)`, r.ID, gen); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM media WHERE root_id=? AND gen<>?`, r.ID, gen); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM dialogs WHERE root_id=? AND gen<>?`, r.ID, gen); err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE roots SET last_scan=?, scan_state='complete' WHERE id=?`, time.Now().Unix(), r.ID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	if err := ix.Finalize(r.ID); err != nil {
		return err
	}
	ix.Log.Info("扫描完成", "root", r.Path, "dirs", atomic.LoadInt64(&p.Dirs), "files", atomic.LoadInt64(&p.Files),
		"ms", time.Since(p.Started).Milliseconds(), "resumed", resume)
	return nil
}

// recordDirFailure 记录一个读取失败的目录:无权限 / 已消失属于正常现象(记为"跳过"),其它才算错误,并按严重程度写日志。
func (ix *Indexer) recordDirFailure(p *Progress, path string, err error) {
	kind, code, text := winfs.ErrKind(err)
	switch kind {
	case "denied":
		atomic.AddInt64(&p.Skipped, 1)
		ix.Log.Info("跳过没有权限的目录", "dir", path, "code", code)
	case "gone":
		atomic.AddInt64(&p.Skipped, 1)
		ix.Log.Debug("目录在扫描过程中消失", "dir", path)
	case "name":
		atomic.AddInt64(&p.Skipped, 1)
		ix.Log.Warn("目录名称不符合 Windows 规则,已跳过(改名后重新扫描即可)", "dir", path, "code", code, "err", err)
	default:
		atomic.AddInt64(&p.Errors, 1)
		ix.Log.Warn("目录读取失败,已保留其下旧数据", "dir", path, "kind", kind, "code", code, "reason", text, "err", err)
	}
	ix.mu.Lock()
	if len(p.FailedDirs) < 50 {
		p.FailedDirs = append(p.FailedDirs, DirFailure{Path: path, Kind: kind, Code: code, Reason: text})
	}
	ix.mu.Unlock()
}

func (ix *Indexer) markScanned(dialogID, gen int64) {
	_, _ = ix.DB.Exec(`UPDATE dialogs SET scanned_gen=? WHERE id=?`, gen, dialogID)
}

func (ix *Indexer) scannedGen(dialogID int64) int64 {
	var g int64
	_ = ix.DB.QueryRow(`SELECT scanned_gen FROM dialogs WHERE id=?`, dialogID).Scan(&g)
	return g
}

// preserveSubtree 目录读取失败时,保留它及其子树已有数据,避免被误删。
func (ix *Indexer) preserveSubtree(rootID int64, f frame, gen int64) {
	if f.depth == 0 { // 整个根目录读不了(盘离线等):全部保留
		_, _ = ix.DB.Exec(`UPDATE dialogs SET gen=? WHERE root_id=?`, gen, rootID)
		_, _ = ix.DB.Exec(`UPDATE media SET gen=? WHERE root_id=?`, gen, rootID)
		return
	}
	like := strings.NewReplacer(`%`, `\%`, `_`, `\_`).Replace(f.rel) + `\%`
	_, _ = ix.DB.Exec(`UPDATE dialogs SET gen=? WHERE id=?`, gen, f.group)
	_, _ = ix.DB.Exec(`UPDATE media SET gen=? WHERE root_id=? AND dialog_id=? AND (dir_rel=? OR dir_rel LIKE ? ESCAPE '\')`,
		gen, rootID, f.group, f.rel, like)
}

// Finalize 重新统计对话计数、最新条目与版本号。
func (ix *Indexer) Finalize(rootID int64) error {
	tx, err := ix.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`UPDATE dialogs SET
		cnt_photo=(SELECT count(*) FROM media WHERE dialog_id=dialogs.id AND type=0),
		cnt_video=(SELECT count(*) FROM media WHERE dialog_id=dialogs.id AND type=1),
		cnt_gif  =(SELECT count(*) FROM media WHERE dialog_id=dialogs.id AND type=2),
		cnt_audio=(SELECT count(*) FROM media WHERE dialog_id=dialogs.id AND type=3),
		cnt_file =(SELECT count(*) FROM media WHERE dialog_id=dialogs.id AND type=4),
		last_item_id=(SELECT id FROM media WHERE dialog_id=dialogs.id AND type IN (0,1,2) ORDER BY taken_eff DESC, id DESC LIMIT 1),
		last_taken  =COALESCE((SELECT taken_eff FROM media WHERE dialog_id=dialogs.id AND type IN (0,1,2) ORDER BY taken_eff DESC, id DESC LIMIT 1),0)
		WHERE root_id=?`, rootID); err != nil {
		return err
	}
	// 对话没有层级:递归计数 = 直接计数,话题数恒为 0
	if _, err := tx.Exec(`UPDATE dialogs SET parent_id=NULL, topics=0,
		cnt_recursive=cnt_photo+cnt_video+cnt_gif+cnt_audio WHERE root_id=?`, rootID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	// 版本号:由计数和最新条目决定,客户端据此判断列表是否有变化
	rows, err := ix.DB.Query(`SELECT id, cnt_photo,cnt_video,cnt_gif,cnt_audio,cnt_file, cnt_recursive, last_taken, COALESCE(last_item_id,0)
		FROM dialogs WHERE root_id=?`, rootID)
	if err != nil {
		return err
	}
	type ver struct{ id, v int64 }
	var vs []ver
	for rows.Next() {
		var id, photo, vid, gif, aud, file, rec, last, lastItem int64
		if err := rows.Scan(&id, &photo, &vid, &gif, &aud, &file, &rec, &last, &lastItem); err != nil {
			rows.Close()
			return err
		}
		h := fnv.New64a()
		fmt.Fprintf(h, "%d|%d|%d|%d|%d|%d|%d|%d", photo, vid, gif, aud, file, rec, last, lastItem)
		vs = append(vs, ver{id, int64(h.Sum64() & 0x7fffffffffffffff)})
	}
	rows.Close()
	tx2, err := ix.DB.Begin()
	if err != nil {
		return err
	}
	defer tx2.Rollback()
	up, err := tx2.Prepare(`UPDATE dialogs SET version=? WHERE id=?`)
	if err != nil {
		return err
	}
	defer up.Close()
	for _, x := range vs {
		if _, err := up.Exec(x.v, x.id); err != nil {
			return err
		}
	}
	return tx2.Commit()
}
