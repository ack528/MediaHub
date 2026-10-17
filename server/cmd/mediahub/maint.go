package main

import (
	"context"
	"database/sql"
	"log/slog"
	"time"

	"mediahub/internal/store"
)

// dbMaintenance 数据库日常维护:
//   - PRAGMA optimize:更新查询统计信息,让 SQLite 选对索引(启动时一次,之后每 6 小时);
//   - 每日自动备份(VACUUM INTO,不停服务),保留最近 7 份;距离上次备份不到 24 小时就不重复备份。
func dbMaintenance(ctx context.Context, db *sql.DB, dataDir string, log *slog.Logger) {
	run := func() {
		if _, err := db.Exec(`PRAGMA optimize`); err != nil {
			log.Debug("PRAGMA optimize 失败", "err", err)
		}
		list := store.ListBackups(dataDir)
		if len(list) == 0 || time.Since(time.Unix(list[0].Created, 0)) > 24*time.Hour {
			name, err := store.Backup(db, dataDir, 7)
			if err != nil {
				log.Warn("数据库自动备份失败", "err", err)
				return
			}
			log.Info("数据库已自动备份", "file", name)
		}
	}
	select {
	case <-ctx.Done():
		return
	case <-time.After(2 * time.Minute): // 启动后先让扫描等重活过去
	}
	run()
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			run()
		}
	}
}
