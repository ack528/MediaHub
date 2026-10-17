package api

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"runtime/pprof"
	"strconv"
	"time"

	"mediahub/internal/store"
)

// 管理接口(只接受本机回环 + 管理密钥):数据库备份 / 还原、CPU 性能剖析。

// GET /api/v1/admin/backup —— 现有的备份(从新到旧)。
func (s *Server) adminBackupList(w http.ResponseWriter, r *http.Request) {
	list := store.ListBackups(s.Cfg.DataDir)
	if list == nil {
		list = []store.BackupInfo{}
	}
	writeJSON(w, 200, map[string]any{"backups": list, "dir": store.BackupDir(s.Cfg.DataDir)})
}

// POST /api/v1/admin/backup/run —— 立刻备份一份。
func (s *Server) adminBackupRun(w http.ResponseWriter, r *http.Request) {
	name, err := store.Backup(s.DB, s.Cfg.DataDir, 7)
	if err != nil {
		writeErr(w, 500, "backup.failed", err.Error())
		return
	}
	s.Log.Info("数据库已备份", "file", name)
	writeJSON(w, 200, map[string]any{"name": name})
}

// POST /api/v1/admin/backup/restore {name} —— 登记"下次启动时用这份备份还原",由管理程序随后重启服务。
func (s *Server) adminBackupRestore(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name string `json:"name"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in) != nil {
		writeErr(w, 400, "bad.request", "请求格式错误")
		return
	}
	if err := store.MarkRestore(s.Cfg.DataDir, in.Name); err != nil {
		writeErr(w, 400, "backup.invalid", err.Error())
		return
	}
	s.Log.Warn("已登记数据库还原,重启服务后生效", "file", in.Name)
	writeJSON(w, 200, map[string]any{"pending": in.Name})
}

// POST /api/v1/admin/pprof/cpu?seconds=30 —— 录一段 CPU 剖析,写到 <数据目录>\logs\cpu-时间.pprof(随诊断包导出),
// 返回文件路径。用 `go tool pprof` 打开;遇到"某个操作很慢"时,能直接看到时间花在哪个函数上。同一时间只能录一个。
var profiling = make(chan struct{}, 1)

func (s *Server) adminPprofCPU(w http.ResponseWriter, r *http.Request) {
	sec, _ := strconv.Atoi(r.URL.Query().Get("seconds"))
	if sec < 5 {
		sec = 30
	}
	if sec > 120 {
		sec = 120
	}
	select {
	case profiling <- struct{}{}:
		defer func() { <-profiling }()
	default:
		writeErr(w, 409, "pprof.busy", "已经有一个剖析在录制")
		return
	}
	dir := filepath.Join(s.Cfg.DataDir, "logs")
	_ = os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, "cpu-"+time.Now().Format("20060102-150405")+".pprof")
	f, err := os.Create(path)
	if err != nil {
		writeErr(w, 500, "pprof.failed", err.Error())
		return
	}
	if err := pprof.StartCPUProfile(f); err != nil {
		f.Close()
		writeErr(w, 500, "pprof.failed", err.Error())
		return
	}
	select {
	case <-time.After(time.Duration(sec) * time.Second):
	case <-r.Context().Done():
	}
	pprof.StopCPUProfile()
	f.Close()
	s.Log.Info("CPU 剖析已保存", "file", path, "seconds", sec)
	writeJSON(w, 200, map[string]any{"path": path, "seconds": sec})
}
