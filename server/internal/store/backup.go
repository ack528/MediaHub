package store

import (
	"database/sql"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// 数据库备份:账号、令牌、播放进度、浏览位置、压缩队列、哈希缓存都在一个 SQLite 文件里,损坏了就全没了。
// 用 VACUUM INTO 在线备份:不停服务、不阻塞读、得到的是一份一致且已整理过的完整副本。

const backupPrefix = "hub-"
const backupSuffix = ".sqlite"

// BackupInfo 一份备份。
type BackupInfo struct {
	Name    string `json:"name"`
	Size    int64  `json:"size"`
	Created int64  `json:"created"`
}

// BackupDir 备份放在 <数据目录>\backup\。
func BackupDir(dataDir string) string { return filepath.Join(dataDir, "backup") }

// Backup 立刻备份一份,然后只保留最近 keep 份(keep <= 0 不删)。返回新备份的文件名。
func Backup(db *sql.DB, dataDir string, keep int) (string, error) {
	dir := BackupDir(dataDir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	name := backupPrefix + time.Now().Format("20060102-150405.000000000") + backupSuffix
	// Pending files are not listed/restorable until validation has completed.
	f, err := os.CreateTemp(dir, ".pending-backup-*")
	if err != nil {
		return "", err
	}
	dst := f.Name()
	if err := f.Close(); err != nil {
		return "", err
	}
	defer os.Remove(dst)
	if _, err := db.Exec("VACUUM INTO '" + strings.ReplaceAll(dst, "'", "''") + "'"); err != nil {
		return "", fmt.Errorf("备份失败:%w", err)
	}
	// Validate the actual output, not just VACUUM's exit status. In particular,
	// corrupt FTS shadow tables can be copied successfully. Never rotate older
	// backups until the new backup has passed validation.
	copyDB, err := Open(dst)
	if err == nil {
		err = integrityCheck(copyDB)
		if err == nil {
			tx, txErr := copyDB.Begin()
			if txErr == nil {
				var broken bool
				broken, txErr = checkSearchIndex(tx)
				_ = tx.Rollback()
				if txErr == nil && broken {
					txErr = fmt.Errorf("搜索索引不完整")
				}
			}
			err = txErr
		}
		closeErr := copyDB.Close()
		if err == nil {
			err = closeErr
		}
	}
	if err != nil {
		return "", fmt.Errorf("备份校验失败,旧备份已保留: %w", err)
	}
	if err := os.Rename(dst, filepath.Join(dir, name)); err != nil {
		return "", fmt.Errorf("保存已验证备份失败: %w", err)
	}
	if keep > 0 {
		list := ListBackups(dataDir)
		for i := keep; i < len(list); i++ { // list 按时间从新到旧
			_ = os.Remove(filepath.Join(dir, list[i].Name))
		}
	}
	return name, nil
}

// ListBackups 现有的备份,从新到旧。
func ListBackups(dataDir string) []BackupInfo {
	ents, err := os.ReadDir(BackupDir(dataDir))
	if err != nil {
		return nil
	}
	var out []BackupInfo
	for _, e := range ents {
		n := e.Name()
		if e.IsDir() || !strings.HasPrefix(n, backupPrefix) || !strings.HasSuffix(n, backupSuffix) {
			continue
		}
		if fi, err := e.Info(); err == nil {
			out = append(out, BackupInfo{Name: n, Size: fi.Size(), Created: fi.ModTime().Unix()})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name > out[j].Name })
	return out
}

// ValidBackupName 防止路径穿越:只接受我们自己生成的文件名。
func ValidBackupName(n string) bool {
	return strings.HasPrefix(n, backupPrefix) && strings.HasSuffix(n, backupSuffix) && !strings.ContainsAny(n, `/\:`) && !strings.Contains(n, "..")
}

const restoreMarker = "restore-pending.txt"

// MarkRestore 登记"下次启动时用这份备份还原"(服务正在使用数据库,不能直接替换文件)。
func MarkRestore(dataDir, name string) error {
	if !ValidBackupName(name) {
		return fmt.Errorf("备份名无效")
	}
	if _, err := os.Stat(filepath.Join(BackupDir(dataDir), name)); err != nil {
		return fmt.Errorf("找不到这份备份")
	}
	return os.WriteFile(filepath.Join(dataDir, restoreMarker), []byte(name), 0o600)
}

// ApplyPendingRestore 在打开数据库之前调用:有待还原的备份就把现在的库改名留底(hub.sqlite.before-restore),再把备份复制成 hub.sqlite。
// 返回还原用的备份名(没有待还原的返回空)。
func ApplyPendingRestore(dataDir string) (string, error) {
	mp := filepath.Join(dataDir, restoreMarker)
	b, err := os.ReadFile(mp)
	if err != nil {
		return "", nil
	}
	_ = os.Remove(mp)
	name := strings.TrimSpace(string(b))
	if !ValidBackupName(name) {
		return "", fmt.Errorf("待还原的备份名无效:%q", name)
	}
	src := filepath.Join(BackupDir(dataDir), name)
	in, err := os.Open(src)
	if err != nil {
		return "", err
	}
	defer in.Close()
	cur := filepath.Join(dataDir, "hub.sqlite")
	_ = os.Remove(cur + ".before-restore")
	if _, err := os.Stat(cur); err == nil {
		if err := os.Rename(cur, cur+".before-restore"); err != nil {
			return "", err
		}
	}
	_ = os.Remove(cur + "-wal")
	_ = os.Remove(cur + "-shm")
	out, err := os.Create(cur)
	if err != nil {
		return "", err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return "", err
	}
	return name, out.Close()
}
