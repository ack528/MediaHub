package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"

	"modernc.org/sqlite"
)

// IsCorruption recognizes SQLite's primary and extended corruption codes,
// including SQLITE_CORRUPT_INDEX (779). Busy, full and I/O errors are not corruption.
func IsCorruption(err error) bool {
	var coded interface{ Code() int }
	if errors.As(err, &coded) {
		return coded.Code()&255 == 11 || coded.Code()&255 == 26
	}
	var integrity *integrityError
	return errors.As(err, &integrity)
}

type integrityError struct{ detail string }

func (e *integrityError) Error() string { return "数据库完整性检查失败: " + e.detail }

// integrityCheck must drain rows and inspect their contents: SQLite reports many
// integrity failures as result rows, not as a driver error. quick_check omits
// missing index entries such as the reported ix_m_gen failure.
func integrityCheck(q interface {
	Query(string, ...any) (*sql.Rows, error)
}) error {
	rows, err := q.Query("PRAGMA integrity_check")
	if err != nil {
		return err
	}
	defer rows.Close()
	var problems []string
	seen := false
	for rows.Next() {
		var result string
		if err := rows.Scan(&result); err != nil {
			return err
		}
		seen = true
		if result != "ok" {
			problems = append(problems, result)
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if !seen {
		return fmt.Errorf("数据库完整性检查没有返回结果")
	}
	if len(problems) > 0 {
		return &integrityError{strings.Join(problems, "; ")}
	}
	return nil
}

// ensureDatabase runs before migrations/workers, with the data-directory lock
// held. All repairs and their validation share one immediate transaction. We
// never salvage/drop application tables or silently restore an older backup.
func ensureDatabase(db *sql.DB, path string) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	checkErr := integrityCheck(tx)
	if checkErr != nil && !IsCorruption(checkErr) {
		return fmt.Errorf("检查数据库: %w", checkErr)
	}
	var hasSearch int
	if err := tx.QueryRow(`SELECT count(*) FROM sqlite_schema WHERE name='media_fts' AND type='table'`).Scan(&hasSearch); err != nil {
		return fmt.Errorf("读取数据库结构: %w", err)
	}
	var hasMedia int
	if err := tx.QueryRow(`SELECT count(*) FROM sqlite_schema WHERE name='media' AND type='table'`).Scan(&hasMedia); err != nil {
		return fmt.Errorf("读取媒体表结构: %w", err)
	}
	brokenSearch := hasMedia != 0 && hasSearch == 0
	if checkErr == nil && hasSearch != 0 {
		brokenSearch, err = checkSearchIndex(tx)
		if err != nil {
			return err
		}
	}
	if checkErr == nil && !brokenSearch {
		return tx.Commit()
	}

	// A separate read connection can copy committed pages while this immediate
	// transaction excludes other writers. Unlike VACUUM INTO, the backup API
	// preserves index pages, including the damage and committed WAL contents.
	snapshot, err := preserveBeforeRepair(db, path)
	if err != nil {
		return fmt.Errorf("无法保存修复前副本,未执行修复: %w", err)
	}
	slog.Warn("数据库开始自修复", "snapshot", snapshot, "integrity", checkErr, "searchMismatch", brokenSearch)
	fail := func(err error) error {
		return fmt.Errorf("数据库自修复失败,事务已回滚; 修复前副本 %s: %w", snapshot, err)
	}
	if checkErr != nil {
		if _, err := tx.Exec("REINDEX"); err != nil {
			return fail(fmt.Errorf("重建普通索引: %w", err))
		}
	}
	if hasMedia != 0 {
		if err := rebuildSearchIndex(tx); err != nil {
			return fail(err)
		}
	}
	if err := integrityCheck(tx); err != nil {
		return fail(err)
	}
	if hasMedia != 0 {
		broken, err := checkSearchIndex(tx)
		if err != nil {
			return fail(err)
		}
		if broken {
			return fail(fmt.Errorf("重建后搜索索引仍不一致"))
		}
	}
	if err := tx.Commit(); err != nil {
		return fail(err)
	}
	slog.Info("数据库自修复完成,完整性检查通过", "snapshot", snapshot)
	return nil
}

func preserveBeforeRepair(db *sql.DB, path string) (string, error) {
	dir := filepath.Join(filepath.Dir(path), "repair")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	f, err := os.CreateTemp(dir, "before-repair-*.sqlite")
	if err != nil {
		return "", err
	}
	dst := f.Name()
	if err := f.Close(); err != nil {
		return "", err
	}
	conn, err := db.Conn(context.Background())
	if err != nil {
		return "", err
	}
	defer conn.Close()
	err = conn.Raw(func(raw any) error {
		b, err := raw.(interface {
			NewBackup(string) (*sqlite.Backup, error)
		}).NewBackup(dst)
		if err != nil {
			return err
		}
		_, stepErr := b.Step(-1)
		finishErr := b.Finish()
		return errors.Join(stepErr, finishErr)
	})
	if err != nil {
		return "", fmt.Errorf("保存页级副本 %s: %w", dst, err)
	}
	f, err = os.OpenFile(dst, os.O_RDWR, 0)
	if err != nil {
		return "", err
	}
	err = f.Sync()
	return dst, errors.Join(err, f.Close())
}
