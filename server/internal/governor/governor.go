// Package governor 按"有没有客户端在用"调度后台任务,优先保证手机的传输和播放流畅:
//
//	Idle      没有客户端(最近 ActiveWindow 内没有任何登录客户端的请求):后台任务全速跑,压缩队列正常转码;
//	Active    有客户端连着(但没在传大文件):后台任务(扫描、补全元数据、重复检测、封面预生成……)每干完一小块就让出一半时间,压缩暂停;
//	Streaming 正在传输 / 播放(有视频流、HLS 转码段、测速在进行):后台任务只用很小一部分时间,压缩暂停。
//
// 后台循环在每处理完一个单元后调用 [Governor.Yield],由它决定睡多久;压缩这类整段运行的任务用 [Governor.Level] 决定暂停 / 继续。
package governor

import (
	"context"
	"sync/atomic"
	"time"
)

type Level int32

const (
	Idle Level = iota
	Active
	Streaming
)

func (l Level) String() string {
	switch l {
	case Active:
		return "active"
	case Streaming:
		return "streaming"
	}
	return "idle"
}

type Config struct {
	Enabled       bool          // 关闭 = 永远 Idle(不做任何让路)
	ActiveWindow  time.Duration // 最近这么久内有客户端请求就算"有客户端连着"
	StreamGrace   time.Duration // 传输结束后多久内仍算 Streaming(连续取多个视频段时不要抖动)
	PauseCompress bool          // 有客户端时暂停后台压缩
}

type Governor struct {
	cfg        Config
	lastClient atomic.Int64 // unix 纳秒:最近一次登录客户端的请求
	lastHeavy  atomic.Int64 // 最近一次"重"传输(开始 / 结束 / 有数据)
	streams    atomic.Int32 // 正在进行的重传输数
	throttleMs atomic.Int64 // 累计让出的毫秒数(显示用)
	yields     atomic.Int64
}

func New(cfg Config) *Governor {
	if cfg.ActiveWindow <= 0 {
		cfg.ActiveWindow = 2 * time.Minute
	}
	if cfg.StreamGrace <= 0 {
		cfg.StreamGrace = 6 * time.Second
	}
	return &Governor{cfg: cfg}
}

func (g *Governor) Config() Config { return g.cfg }

// ClientActivity 登录客户端发来了请求(管理程序的请求不算)。nil 安全。
func (g *Governor) ClientActivity() {
	if g != nil {
		g.lastClient.Store(time.Now().UnixNano())
	}
}

// StreamStart / StreamEnd 一次重传输(视频文件、HLS 段、测速)开始 / 结束。
func (g *Governor) StreamStart() {
	if g == nil {
		return
	}
	g.streams.Add(1)
	g.lastHeavy.Store(time.Now().UnixNano())
	g.ClientActivity()
}

func (g *Governor) StreamEnd() {
	if g == nil {
		return
	}
	g.streams.Add(-1)
	g.lastHeavy.Store(time.Now().UnixNano())
}

// Touch 重传输期间有数据在流动(刷新"最近重传输"时间)。
func (g *Governor) Touch() {
	if g != nil {
		g.lastHeavy.Store(time.Now().UnixNano())
	}
}

// Streams 正在进行的重传输数。
func (g *Governor) Streams() int {
	if g == nil {
		return 0
	}
	return int(g.streams.Load())
}

// Level 当前调度级别。
func (g *Governor) Level() Level {
	if g == nil || !g.cfg.Enabled {
		return Idle
	}
	now := time.Now().UnixNano()
	if g.streams.Load() > 0 || now-g.lastHeavy.Load() < int64(g.cfg.StreamGrace) {
		return Streaming
	}
	if now-g.lastClient.Load() < int64(g.cfg.ActiveWindow) {
		return Active
	}
	return Idle
}

// IdleFor 距离最近一次客户端请求过去了多久(从没有过返回 24 小时)。
func (g *Governor) IdleFor() time.Duration {
	if g == nil {
		return 24 * time.Hour
	}
	t := g.lastClient.Load()
	if t == 0 {
		return 24 * time.Hour
	}
	return time.Duration(time.Now().UnixNano() - t)
}

// PauseCompress 当前这个级别下压缩该不该暂停。
func (g *Governor) PauseCompress() bool {
	return g != nil && g.cfg.Enabled && g.cfg.PauseCompress && g.Level() != Idle
}

func clamp(d, lo, hi time.Duration) time.Duration {
	if d < lo {
		return lo
	}
	if d > hi {
		return hi
	}
	return d
}

// Yield 后台循环每处理完一个单元(worked = 这个单元花的时间)后调用:有客户端时睡一会儿,把磁盘和 CPU 让给传输和播放。
// Active:睡 worked(约 50% 占空比,15 ~ 400 毫秒);Streaming:睡 worked 的 4 倍(120 毫秒 ~ 2.5 秒)。ctx 取消立即返回。nil 安全。
func (g *Governor) Yield(ctx context.Context, worked time.Duration) {
	if g == nil {
		return
	}
	var d time.Duration
	switch g.Level() {
	case Active:
		d = clamp(worked, 15*time.Millisecond, 400*time.Millisecond)
	case Streaming:
		d = clamp(worked*4, 120*time.Millisecond, 2500*time.Millisecond)
	default:
		return
	}
	g.yields.Add(1)
	g.throttleMs.Add(d.Milliseconds())
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

// YieldChunk 长时间读磁盘的循环(算大文件哈希)里每读一块调用:只在 Streaming 时睡很短的一下,避免和视频流抢磁盘。
func (g *Governor) YieldChunk(ctx context.Context) {
	if g != nil && g.Level() == Streaming {
		g.throttleMs.Add(30)
		t := time.NewTimer(30 * time.Millisecond)
		defer t.Stop()
		select {
		case <-ctx.Done():
		case <-t.C:
		}
	}
}

// WaitIdle 阻塞到 Idle(或 ctx 取消);返回 false 表示 ctx 取消了。
func (g *Governor) WaitIdle(ctx context.Context) bool {
	for g.Level() != Idle {
		select {
		case <-ctx.Done():
			return false
		case <-time.After(2 * time.Second):
		}
	}
	return ctx.Err() == nil
}

// Snapshot 状态快照(管理程序显示)。
type Snapshot struct {
	Enabled       bool   `json:"enabled"`
	Level         string `json:"level"`
	ActiveWindowS int    `json:"activeWindowSec"`
	IdleForSec    int64  `json:"idleForSec"`
	Streams       int    `json:"streams"`
	ThrottledMs   int64  `json:"throttledMs"`
	Yields        int64  `json:"yields"`
	PauseCompress bool   `json:"pauseCompress"`
}

func (g *Governor) Snapshot() Snapshot {
	if g == nil {
		return Snapshot{Level: "idle"}
	}
	return Snapshot{
		Enabled: g.cfg.Enabled, Level: g.Level().String(), ActiveWindowS: int(g.cfg.ActiveWindow / time.Second),
		IdleForSec: int64(g.IdleFor() / time.Second), Streams: g.Streams(), ThrottledMs: g.throttleMs.Load(), Yields: g.yields.Load(),
		PauseCompress: g.cfg.PauseCompress,
	}
}
