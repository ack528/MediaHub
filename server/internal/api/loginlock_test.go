package api

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// 任何设备登录失败后,所有设备在锁定时间内都不能再验证密码(连正确密码也一样);过了锁定时间恢复;已登录的令牌不受影响。
func TestLoginGlobalLock(t *testing.T) {
	e := newEnv(t)
	e.srv.LoginLock = 700 * time.Millisecond
	login := func(user, pw, device string) (int, string) {
		resp, b := e.do("POST", "/api/v1/auth/login", map[string]any{"username": user, "password": pw, "deviceName": device}, nil)
		return resp.StatusCode, string(b)
	}
	// 先登录一台设备(此时没有锁定)
	st, body := login("me", "secret1", "手机")
	if st != 200 {
		t.Fatalf("初始登录应成功: %d %s", st, body)
	}
	var ok struct{ Token string }
	_ = json.Unmarshal([]byte(body), &ok)

	// 另一台设备输错密码 → 401,并开始全局锁定
	if st, _ := login("me", "wrong", "平板"); st != 401 {
		t.Fatalf("错误密码应 401,实际 %d", st)
	}
	// 锁定期间:正确密码、别的账号、别的设备都是 429,而且不用算密码
	t0 := time.Now()
	for _, c := range [][3]string{{"me", "secret1", "手机"}, {"me", "secret1", "新设备"}, {"nobody", "x", "电脑"}} {
		st, body := login(c[0], c[1], c[2])
		if st != 429 || !strings.Contains(body, "auth.locked") {
			t.Fatalf("锁定期间应 429(%v): %d %s", c, st, body)
		}
	}
	if time.Since(t0) > 300*time.Millisecond {
		t.Fatalf("锁定期间的拒绝应该很快(没做密码计算),用了 %v", time.Since(t0))
	}
	// 已登录的令牌不受影响
	if resp := get(e, "/api/v1/dialogs", ok.Token, ""); resp.StatusCode != 200 {
		t.Fatalf("已登录设备不应受影响: %d", resp.StatusCode)
	}
	// 管理接口能看到锁定状态和最近的失败
	r := getClients(t, e)
	_ = r
	_, b := e.do("GET", "/api/v1/admin/clients", nil, adminHdr(e))
	var ll struct {
		LoginLock struct {
			RemainingSec int
			Failures     int64
			LockSec      int
			Recent       []struct{ User, Device string }
		}
	}
	if err := json.Unmarshal(b, &ll); err != nil || ll.LoginLock.RemainingSec < 1 || ll.LoginLock.Failures != 1 || len(ll.LoginLock.Recent) != 1 || ll.LoginLock.Recent[0].Device != "平板" {
		t.Fatalf("锁定状态: %v %s", err, b)
	}
	// 锁定期过后恢复:正确密码可以登录
	time.Sleep(800 * time.Millisecond)
	if st, body := login("me", "secret1", "新设备"); st != 200 {
		t.Fatalf("锁定期过后应能登录: %d %s", st, body)
	}
	// 手动解除
	login("me", "wrong", "平板")
	if resp, _ := e.do("POST", "/api/v1/admin/login-lock/clear", nil, adminHdr(e)); resp.StatusCode != 204 {
		t.Fatalf("解除锁定 = %d", resp.StatusCode)
	}
	if st, _ := login("me", "secret1", "新设备"); st != 200 {
		t.Fatalf("手动解除后应能登录: %d", st)
	}
	// LoginLock = 0:不锁定
	e.srv.LoginLock = 0
	login("me", "wrong", "平板")
	if st, _ := login("me", "secret1", "平板"); st != 200 {
		t.Fatalf("关闭锁定后应能立即登录: %d", st)
	}
}
