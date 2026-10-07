// Package store 封装 SQLite(纯 Go 驱动 modernc.org/sqlite,无需 cgo)。
package store

import (
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
)

// Open 打开(必要时创建)索引库,启用 WAL 与外键。
func Open(path string) (*sql.DB, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(ON)&_pragma=busy_timeout(60000)&_txlock=immediate", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// 6 个盘同时扫描 + 补元数据各占着连接,8 个不够,接口请求(浏览 / 播放)拿不到连接就要排队等 —— 32 个连接对 SQLite 文件库几乎没有成本
	db.SetMaxOpenConns(32)
	return db, db.Ping()
}

// OpenMigrated 打开并迁移到最新模式。
func OpenMigrated(path string) (*sql.DB, error) {
	db, err := Open(path)
	if err != nil {
		return nil, err
	}
	if err := Migrate(db); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}
