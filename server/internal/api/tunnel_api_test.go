package api

import (
	"encoding/json"
	"testing"
)

func TestTunnelAPIAndAddresses(t *testing.T) {
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	// 没启用穿透:状态是 disabled,操作返回 400
	var st struct {
		Enabled bool
		State   string
	}
	_, b := e.do("GET", "/api/v1/admin/tunnel", nil, hdr)
	if err := json.Unmarshal(b, &st); err != nil || st.Enabled || st.State != "disabled" {
		t.Fatalf("status: %s", b)
	}
	if resp, _ := e.do("POST", "/api/v1/admin/tunnel/restart", nil, hdr); resp.StatusCode != 400 {
		t.Fatalf("没启用时操作应该 400,实际 %d", resp.StatusCode)
	}
	// 没有管理密钥:拒绝
	if resp, _ := e.do("GET", "/api/v1/admin/tunnel", nil, nil); resp.StatusCode != 403 && resp.StatusCode != 401 {
		t.Fatalf("没有管理密钥应该被拒绝: %d", resp.StatusCode)
	}
	// 本机地址:需要登录;返回 JSON 里有 lan / ipv6 / public / tls
	if resp, _ := e.do("GET", "/api/v1/server/addresses", nil, map[string]string{"Authorization": ""}); resp.StatusCode != 401 {
		t.Fatalf("addresses 需要登录: %d", resp.StatusCode)
	}
	var ad struct {
		LAN    []string `json:"lan"`
		IPv6   []string `json:"ipv6"`
		Public string   `json:"public"`
	}
	resp, b := e.do("GET", "/api/v1/server/addresses", nil, nil)
	if resp.StatusCode != 200 || json.Unmarshal(b, &ad) != nil {
		t.Fatalf("addresses: %d %s", resp.StatusCode, b)
	}
	if ad.Public != "" {
		t.Fatal("没启用穿透时 public 应为空")
	}
	for _, a := range ad.LAN {
		if len(a) < 7 {
			t.Fatalf("内网地址格式不对: %q", a)
		}
	}
	e.srv.Cfg.Tunnel.Enabled, e.srv.Cfg.Tunnel.PublicAddr = true, "nas.example.com:6001"
	_, b = e.do("GET", "/api/v1/server/addresses", nil, nil)
	_ = json.Unmarshal(b, &ad)
	if ad.Public != "nas.example.com:6001" {
		t.Fatalf("启用穿透后应带上外网地址: %s", b)
	}
}
