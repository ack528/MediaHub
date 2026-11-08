package api

import (
	"net/http"

	"mediahub/internal/tunnel"
)

// transportCaps 这台服务器(和它的穿透)能用哪些传输协议。手机据此显示"当前连接方式",以后切换到基于 UDP 的低延迟协议时也从这里判断。
//
//	http1 / http2   TCP 上的 HTTP/1.1、HTTP/2(开了 HTTPS 才有 h2);
//	http3 / quic    HTTP/3(QUIC):标准库没有实现,还没支持(规划见 docs/24-UDP协议支持评估.md);
//	tunnelProtocol  frp 客户端到穿透服务器之间用的协议(tcp / kcp / quic / websocket / wss),kcp 和 quic 是 UDP。
func (s *Server) transportCaps() map[string]any {
	proto, udp := "", false
	if t := s.tunnelMgr(); t != nil {
		proto = t.Protocol()
		udp = tunnel.UDPProtocol(proto)
	}
	return map[string]any{
		"http1": true, "http2": s.TLSFingerprint != "", "http3": false, "quic": false, "udp": false,
		"tunnelProtocol": proto, "tunnelUDP": udp,
	}
}

// GET /api/v1/server/link[?watch=1](需要登录)—— 手机设置里的"连接质量":服务器这边看到的穿透状态和实时延迟。
// 带 watch=1 就登记"有人在看",服务端开始每 2 秒采样一次(40 秒内没人再看就停,不白占穿透服务器的流量)。不含任何地址 / 令牌。
func (s *Server) serverLink(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{"transport": s.transportCaps(), "tunnel": nil}
	if t := s.tunnelMgr(); t != nil {
		if r.URL.Query().Get("watch") != "" {
			t.Watch()
		}
		st := t.Status(false)
		out["tunnel"] = map[string]any{
			"enabled": st.Enabled, "type": t.Type(), "state": st.State, "message": st.Message, "restarts": st.Restarts, "latency": t.Latency(),
		}
	}
	writeJSON(w, 200, out)
}
