package store

import (
	"database/sql"
	"fmt"
	"log/slog"
)

// EnsureSearchIndex checks FTS's internal structure and its content against media.
// The search table is derived data; rebuild it atomically without touching IDs,
// accounts, playback positions or compression jobs.
func EnsureSearchIndex(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, checkErr := tx.Exec(`INSERT INTO media_fts(media_fts) VALUES('integrity-check')`)
	var mismatch int
	if checkErr == nil {
		err = tx.QueryRow(`SELECT EXISTS(SELECT 1 FROM media m LEFT JOIN media_fts f ON f.rowid=m.id WHERE f.rowid IS NULL OR f.name<>m.name) OR EXISTS(SELECT 1 FROM media_fts f LEFT JOIN media m ON m.id=f.rowid WHERE m.id IS NULL)`).Scan(&mismatch)
		if err != nil {
			return fmt.Errorf("检查搜索索引: %w", err)
		}
	}
	if checkErr != nil || mismatch != 0 {
		slog.Warn("搜索索引损坏或与媒体不一致,从媒体记录重建", "err", checkErr)
		for _, q := range []string{
			`DROP TABLE media_fts`,
			`CREATE VIRTUAL TABLE media_fts USING fts5(name, tokenize='trigram')`,
			`INSERT INTO media_fts(rowid,name) SELECT id,name FROM media`,
			`INSERT INTO media_fts(media_fts) VALUES('integrity-check')`,
		} {
			if _, err := tx.Exec(q); err != nil {
				return fmt.Errorf("重建搜索索引: %w", err)
			}
		}
	}
	return tx.Commit()
}
