package api

import (
	"net/http"
	"strconv"
	"sync"
	"time"
)

// 登录失败全局锁定:**任何一台设备**(不管来自哪个 IP、哪个账号)登录失败一次,之后的 LoginLock(默认 20 秒)内,
// **所有设备**都不能再验证密码 —— 锁定期间的登录请求直接返回 429,不做密码计算(pbkdf2 本身就耗 CPU,反复算等于给爆破者送资源)。
// 已登录设备的令牌不受影响(只有"验证密码"被锁)。
//
// 代价:有人故意每 20 秒试一次密码,会一直把别的设备挡在登录页外 —— 但他一小时也只能试 180 次;
// 爆破一个像样的密码,这个速度等于不可能。需要更宽松时在 config.json 的 security.loginLockSec 里调(0 = 关闭)。
type loginGuard struct {
	mu    sync.Mutex
	until time.Time
	total int64
	recent []loginFail // 最近 30 次失败(管理程序里看)
}

type loginFail struct {
	At     int64  `json:"at"`
	IP     string `json:"ip"`
	User   string `json:"user"`
	Device string `json:"device"`
}

// wait 返回还要等多久才能验证密码(0 = 现在可以)。
func (g *loginGuard) wait() time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	if d := time.Until(g.until); d > 0 {
		return d
	}
	return 0
}

// fail 记一次失败并开始(重新开始)锁定。
func (g *loginGuard) fail(lock time.Duration, ip, user, device string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.total++
	if lock > 0 {
		g.until = time.Now().Add(lock)
	}
	g.recent = append(g.recent, loginFail{At: time.Now().Unix(), IP: ip, User: user, Device: device})
	if len(g.recent) > 30 {
		g.recent = g.recent[len(g.recent)-30:]
	}
}

// snapshot 给管理接口:剩余锁定秒数、失败总数、最近失败。
func (g *loginGuard) snapshot() map[string]any {
	g.mu.Lock()
	defer g.mu.Unlock()
	rem := time.Until(g.until).Seconds()
	if rem < 0 {
		rem = 0
	}
	return map[string]any{"remainingSec": int(rem + 0.999), "failures": g.total, "recent": append([]loginFail(nil), g.recent...)}
}

func (g *loginGuard) clear() {
	g.mu.Lock()
	g.until = time.Time{}
	g.mu.Unlock()
}

// lockedResponse 锁定期间的登录响应。
func lockedResponse(w http.ResponseWriter, wait time.Duration) {
	sec := int(wait.Seconds() + 0.999)
	w.Header().Set("Retry-After", strconv.Itoa(sec))
	writeErr(w, 429, "auth.locked", "刚刚有设备登录失败,为了安全,"+strconv.Itoa(sec)+" 秒后才能再次验证密码")
}

// loginLockInfo 给「客户端连接」页:锁定时长设置、剩余秒数、最近的失败。
func (s *Server) loginLockInfo() map[string]any {
	m := s.loginG.snapshot()
	m["lockSec"] = int(s.LoginLock.Seconds())
	return m
}
