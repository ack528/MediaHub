package index

import (
	"context"
	"database/sql"
	"fmt"
	"path/filepath"
	"strings"
	"time"

	"mediahub/internal/classify"
	"mediahub/internal/natsort"
	"mediahub/internal/winfs"
)

// 实时增量索引:收到目录变更通知后只重新扫描变化的那几个目录(不用整盘重扫)。
// 做法和 ScanRoot 的"扫一个目录"一致 —— 同样的 upsert(指纹没变的文件不丢元数据)、同样的 FTS 维护、同样的对话归属规则 ——
// 区别是:① 只处理给定的目录,新出现的子目录(整个文件夹被拷进来 / 改名)递归处理;② 消失的文件、子目录就地删除。

func likeEscape(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// frameFor 为目录 dir 算出对话归属等信息。exists=false 表示目录已经不在了。
func (ix *Indexer) frameFor(r Root, rootID int64, dir string) (f frame, exists bool) {
	dir = filepath.Clean(dir)
	rel, err := filepath.Rel(r.Path, dir)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return frame{}, false
	}
	if rel == "." {
		return frame{path: r.Path, rel: "", id: rootID, title: r.Label, depth: 0, group: rootID}, true
	}
	parts := strings.Split(rel, string(filepath.Separator))
	fid, err := winfs.FileID(dir)
	if err != nil {
		return frame{rel: rel, depth: len(parts)}, false
	}
	id := winfs.Stable(r.Serial, fid)
	f = frame{path: dir, rel: rel, id: id, title: parts[len(parts)-1], depth: len(parts), group: id}
	if len(parts) > 1 {
		tfid, err := winfs.FileID(filepath.Join(r.Path, parts[0]))
		if err != nil {
			return frame{rel: rel, depth: len(parts)}, false
		}
		f.group = winfs.Stable(r.Serial, tfid)
	}
	return f, true
}

// removeDir 删除某个相对目录(及其整棵子树)在索引里的全部媒体;depth==1 时连对话一起删。
func (ix *Indexer) removeDir(rootID int64, rel string, depth int) error {
	tx, err := ix.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	like := likeEscape(rel) + `\%`
	cond := `root_id=? AND (dir_rel=? OR dir_rel LIKE ? ESCAPE '\')`
	if _, err := tx.Exec(`DELETE FROM media_fts WHERE rowid IN (SELECT id FROM media WHERE `+cond+`)`, rootID, rel, like); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM media WHERE `+cond, rootID, rel, like); err != nil {
		return err
	}
	if depth == 1 {
		if _, err := tx.Exec(`DELETE FROM dialogs WHERE root_id=? AND rel_path=?`, rootID, rel); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// RescanDirs 重新扫描 dirs(绝对路径)。返回是否有索引内容发生变化。超过 maxDirs 个目录时调用方应该改用整盘扫描。
func (ix *Indexer) RescanDirs(ctx context.Context, r Root, dirs []string) (changed bool, retErr error) {
	rootFID, err := winfs.FileID(r.Path)
	if err != nil {
		return false, err
	}
	rootID := winfs.Stable(r.Serial, rootFID)
	gen := time.Now().UnixNano()
	b := &batch{db: ix.DB}
	defer b.rollback()

	var stack []frame
	var gone []frame
	queued := map[string]bool{}
	for _, d := range dirs {
		if ix.pathExcluded(r, d) { // .mediahub(我们自己的缓存)、$RECYCLE.BIN 等
			continue
		}
		f, ok := ix.frameFor(r, rootID, d)
		if !ok {
			if f.rel != "" {
				gone = append(gone, f)
			}
			continue
		}
		if !queued[f.path] {
			queued[f.path] = true
			stack = append(stack, f)
		}
	}
	dirKnown := func(f frame) bool { // 索引里是否已经有这个目录里的媒体(没有 = 新目录,要递归扫)
		var one int
		return b.tx.QueryRow(`SELECT 1 FROM media WHERE root_id=? AND dir_rel=? LIMIT 1`, r.ID, f.rel).Scan(&one) == nil
	}

	for len(stack) > 0 {
		if err := ctx.Err(); err != nil {
			return changed, err
		}
		f := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if ix.Throttle != nil {
			ix.Throttle(ctx, 20*time.Millisecond)
		}
		entries, err := listDirTimeout(f.path, dirReadTimeout)
		if err != nil {
			if k, _, _ := winfs.ErrKind(err); k == "gone" {
				gone = append(gone, f)
			} else {
				ix.Log.Debug("增量扫描:目录读取失败,留给下一次整盘扫描", "dir", f.path, "err", err)
			}
			continue
		}
		if err := b.begin(); err != nil {
			return changed, err
		}
		if f.depth <= 1 {
			if _, err := b.dialog.Exec(f.id, r.ID, nil, nil, f.rel, f.title, natsort.Key(f.title), gen); err != nil {
				return changed, err
			}
		}
		listed := map[string]bool{} // 这个目录里现在有的子目录名
		for _, e := range entries {
			if e.Reparse || ix.excluded(e.Name) || (e.Hidden && e.System) {
				continue
			}
			if e.IsDir {
				listed[e.Name] = true
				rel := f.rel
				if rel != "" {
					rel += `\`
				}
				nf := frame{path: filepath.Join(f.path, e.Name), rel: rel + e.Name, id: winfs.Stable(r.Serial, e.FileID), title: e.Name, depth: f.depth + 1}
				if nf.depth == 1 {
					nf.group = nf.id
				} else {
					nf.group = f.group
				}
				if !queued[nf.path] && !dirKnown(nf) { // 新出现的子目录(整个文件夹拷进来 / 改名后):递归处理
					queued[nf.path] = true
					stack = append(stack, nf)
				}
				continue
			}
			t, known := classify.ByName(e.Name)
			if !known && !ix.Cfg.IndexOtherFiles {
				continue
			}
			id := winfs.Stable(r.Serial, e.FileID)
			mtime := e.ModTime.UnixMilli()
			fp := fmt.Sprintf("%d:%d", e.Size, mtime)
			var oldName string
			_ = b.tx.QueryRow(`SELECT name FROM media WHERE id=?`, id).Scan(&oldName)
			if _, err := b.media.Exec(id, f.group, r.ID, nil, e.Name, natsort.Key(e.Name), classify.Ext(e.Name), int(t),
				e.Size, mtime, e.Created.UnixMilli(), mtime, fp, f.rel, gen); err != nil {
				return changed, err
			}
			if oldName != e.Name {
				if _, err := b.ftsDel.Exec(id); err != nil {
					return changed, err
				}
				if _, err := b.ftsIns.Exec(id, e.Name); err != nil {
					return changed, err
				}
			}
		}
		if err := b.commit(); err != nil {
			return changed, err
		}
		changed = true

		// 这个目录里消失的文件:同一目录、同一对话、这次没见到的
		if err := ix.deleteUnseen(r.ID, f, gen); err != nil {
			return changed, err
		}
		// 消失的子目录:索引里记着它,磁盘上已经没有了
		if err := ix.pruneVanishedChildren(r, rootID, f, listed); err != nil {
			return changed, err
		}
	}
	for _, g := range gone { // 放在最后:改名的目录会先被当作"新目录"整棵 upsert 过去,旧路径下就没有东西可删了
		if err := ix.removeDir(r.ID, g.rel, g.depth); err != nil {
			return changed, err
		}
		changed = true
	}
	if changed {
		if err := ix.Finalize(r.ID); err != nil {
			return changed, err
		}
	}
	return changed, nil
}

func (ix *Indexer) deleteUnseen(rootID int64, f frame, gen int64) error {
	tx, err := ix.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	cond := `root_id=? AND dialog_id=? AND dir_rel=? AND gen<>?`
	if _, err := tx.Exec(`DELETE FROM media_fts WHERE rowid IN (SELECT id FROM media WHERE `+cond+`)`, rootID, f.group, f.rel, gen); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM media WHERE `+cond, rootID, f.group, f.rel, gen); err != nil {
		return err
	}
	return tx.Commit()
}

// pruneVanishedChildren 删除 f 下"索引里有、磁盘上没了"的直接子目录(整棵子树)。
func (ix *Indexer) pruneVanishedChildren(r Root, rootID int64, f frame, listed map[string]bool) error {
	var names []string
	if f.depth == 0 { // 根目录下的母文件夹 = 对话
		rows, err := ix.DB.Query(`SELECT rel_path FROM dialogs WHERE root_id=? AND rel_path<>'' AND instr(rel_path, '\')=0`, r.ID)
		if err != nil {
			return err
		}
		for rows.Next() {
			var n string
			if rows.Scan(&n) == nil {
				names = append(names, n)
			}
		}
		rows.Close()
	} else {
		prefix := f.rel + `\`
		rows, err := ix.DB.Query(`SELECT DISTINCT dir_rel FROM media WHERE root_id=? AND dialog_id=? AND dir_rel LIKE ? ESCAPE '\'`, r.ID, f.group, likeEscape(prefix)+`%`)
		if err != nil {
			return err
		}
		seen := map[string]bool{}
		for rows.Next() {
			var d string
			if rows.Scan(&d) != nil {
				continue
			}
			first := strings.SplitN(strings.TrimPrefix(d, prefix), `\`, 2)[0]
			if first != "" && !seen[first] {
				seen[first] = true
				names = append(names, first)
			}
		}
		rows.Close()
	}
	for _, n := range names {
		if listed[n] || ix.excluded(n) {
			continue
		}
		rel := n
		if f.rel != "" {
			rel = f.rel + `\` + n
		}
		if err := ix.removeDir(r.ID, rel, f.depth+1); err != nil {
			return err
		}
	}
	return nil
}

// 避免 database/sql 未使用的导入告警(rows 辅助)。
var _ = sql.ErrNoRows

// pathExcluded 路径里有没有被排除的目录名(.mediahub、$RECYCLE.BIN、System Volume Information…)。
func (ix *Indexer) pathExcluded(r Root, p string) bool {
	rel, err := filepath.Rel(r.Path, filepath.Clean(p))
	if err != nil || rel == "." {
		return false
	}
	for _, c := range strings.Split(rel, string(filepath.Separator)) {
		if ix.excluded(c) {
			return true
		}
	}
	return false
}
