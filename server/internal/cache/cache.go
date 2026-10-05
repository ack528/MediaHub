// Package cache 管理"每个媒体盘一份、带配额"的生成物缓存(视频封面、兜底转换结果)。
//
// 关键约束(见 docs/02-服务端设计.md §6):
//   - 缓存放在视频所在盘自己的 X:\.mediahub\,每盘配额默认 50GB,LRU 淘汰;
//   - 启动自检:盘上可用空间 < 配额 + 余量时,回退到 runtime\cache\<卷>\ 并告警;
//   - 写入边界:本包是服务端对媒体盘的【唯一】写入口,路径必须落在缓存目录内。
package cache

import (
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"mediahub/internal/winfs"
)

const (
	ModeOnDrive  = "on_drive"
	ModeFallback = "fallback"
)

type RootCache struct {
	RootID int64
	Dir    string
	Mode   string
	Quota  int64
	Reason string // 回退原因
}

type Manager struct {
	db          *sql.DB
	log         *slog.Logger
	quotaBytes  int64
	marginBytes int64
	fallbackDir string

	mu    sync.Mutex
	roots map[int64]*RootCache
}

func New(db *sql.DB, log *slog.Logger, quotaGB, marginGB int, fallbackDir string) *Manager {
	return &Manager{
		db: db, log: log,
		quotaBytes:  int64(quotaGB) << 30,
		marginBytes: int64(marginGB) << 30,
		fallbackDir: fallbackDir,
		roots:       map[int64]*RootCache{},
	}
}

// Init 对一个根目录做空间自检并确定缓存目录。
func (m *Manager) Init(rootID int64, rootPath, label string) (*RootCache, error) {
	rc := &RootCache{RootID: rootID, Quota: m.quotaBytes}
	onDrive := filepath.Join(rootPath, ".mediahub")

	free, ferr := winfs.FreeBytes(rootPath)
	switch {
	case ferr != nil:
		rc.Reason = "无法读取剩余空间: " + ferr.Error()
	case free < m.quotaBytes+m.marginBytes:
		rc.Reason = fmt.Sprintf("盘剩余 %.1fGB < 配额 %.0fGB + 余量 %.0fGB", float64(free)/(1<<30), float64(m.quotaBytes)/(1<<30), float64(m.marginBytes)/(1<<30))
	default:
		if err := os.MkdirAll(onDrive, 0o755); err != nil {
			rc.Reason = "无法创建缓存目录: " + err.Error()
		} else if err := m.prepareDir(onDrive, true); err != nil {
			rc.Reason = "缓存目录不可写: " + err.Error()
		} else {
			rc.Dir, rc.Mode = onDrive, ModeOnDrive
		}
	}
	if rc.Dir == "" {
		fb := filepath.Join(m.fallbackDir, sanitize(label))
		if err := os.MkdirAll(fb, 0o755); err != nil {
			return nil, fmt.Errorf("fallback cache dir: %w", err)
		}
		if err := m.prepareDir(fb, false); err != nil {
			return nil, err
		}
		rc.Dir, rc.Mode = fb, ModeFallback
		m.log.Warn("缓存回退到项目目录", "root", rootPath, "dir", fb, "reason", rc.Reason)
	}
	m.cleanTmp(rc.Dir)
	m.mu.Lock()
	m.roots[rootID] = rc
	m.mu.Unlock()
	_, _ = m.db.Exec(`UPDATE roots SET cache_dir=?, cache_mode=? WHERE id=?`, rc.Dir, rc.Mode, rootID)
	return rc, nil
}

func (m *Manager) prepareDir(dir string, hide bool) error {
	// .ignore:让 Jellyfin 忽略此目录(其中的 WebP 不是媒体)
	ign := filepath.Join(dir, ".ignore")
	if _, err := os.Stat(ign); err != nil {
		if err := os.WriteFile(ign, nil, 0o644); err != nil {
			return err
		}
	}
	if hide {
		_ = winfs.SetHidden(dir)
	}
	return nil
}

func (m *Manager) cleanTmp(dir string) {
	_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && strings.HasSuffix(p, ".tmp") {
			_ = os.Remove(p)
		}
		return nil
	})
}

func (m *Manager) Get(rootID int64) (*RootCache, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	rc, ok := m.roots[rootID]
	return rc, ok
}

func (m *Manager) All() []*RootCache {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]*RootCache, 0, len(m.roots))
	for _, rc := range m.roots {
		out = append(out, rc)
	}
	return out
}

// Path 返回缓存文件应处的路径(不保证存在)。kind 例如 "posters"、"renders"。
func (m *Manager) Path(rootID int64, kind string, mediaID int64, ext string) (string, error) {
	rc, ok := m.Get(rootID)
	if !ok {
		return "", errors.New("cache: unknown root")
	}
	if kind == "" || strings.ContainsAny(kind, `/\.`) {
		return "", errors.New("cache: bad kind")
	}
	return filepath.Join(rc.Dir, kind, fmt.Sprintf("%02x", mediaID&0xff), fmt.Sprintf("%d.%s", mediaID, ext)), nil
}

// Exists 判断缓存是否命中并刷新访问时间(每小时最多写一次)。
func (m *Manager) Exists(rootID int64, kind string, mediaID int64, ext string) (string, bool) {
	p, err := m.Path(rootID, kind, mediaID, ext)
	if err != nil {
		return "", false
	}
	if _, err := os.Stat(p); err != nil {
		return p, false
	}
	now := time.Now().Unix()
	_, _ = m.db.Exec(`UPDATE cache_entries SET last_access=? WHERE kind=? AND media_id=? AND last_access<?`, now, kind, mediaID, now-3600)
	return p, true
}

// Put 把 write 产生的文件原子地放进缓存。这是对媒体盘的唯一写入口。
// write 收到一个临时文件路径(在目标目录内),完成后本函数重命名为最终路径。
func (m *Manager) Put(rootID int64, kind string, mediaID int64, ext string, write func(tmp string) error) (string, error) {
	rc, ok := m.Get(rootID)
	if !ok {
		return "", errors.New("cache: unknown root")
	}
	final, err := m.Path(rootID, kind, mediaID, ext)
	if err != nil {
		return "", err
	}
	if !within(rc.Dir, final) { // 写入边界断言
		return "", fmt.Errorf("cache: path escapes cache dir: %s", final)
	}
	if err := os.MkdirAll(filepath.Dir(final), 0o755); err != nil {
		return "", err
	}
	tmp := final + ".tmp"
	if err := write(tmp); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	if !within(rc.Dir, tmp) {
		_ = os.Remove(tmp)
		return "", errors.New("cache: tmp escapes cache dir")
	}
	st, err := os.Stat(tmp)
	if err != nil {
		return "", err
	}
	if err := os.Rename(tmp, final); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	_, err = m.db.Exec(`INSERT INTO cache_entries(kind,media_id,root_id,path,bytes,last_access) VALUES(?,?,?,?,?,?)
		ON CONFLICT(kind,media_id) DO UPDATE SET path=excluded.path, bytes=excluded.bytes, last_access=excluded.last_access`,
		kind, mediaID, rootID, final, st.Size(), time.Now().Unix())
	if err == nil {
		m.Evict(rootID, kind, mediaID)
	}
	return final, err
}

// Evict 超过配额时按最久未访问淘汰;keepKind/keepID 指定的条目(刚写入的)永不淘汰。
func (m *Manager) Evict(rootID int64, keepKind string, keepID int64) {
	rc, ok := m.Get(rootID)
	if !ok {
		return
	}
	var used int64
	if err := m.db.QueryRow(`SELECT COALESCE(SUM(bytes),0) FROM cache_entries WHERE root_id=?`, rootID).Scan(&used); err != nil || used <= rc.Quota {
		return
	}
	rows, err := m.db.Query(`SELECT kind, media_id, path, bytes FROM cache_entries WHERE root_id=? ORDER BY last_access ASC LIMIT 500`, rootID)
	if err != nil {
		return
	}
	type ent struct {
		kind string
		id   int64
		path string
		b    int64
	}
	var del []ent
	for rows.Next() {
		var e ent
		if rows.Scan(&e.kind, &e.id, &e.path, &e.b) == nil {
			del = append(del, e)
		}
	}
	rows.Close()
	for _, e := range del {
		if e.kind == keepKind && e.id == keepID {
			continue
		}
		if used <= rc.Quota*9/10 { // 淘汰到 90% 以避免频繁触发
			break
		}
		if within(rc.Dir, e.path) {
			_ = os.Remove(e.path)
		}
		_, _ = m.db.Exec(`DELETE FROM cache_entries WHERE kind=? AND media_id=?`, e.kind, e.id)
		used -= e.b
	}
}

// Usage 返回某个根的缓存占用。
func (m *Manager) Usage(rootID int64) int64 {
	var used int64
	_ = m.db.QueryRow(`SELECT COALESCE(SUM(bytes),0) FROM cache_entries WHERE root_id=?`, rootID).Scan(&used)
	return used
}

func within(dir, p string) bool {
	rel, err := filepath.Rel(dir, p)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func sanitize(s string) string {
	r := strings.NewReplacer(`:`, "", `\`, "_", `/`, "_", ` `, "_", `*`, "", `?`, "", `"`, "", `<`, "", `>`, "", `|`, "")
	s = r.Replace(s)
	if s == "" {
		s = "root"
	}
	return s
}
