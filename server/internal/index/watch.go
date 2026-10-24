package index

import (
	"context"
	"path/filepath"
	"strings"
	"time"
)

// WatchOptions 实时增量索引的参数(测试里可以调小)。
type WatchOptions struct {
	Quiet   time.Duration // 安静这么久才开始处理(文件还在拷贝时不停地有写入事件,等它拷完)。默认 5 秒
	MaxWait time.Duration // 一直有变化时,最迟等这么久也要处理一批。默认 30 秒
	MinGap  time.Duration // 两批处理之间至少隔这么久(Finalize 要重算对话计数)。默认 10 秒
	MaxDirs int           // 一批里不同目录数超过这个就改做整盘扫描。默认 300
	// Full 缓冲区溢出 / 变化太多时调用:让调用方做一次整盘扫描(含补全元数据)。可为空
	Full func()
	// After 每批增量扫描完成后调用(补全新文件的元数据、预热封面…)。可为空
	After func(changed bool)
}

func (o *WatchOptions) defaults() {
	if o.Quiet <= 0 {
		o.Quiet = 5 * time.Second
	}
	if o.MaxWait <= 0 {
		o.MaxWait = 30 * time.Second
	}
	if o.MinGap <= 0 {
		o.MinGap = 10 * time.Second
	}
	if o.MaxDirs <= 0 {
		o.MaxDirs = 300
	}
}

// Watch 监视一个根目录:文件 / 文件夹有新增、删除、改名、修改后,过几秒就自动把变化反映到索引里(新拷进来的照片几秒后手机上就能看到)。
// 全量扫描仍然保留(定时 + 手动)作为兜底。监视不可用(网络盘、权限不够)时返回错误,调用方只记一条日志,其余行为不变。
// 阻塞直到 ctx 结束。lock 用来和整盘扫描互斥(同一个根目录同一时间只做一件事)。
func (ix *Indexer) Watch(ctx context.Context, r Root, lock func() func(), opt WatchOptions) error {
	opt.defaults()
	ev := make(chan string, 4096)
	werr := make(chan error, 1)
	go func() { werr <- watchRoot(ctx, r.Path, ev) }()

	pending := map[string]bool{}
	overflow := false
	var first, last time.Time // 这一批第一次 / 最近一次收到事件的时间
	var lastRun time.Time
	var retryAt time.Time
	retryDelay := time.Duration(0)
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case err := <-werr:
			if ctx.Err() != nil {
				return nil
			}
			return err
		case p := <-ev:
			now := time.Now()
			if first.IsZero() {
				first = now
			}
			last = now
			if p == "" {
				overflow = true
			} else if !ix.pathExcluded(r, p) && strings.HasPrefix(strings.ToLower(p), strings.ToLower(r.Path)) {
				pending[filepath.Clean(p)] = true
			}
		case <-tick.C:
			if time.Now().Before(retryAt) {
				continue
			}
			if first.IsZero() {
				continue
			}
			now := time.Now()
			if now.Sub(last) < opt.Quiet && now.Sub(first) < opt.MaxWait {
				continue
			}
			if now.Sub(lastRun) < opt.MinGap {
				continue
			}
			dirs := make([]string, 0, len(pending))
			for d := range pending {
				dirs = append(dirs, d)
			}
			full := overflow || len(dirs) > opt.MaxDirs
			pending, overflow, first = map[string]bool{}, false, time.Time{}
			lastRun = now
			if len(dirs) == 0 && !full {
				continue
			}
			unlock := lock()
			if full {
				ix.Log.Info("目录变化太多,改做整盘扫描", "root", r.Path, "dirs", len(dirs))
				unlock()
				if opt.Full != nil {
					opt.Full()
				}
				continue
			}
			t0 := time.Now()
			changed, err := ix.RescanDirs(ctx, r, dirs)
			unlock()
			if err != nil {
				if retryDelay == 0 {
					retryDelay = 30 * time.Second
				} else {
					retryDelay *= 2
				}
				if retryDelay > 5*time.Minute {
					retryDelay = 5 * time.Minute
				}
				retryAt = time.Now().Add(retryDelay)
				for _, d := range dirs {
					pending[d] = true
				}
				first, last = time.Now(), time.Now()
				ix.Log.Warn("增量扫描失败,等下一次整盘扫描", "root", r.Path, "err", err)
				continue
			}
			retryAt, retryDelay = time.Time{}, 0
			if changed {
				ix.Log.Info("增量索引", "root", r.Path, "dirs", len(dirs), "ms", time.Since(t0).Milliseconds())
			}
			if opt.After != nil {
				opt.After(changed)
			}
		}
	}
}

// LockRoot 同一个根目录的整盘扫描和增量扫描互斥。返回解锁函数。
func (ix *Indexer) LockRoot(id int64) func() {
	ix.mu.Lock()
	if ix.rootLocks == nil {
		ix.rootLocks = map[int64]chan struct{}{}
	}
	ch := ix.rootLocks[id]
	if ch == nil {
		ch = make(chan struct{}, 1)
		ix.rootLocks[id] = ch
	}
	ix.mu.Unlock()
	ch <- struct{}{}
	return func() { <-ch }
}
