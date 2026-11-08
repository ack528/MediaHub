package store

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"strings"
)

// 启动自检的取舍:
//
// 完整检查(PRAGMA integrity_check + 搜索索引逐行比对)要读完整个数据库。几十万条媒体记录时要好几分钟,
// 而这段时间服务一直没有开始监听 —— 以前每次改配置重启服务都要等这么久,其实 99% 的启动上次都是正常退出的,根本没必要查。
//
// 所以改成:只在"上次没有正常退出"(崩溃 / 被强杀 / 断电)、"有地方发现了损坏要求检查"、
// "第一次用这个机制(无法确认上次怎么退出的)"或者手动要求时才做完整检查;其余启动只做迁移,几百毫秒就能开始服务。
// 平时由 [BackgroundCheck] 在服务运行时用一个只读连接慢慢查(不挡请求),发现问题就登记"下次启动要完整检查并修复"。

const (
	sessionFile = "session.state" // 内容 "running" = 服务在运行 / 上次没有正常退出;"clean" = 上次正常退出
	checkFile   = "needs-check.flag"
)

// BeginSession 在持有数据目录锁之后、打开数据库之前调用:返回这次启动要不要做完整的数据库检查及原因,并把状态记成 running。
func BeginSession(dataDir string) (verify bool, reason string) {
	state := ""
	if b, err := os.ReadFile(filepath.Join(dataDir, sessionFile)); err == nil {
		state = strings.TrimSpace(string(b))
	}
	_, dbErr := os.Stat(filepath.Join(dataDir, "hub.sqlite"))
	hasDB := dbErr == nil
	switch {
	case os.Getenv("MEDIAHUB_CHECK_DB") == "1":
		verify, reason = true, "环境变量 MEDIAHUB_CHECK_DB=1 要求检查"
	case fileExists(filepath.Join(dataDir, checkFile)):
		why := "之前登记了需要检查"
		if b, err := os.ReadFile(filepath.Join(dataDir, checkFile)); err == nil && strings.TrimSpace(string(b)) != "" {
			why = strings.TrimSpace(string(b))
		}
		verify, reason = true, why
	case !hasDB:
		// 全新安装:没有数据可查
	case state == "running":
		verify, reason = true, "上次服务没有正常退出(崩溃 / 被强制结束 / 断电)"
	case state != "clean":
		verify, reason = true, "第一次使用快速启动,无法确认上次是怎么退出的"
	}
	_ = os.MkdirAll(dataDir, 0o700)
	_ = os.WriteFile(filepath.Join(dataDir, sessionFile), []byte("running"), 0o600)
	return verify, reason
}

// EndSession 服务正常退出(数据库已关闭)时调用:下次启动可以跳过完整检查。
func EndSession(dataDir string) {
	_ = os.WriteFile(filepath.Join(dataDir, sessionFile), []byte("clean"), 0o600)
}

// RequestCheck 登记"下次启动做完整检查并修复"(发现数据库损坏、后台检查失败时调用)。
func RequestCheck(dataDir, why string) {
	_ = os.MkdirAll(dataDir, 0o700)
	_ = os.WriteFile(filepath.Join(dataDir, checkFile), []byte(why), 0o600)
}

// CheckDone 完整检查(和必要的修复)成功之后清掉登记。
func CheckDone(dataDir string) { _ = os.Remove(filepath.Join(dataDir, checkFile)) }

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

// OpenFast 是日常启动用的:打开、迁移到最新模式,不做完整检查。
func OpenFast(path string) (*sql.DB, error) {
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

// BackgroundCheck 在服务运行时检查数据库:PRAGMA quick_check(只检查页和 B 树结构,比完整检查快很多)。
// 只读(不开事务,所以不会像写事务那样挡住别的写入),用单独的一个连接,WAL 模式下不挡读写请求。返回 nil = 没发现问题;ctx 取消时返回 ctx 的错误。
func BackgroundCheck(ctx context.Context, db *sql.DB) error {
	conn, err := db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	rows, err := conn.QueryContext(ctx, `PRAGMA quick_check`)
	if err != nil {
		return err
	}
	var problems []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			rows.Close()
			return err
		}
		if s != "ok" && len(problems) < 5 {
			problems = append(problems, s)
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if IsCorruption(err) {
			return &integrityError{err.Error()}
		}
		return err
	}
	if len(problems) > 0 {
		return &integrityError{strings.Join(problems, "; ")}
	}
	return nil
}
