package main

import (
	"context"
	"database/sql"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"mediahub/internal/store"
)

// dbMaintenance 数据库日常维护:
//   - PRAGMA optimize:更新查询统计信息,让 SQLite 选对索引(启动时一次,之后每 6 小时);
//   - 每两天自动备份(VACUUM INTO,不停服务),保留最近 2 份。
func dbMaintenance(ctx context.Context, db *sql.DB, dataDir string, log *slog.Logger, idle func() time.Duration) {
	run := func() {
		if _, err := db.Exec(`PRAGMA optimize`); err != nil {
			log.Debug("PRAGMA optimize 失败", "err", err)
		}
		list := store.ListBackups(dataDir)
		if len(list) == 0 || time.Since(time.Unix(list[0].Created, 0)) >= 48*time.Hour {
			name, err := store.Backup(db, dataDir, store.BackupRetention)
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
	// 后台检查:日常启动不再做完整检查,改成运行中隔很久、趁没人用的时候悄悄查一次(只读,不挡请求)。发现问题就登记"下次启动完整检查并修复"
	lastCheck := time.Time{}
	if b, err := os.ReadFile(filepath.Join(dataDir, "last-bgcheck")); err == nil {
		if ts, e := strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64); e == nil {
			lastCheck = time.Unix(ts, 0)
		}
	}
	started := time.Now()
	check := func() {
		// 整库检查要读完整个数据库(大库上要占满一个核好几分钟):一个月一次,而且只在启动一小时之后、已经 15 分钟没有客户端在用的时候才做
		if time.Since(lastCheck) < 30*24*time.Hour || time.Since(started) < time.Hour || idle() < 15*time.Minute {
			return
		}
		t0 := time.Now()
		err := store.BackgroundCheck(ctx, db)
		switch {
		case err == nil:
			lastCheck = time.Now()
			_ = os.WriteFile(filepath.Join(dataDir, "last-bgcheck"), []byte(strconv.FormatInt(lastCheck.Unix(), 10)), 0o600)
			log.Info("数据库后台检查通过", "took", time.Since(t0).Round(time.Second).String())
		case ctx.Err() != nil:
		default:
			log.Error("数据库后台检查发现问题,下次启动会做完整检查并修复", "err", err)
			store.RequestCheck(dataDir, "后台检查发现问题:"+err.Error())
		}
	}
	check()
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			run()
			check()
		}
	}
}
