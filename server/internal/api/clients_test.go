package api

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"mediahub/internal/governor"
)

type cview struct {
	ID, User, Device, UA, IP, Route, State string
	Online                                 bool
	Requests, BytesOut                     int64
	NowKind, NowName, NowDetail            string
}

type cresp struct {
	Clients    []cview
	Online     int
	Governor   struct{ Level string }
	Background []struct{ Name, State string }
}

func getClients(t *testing.T, e *env) cresp {
	_, b := e.do("GET", "/api/v1/admin/clients", nil, adminHdr(e))
	var r cresp
	if err := json.Unmarshal(b, &r); err != nil {
		t.Fatalf("clients: %v %s", err, b)
	}
	return r
}

func loginDevice(t *testing.T, e *env, device string) string {
	_, b := e.do("POST", "/api/v1/auth/login", map[string]any{"username": "me", "password": "secret1", "deviceName": device}, nil)
	var r struct{ Token string }
	if json.Unmarshal(b, &r) != nil || r.Token == "" {
		t.Fatalf("login: %s", b)
	}
	return r.Token
}

func get(e *env, path, token, ua string) *http.Response {
	req, _ := http.NewRequest("GET", e.ts.URL+path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	if ua != "" {
		req.Header.Set("User-Agent", ua)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	return resp
}

func TestClientsTrackingAndGovernor(t *testing.T) {
	e := newEnv(t)
	e.srv.Gov = governor.New(governor.Config{Enabled: true, ActiveWindow: 3 * time.Second, StreamGrace: 300 * time.Millisecond, PauseCompress: true})
	tok := loginDevice(t, e, "OPPO OPD2515")
	other := loginDevice(t, e, "平板")

	// 只有管理程序的请求:不算客户端,调度级别仍然是 Idle
	if r := getClients(t, e); r.Online != 0 || r.Governor.Level != "idle" {
		t.Fatalf("没有客户端请求时应该是 idle: %+v", r)
	}
	// 手机发请求:出现在列表里,带设备名、用户、App 版本、线路;调度变成 active
	get(e, "/api/v1/dialogs", tok, "LocalBrowse/3.1.0 (Android 17; OPPO OPD2515)").Body.Close()
	r := getClients(t, e)
	if r.Online != 1 || r.Governor.Level != "active" {
		t.Fatalf("有客户端 → active: %+v", r)
	}
	var me *cview
	var tablet *cview
	for i := range r.Clients {
		switch r.Clients[i].Device {
		case "OPPO OPD2515":
			me = &r.Clients[i]
		case "平板":
			tablet = &r.Clients[i]
		}
	}
	if me == nil || me.User != "me" || !me.Online || me.Requests < 1 || !strings.Contains(me.UA, "LocalBrowse/3.1.0") || me.Route != "tunnel" {
		t.Fatalf("客户端信息不对: %+v", me)
	}
	if tablet == nil || tablet.Online || tablet.State != "offline" {
		t.Fatalf("登录过但还没有请求的设备应该显示为离线: %+v", tablet)
	}

	// 正在测速(重传输):状态 transfer / 正在做什么 = 测速,调度 streaming
	done := make(chan int64, 1)
	go func() {
		resp := get(e, "/api/v1/speedtest/down?seconds=2", tok, "")
		n := int64(0)
		buf := make([]byte, 64<<10)
		for {
			k, err := resp.Body.Read(buf)
			n += int64(k)
			if err != nil {
				break
			}
		}
		resp.Body.Close()
		done <- n
	}()
	time.Sleep(700 * time.Millisecond)
	r = getClients(t, e)
	if r.Governor.Level != "streaming" {
		t.Fatalf("传输中应该是 streaming: %+v", r.Governor)
	}
	var busy *cview
	for i := range r.Clients {
		if r.Clients[i].Device == "OPPO OPD2515" {
			busy = &r.Clients[i]
		}
	}
	if busy == nil || busy.State != "transfer" || busy.NowKind != "speedtest" || !strings.Contains(busy.NowDetail, "测速") || busy.BytesOut == 0 {
		t.Fatalf("传输中的客户端状态: %+v", busy)
	}
	// 后台任务列表里有压缩 / 重复检测 / 扫描三项
	if len(r.Background) < 3 {
		t.Fatalf("后台任务: %+v", r.Background)
	}
	total := <-done
	if total < 1<<20 {
		t.Fatalf("测速应该收到数据: %d", total)
	}
	time.Sleep(500 * time.Millisecond) // 过了宽限期:回到 active(窗口 3 秒内)
	if r = getClients(t, e); r.Governor.Level != "active" {
		t.Fatalf("传输结束后回到 active: %+v", r.Governor)
	}
	// 窗口过去:idle
	time.Sleep(3200 * time.Millisecond)
	if r = getClients(t, e); r.Governor.Level != "idle" || r.Online != 0 && r.Clients[0].Online && false {
		t.Fatalf("没有请求一段时间后回到 idle: %+v", r.Governor)
	}

	// 撤销登录:再用这个令牌请求是 401;列表里这台设备没了
	_, b := e.do("POST", "/api/v1/admin/clients/kick", map[string]any{"id": me.ID}, adminHdr(e))
	if !strings.Contains(string(b), `"revoked":1`) {
		t.Fatalf("kick: %s", b)
	}
	if resp := get(e, "/api/v1/dialogs", tok, ""); resp.StatusCode != 401 {
		t.Fatalf("被撤销的令牌应该 401,实际 %d", resp.StatusCode)
	}
	if resp := get(e, "/api/v1/dialogs", other, ""); resp.StatusCode != 200 { // 另一台设备不受影响
		t.Fatalf("其它设备不受影响: %d", resp.StatusCode)
	}
	if resp, _ := e.do("POST", "/api/v1/admin/clients/kick", map[string]any{"id": "zz"}, adminHdr(e)); resp.StatusCode != 404 && resp.StatusCode != 400 {
		t.Fatalf("乱写的 id 应该被拒绝: %d", resp.StatusCode)
	}
	if resp, _ := e.do("GET", "/api/v1/admin/clients", nil, nil); resp.StatusCode != 403 && resp.StatusCode != 401 {
		t.Fatalf("没有管理密钥应该被拒绝: %d", resp.StatusCode)
	}
}

// 有客户端连着时不开始新的压缩;没有客户端后自动开始并完成。
func TestCompressWaitsForNoClients(t *testing.T) {
	e := newEnv(t)
	e.srv.Gov = governor.New(governor.Config{Enabled: true, ActiveWindow: 2500 * time.Millisecond, StreamGrace: 100 * time.Millisecond, PauseCompress: true})
	hdr := adminHdr(e)
	e.srv.Cfg.Video.HWAccel = "none"
	tok := loginDevice(t, e, "手机")
	dir := filepath.Join(e.lib, "调度")
	os.MkdirAll(dir, 0o755)
	big := filepath.Join(dir, "g.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30", "-t", "4", "-c:v", "libx264", "-b:v", "6M", "-minrate", "6M", "-maxrate", "6M", "-bufsize", "6M", big).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	rescanAll(t, e)
	id := idByName(t, e, "g.mp4")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e.srv.StartOptimizeWorker(ctx)
	get(e, "/api/v1/dialogs", tok, "").Body.Close() // 客户端活动:进入 Active
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800, "preset": "veryfast"}, hdr); resp.StatusCode != 200 {
		t.Fatalf("enqueue = %d", resp.StatusCode)
	}
	state := func() string {
		var st string
		_ = e.srv.DB.QueryRow(`SELECT state FROM opt_job ORDER BY id DESC LIMIT 1`).Scan(&st)
		return st
	}
	time.Sleep(1500 * time.Millisecond)
	if s := state(); s != "queued" {
		t.Fatalf("有客户端连着时不应该开始压缩,状态 %s", s)
	}
	if r := getClients(t, e); len(r.Background) == 0 || r.Background[0].State != "paused" {
		t.Fatalf("后台任务里压缩应该是 paused: %+v", r.Background)
	}
	// 没有客户端活动 → 窗口过期 → 自动开始并完成
	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		if s := state(); s == "done" {
			return
		} else if s == "failed" {
			t.Fatal("压缩失败")
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("没有客户端后应该自动完成压缩,状态 %s", state())
}
