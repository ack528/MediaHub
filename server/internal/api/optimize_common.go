package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"mediahub/internal/winfs"
)

// 存储优化(重复文件检测 + 视频压缩)的公共部分。
//
// 写入边界:服务端对媒体盘的写入只发生在各盘根目录下的 .mediahub 里,存储优化也一样 ——
//   - 压缩的输出先写到 <根目录>\.mediahub\optimize\;
//   - "删除"重复文件 / "替换"成压缩版时,原文件是被【移动】到 <根目录>\.mediahub\trash\<批次>\ 里(带清单,可以还原),不是直接删除;
//   - 只有在管理程序里明确点「清空回收区」才会永久删除。
//   所有这些接口都只接受本机管理程序的请求(回环地址 + 管理密钥),手机端用不了。

// optState 挂在 Server 上的存储优化状态。
type optState struct {
	dd dedupeState
	cp compressState
}

// mediaInfo 一个媒体文件的路径与基本属性。
type mediaInfo struct {
	ID       int64
	RootID   int64
	RootPath string
	RootName string // 盘符 / 标签,给界面显示
	DirRel   string
	Name     string
	Ext      string
	Type     int
	Size     int64
	Mtime    int64
	TakenAt  int64
	W, H     int
	DurMs    int64
	Vcodec   string
	Bitrate  int64
}

func (m mediaInfo) rel() string { return filepath.Join(m.DirRel, m.Name) }

// full 绝对路径(长路径 / 结尾带空格的名称用扩展路径)。
func (m mediaInfo) full() string { return winfs.Path(filepath.Join(m.RootPath, m.DirRel, m.Name)) }

const mediaInfoSQL = `SELECT m.id, m.root_id, ro.path, COALESCE(ro.label,''), m.dir_rel, m.name, m.ext, m.type, m.size, m.mtime, COALESCE(m.taken_at,0),
	COALESCE(m.w,0), COALESCE(m.h,0), COALESCE(m.duration_ms,0), COALESCE(m.vcodec,''), COALESCE(m.bitrate,0)
	FROM media m JOIN roots ro ON ro.id=m.root_id`

func scanMediaInfo(sc scanner) (mediaInfo, error) {
	var m mediaInfo
	err := sc.Scan(&m.ID, &m.RootID, &m.RootPath, &m.RootName, &m.DirRel, &m.Name, &m.Ext, &m.Type, &m.Size, &m.Mtime, &m.TakenAt, &m.W, &m.H, &m.DurMs, &m.Vcodec, &m.Bitrate)
	if m.RootName == "" {
		m.RootName = filepath.VolumeName(m.RootPath)
	}
	return m, err
}

func (s *Server) mediaInfoByID(id int64) (mediaInfo, error) {
	return scanMediaInfo(s.DB.QueryRow(mediaInfoSQL+` WHERE m.id=?`, id))
}

// ---------------------------------------------------------------- 回收区

// 回收区:<根目录>\.mediahub\trash\<批次>\<原来的相对路径>,同一批次里有一个 manifest.json 记录来源,可以还原。
type trashEntry struct {
	MediaID int64  `json:"mediaId"`
	Rel     string `json:"rel"`  // 原来的相对路径(相对根目录)
	Size    int64  `json:"size"` // 文件大小
	Reason  string `json:"reason"`
}

type trashManifest struct {
	Batch   string       `json:"batch"`
	RootID  int64        `json:"rootId"`
	Created int64        `json:"created"`
	Reason  string       `json:"reason"`
	Files   []trashEntry `json:"files"`
}

func trashRoot(rootPath string) string { return filepath.Join(rootPath, ".mediahub", "trash") }

var trashMu sync.Mutex // 同一批次的 manifest 读写串行化

// moveToTrash 把一个媒体文件移进回收区(同一个盘上的重命名,瞬间完成,不占额外空间)。
func moveToTrash(m mediaInfo, batch, reason string) error {
	src := m.full()
	dst := filepath.Join(trashRoot(m.RootPath), batch, m.rel())
	if err := os.MkdirAll(winfs.Path(filepath.Dir(dst)), 0o755); err != nil {
		return err
	}
	if _, err := os.Stat(winfs.Path(dst)); err == nil { // 批次里已经有同名文件:加序号,不覆盖
		ext := filepath.Ext(dst)
		base := strings.TrimSuffix(dst, ext)
		for i := 2; ; i++ {
			c := fmt.Sprintf("%s (%d)%s", base, i, ext)
			if _, err := os.Stat(winfs.Path(c)); err != nil {
				dst = c
				break
			}
		}
	}
	if err := os.Rename(src, winfs.Path(dst)); err != nil {
		return err
	}
	trashMu.Lock()
	defer trashMu.Unlock()
	mp := filepath.Join(trashRoot(m.RootPath), batch, "manifest.json")
	var mf trashManifest
	if b, err := os.ReadFile(winfs.Path(mp)); err == nil {
		_ = json.Unmarshal(b, &mf)
	}
	if mf.Batch == "" {
		mf = trashManifest{Batch: batch, RootID: m.RootID, Created: time.Now().Unix(), Reason: reason}
	}
	rel, _ := filepath.Rel(trashRoot(m.RootPath)+string(filepath.Separator)+batch, dst)
	mf.Files = append(mf.Files, trashEntry{MediaID: m.ID, Rel: rel, Size: m.Size, Reason: reason})
	b, _ := json.MarshalIndent(mf, "", " ")
	return os.WriteFile(winfs.Path(mp), b, 0o644)
}

// forgetMedia 从索引里去掉已经移走的文件(不等重新扫描,界面马上不再显示)。
func (s *Server) forgetMedia(ids ...int64) {
	for _, id := range ids {
		_, _ = s.DB.Exec(`DELETE FROM media WHERE id=?`, id)
		_, _ = s.DB.Exec(`DELETE FROM media_hash WHERE media_id=?`, id)
	}
}

var errNotUnder = errors.New("路径不在回收区内")

// underTrash 校验 p 位于 root 的回收区里(防止清空 / 还原时被构造的路径带出去)。
func underTrash(rootPath, p string) error {
	rel, err := filepath.Rel(trashRoot(rootPath), p)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return errNotUnder
	}
	return nil
}

func rootPathByID(db *sql.DB, id int64) (string, error) {
	var p string
	err := db.QueryRow(`SELECT path FROM roots WHERE id=?`, id).Scan(&p)
	return p, err
}

func dirSize(dir string) (n int64, files int) {
	_ = filepath.Walk(winfs.Path(dir), func(_ string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() {
			n += info.Size()
			files++
		}
		return nil
	})
	return
}
