package api

import (
	"context"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// GET /api/v1/server/addresses(需要登录)—— 这台服务器在各个网络上的地址。手机据此在"和服务器同一个局域网"时自动改走内网直连
// (比经过内网穿透服务器快得多,也不占穿透服务器的带宽),离开家里的网络再自动切回外网地址。
// 不放在不需要登录的 /server/info 里:内网地址属于内部网络结构,只给登录过的手机看。
func (s *Server) serverAddresses(w http.ResponseWriter, r *http.Request) {
	port := "8480"
	if i := strings.LastIndex(s.Cfg.Listen, ":"); i >= 0 {
		port = s.Cfg.Listen[i+1:]
	}
	var lan, v6 []string
	ifs, _ := net.Interfaces()
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip := ipn.IP
			switch {
			case ip.To4() != nil && ip.IsPrivate() && !ip.IsLinkLocalUnicast():
				lan = append(lan, ip.String()+":"+port)
			case ip.To4() == nil && ip.IsGlobalUnicast() && !ip.IsPrivate() && !ip.IsLinkLocalUnicast():
				v6 = append(v6, "["+ip.String()+"]:"+port)
			}
		}
	}
	pub := ""
	if s.Cfg.Tunnel.Enabled {
		pub = s.Cfg.Tunnel.PublicAddr
	}
	writeJSON(w, 200, map[string]any{"lan": lan, "ipv6": v6, "public": pub, "tls": s.TLSFingerprint != ""})
}

// GET /api/v1/admin/tunnel[?logs=1] —— 内网穿透的状态(管理程序用)。
func (s *Server) adminTunnelStatus(w http.ResponseWriter, r *http.Request) {
	if s.Tunnel == nil {
		writeJSON(w, 200, map[string]any{"enabled": false, "state": "disabled", "message": "没有启用内网穿透", "probes": []any{}})
		return
	}
	writeJSON(w, 200, s.Tunnel.Status(r.URL.Query().Get("logs") != ""))
}

// POST /api/v1/admin/tunnel/{start|stop|restart|probe}
func (s *Server) adminTunnelAction(w http.ResponseWriter, r *http.Request) {
	if s.Tunnel == nil {
		writeErr(w, 400, "tunnel.disabled", "没有启用内网穿透(在 配置 → 网络 → 内网穿透 里打开并重启服务)")
		return
	}
	switch r.PathValue("action") {
	case "start":
		s.Tunnel.Start()
	case "stop":
		s.Tunnel.Stop()
	case "restart":
		s.Tunnel.Restart()
	case "probe":
		ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
		defer cancel()
		writeJSON(w, 200, s.Tunnel.Probe(ctx))
		return
	default:
		writeErr(w, 404, "notfound", "未知操作")
		return
	}
	w.WriteHeader(204)
}

var _ = strconv.Itoa
