package api

import "net/http"

// POST /api/v1/admin/config/reload —— 管理程序保存了配置文件之后调用:能立刻生效的设置马上生效(见 cmd/mediahub/reload.go),
// 返回哪些已生效、哪些还要重启服务。
func (s *Server) adminConfigReload(w http.ResponseWriter, r *http.Request) {
	if s.Reload == nil {
		writeErr(w, 503, "reload.unavailable", "此服务不支持热更新配置")
		return
	}
	s.configMu.Lock() // 和手机端保存配置互斥
	defer s.configMu.Unlock()
	res, err := s.Reload()
	if err != nil {
		writeErr(w, 500, "reload.failed", err.Error())
		return
	}
	writeJSON(w, 200, res)
}
