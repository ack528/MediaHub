package tunnel

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"mediahub/internal/config"
)

// 子进程助手:测试二进制自己当"穿透客户端"用。TUNNEL_HELPER=run:打印几行日志(含令牌,用来验证脱敏)后一直睡;=exit:打印后立刻退出。
func TestMain(m *testing.M) {
	switch os.Getenv("TUNNEL_HELPER") {
	case "run":
		fmt.Println("[I] start frpc")
		fmt.Println("[I] token is secret-token-123 ok")
		fmt.Println("[I] login to server success, get run id [abc]")
		time.Sleep(time.Hour)
		return
	case "exit":
		fmt.Println("[I] bye")
		return
	}
	os.Exit(m.Run())
}

func logger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestFrpTOML(t *testing.T) {
	c := config.Default().Tunnel
	c.ServerAddr, c.Token, c.RemotePort, c.Protocol = "relay.example.com", `tok"en`, 6001, "quic"
	c.BandwidthLimit = "2MB"
	s := FrpTOML(c, 8480)
	for _, want := range []string{
		`serverAddr = "relay.example.com"`, "serverPort = 7000", `auth.token = "tok\"en"`, `transport.protocol = "quic"`,
		"transport.tcpMux = true", "transport.poolCount = 5", "transport.heartbeatInterval = 10", "transport.heartbeatTimeout = 30",
		"loginFailExit = false", "[[proxies]]", `type = "tcp"`, "localPort = 8480", "remotePort = 6001",
		"transport.useEncryption = false", "transport.useCompression = false", `transport.bandwidthLimit = "2MB"`,
	} {
		if !strings.Contains(s, want) {
			t.Errorf("TOML 缺少 %q:\n%s", want, s)
		}
	}
	c.ProxyType, c.CustomDomain, c.Subdomain = "https", "nas.example.com", "nas"
	s = FrpTOML(c, 8480)
	if !strings.Contains(s, `type = "https"`) || !strings.Contains(s, `customDomains = ["nas.example.com"]`) || !strings.Contains(s, `subdomain = "nas"`) || strings.Contains(s, "remotePort") {
		t.Errorf("https 类型配置不对:\n%s", s)
	}
}

func TestSplitArgs(t *testing.T) {
	got := SplitArgs(`-c "C:\Program Files\x\a.toml" --port {port}  -v`)
	want := []string{"-c", `C:\Program Files\x\a.toml`, "--port", "{port}", "-v"}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("SplitArgs = %q", got)
	}
}

func customCfg(mode string) config.Tunnel {
	c := config.Default().Tunnel
	c.Enabled, c.Type, c.Command, c.Args = true, "custom", os.Args[0], "-test.run=NONE"
	c.Token = "secret-token-123"
	return c
}

func waitState(t *testing.T, m *Manager, want string, d time.Duration) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if m.Status(false).State == want {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("状态没有变成 %s,当前 %+v", want, m.Status(true))
}

func TestSuperviseRunMaskStopRestart(t *testing.T) {
	t.Setenv("TUNNEL_HELPER", "run")
	m := New(customCfg("run"), 8480, true, t.TempDir(), logger())
	m.backoffMin, m.backoffMax = 100*time.Millisecond, 200*time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	m.Run(ctx)
	waitState(t, m, "running", 10*time.Second)
	st := m.Status(true)
	if st.PID == 0 {
		t.Fatal("应该有 pid")
	}
	joined := strings.Join(st.Logs, "\n")
	if strings.Contains(joined, "secret-token-123") || !strings.Contains(joined, "token is *** ok") {
		t.Fatalf("日志里的令牌必须脱敏:\n%s", joined)
	}
	// 手动停止:进程被结束,不会自动拉起
	m.Stop()
	waitState(t, m, "stopped", 3*time.Second)
	time.Sleep(600 * time.Millisecond)
	if m.Status(false).PID != 0 || m.Status(false).State != "stopped" {
		t.Fatalf("停止后不该自动拉起: %+v", m.Status(false))
	}
	// 再启动
	m.Start()
	waitState(t, m, "running", 10*time.Second)
	// 重启:换一个新进程
	old := m.Status(false).PID
	m.Restart()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if s := m.Status(false); s.State == "running" && s.PID != 0 && s.PID != old {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("重启后应该是新进程: %+v", m.Status(false))
}

func TestSuperviseRestartsOnExit(t *testing.T) {
	t.Setenv("TUNNEL_HELPER", "exit")
	m := New(customCfg("exit"), 8480, true, t.TempDir(), logger())
	m.backoffMin, m.backoffMax = 80*time.Millisecond, 160*time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	m.Run(ctx)
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if m.Status(false).Restarts >= 2 {
			return // 进程退出后被自动重新拉起了至少两次
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("进程退出后应该自动重启: %+v", m.Status(true))
}

func TestMissingBinaryAndDisabled(t *testing.T) {
	c := config.Default().Tunnel
	c.Enabled, c.Type, c.ServerAddr, c.Binary = true, "frp", "x.example.com", `Z:\no\such\frpc.exe`
	m := New(c, 8480, true, t.TempDir(), logger())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	m.Run(ctx)
	waitState(t, m, "missing", 3*time.Second)
	if !strings.Contains(m.Status(false).Message, "找不到穿透客户端程序") {
		t.Fatalf("message: %s", m.Status(false).Message)
	}
	d := New(config.Default().Tunnel, 8480, true, t.TempDir(), logger())
	d.Run(ctx)
	if d.Status(false).State != "disabled" {
		t.Fatalf("没启用应该是 disabled: %s", d.Status(false).State)
	}
}

func TestProbeAndAutoRestart(t *testing.T) {
	// "外网地址":一个 HTTPS 服务,返回 200
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(`{}`)) }))
	defer srv.Close()
	c := customCfg("run")
	c.PublicAddr = strings.TrimPrefix(srv.URL, "https://")
	m := New(c, 8480, true, t.TempDir(), logger())
	p := m.ProbeOnce(context.Background())
	if !p.OK || p.ConnectMs < 0 || p.TTFBMs < 0 {
		t.Fatalf("探测应该成功: %+v", p)
	}
	// 地址不通:失败并给出原因
	c2 := c
	c2.PublicAddr = "127.0.0.1:1"
	if p := New(c2, 8480, true, t.TempDir(), logger()).ProbeOnce(context.Background()); p.OK || p.Err == "" {
		t.Fatalf("不通的地址应该失败: %+v", p)
	}

	// 连续探测失败 → 自动重启穿透进程
	t.Setenv("TUNNEL_HELPER", "run")
	c3 := customCfg("run")
	c3.PublicAddr, c3.HealthCheck, c3.RestartOnFail = "127.0.0.1:1", true, true
	m3 := New(c3, 8480, true, t.TempDir(), logger())
	m3.probeEvery, m3.probeFails, m3.minUptimeForRestart = 100*time.Millisecond, 2, 200*time.Millisecond
	m3.backoffMin, m3.backoffMax = 50*time.Millisecond, 100*time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	m3.Run(ctx)
	waitState(t, m3, "running", 10*time.Second)
	first := m3.Status(false).PID
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if s := m3.Status(false); s.Restarts >= 1 && s.PID != 0 && s.PID != first {
			if len(s.Probes) < 2 {
				t.Fatal("应该记录了探测结果")
			}
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("连续探测失败后应该自动重启: %+v", m3.Status(true))
}
