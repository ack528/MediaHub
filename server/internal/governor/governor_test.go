package governor

import (
	"context"
	"testing"
	"time"
)

func TestLevels(t *testing.T) {
	g := New(Config{Enabled: true, ActiveWindow: 300 * time.Millisecond, StreamGrace: 100 * time.Millisecond, PauseCompress: true})
	if g.Level() != Idle || g.PauseCompress() {
		t.Fatal("没有客户端应该是 Idle,不暂停压缩")
	}
	g.ClientActivity()
	if g.Level() != Active || !g.PauseCompress() {
		t.Fatalf("有客户端请求 → Active: %v", g.Level())
	}
	g.StreamStart()
	if g.Level() != Streaming {
		t.Fatalf("有传输在进行 → Streaming: %v", g.Level())
	}
	g.StreamEnd()
	if g.Level() != Streaming { // 刚结束还在宽限期内,不抖动
		t.Fatalf("宽限期内仍是 Streaming: %v", g.Level())
	}
	time.Sleep(150 * time.Millisecond)
	if g.Level() != Active {
		t.Fatalf("宽限期过了回到 Active: %v", g.Level())
	}
	time.Sleep(350 * time.Millisecond)
	if g.Level() != Idle {
		t.Fatalf("窗口过了回到 Idle: %v", g.Level())
	}
	// 关闭时永远 Idle
	off := New(Config{Enabled: false})
	off.ClientActivity()
	off.StreamStart()
	if off.Level() != Idle || off.PauseCompress() {
		t.Fatal("关闭后不让路")
	}
}

func TestYield(t *testing.T) {
	g := New(Config{Enabled: true, ActiveWindow: time.Minute, StreamGrace: 50 * time.Millisecond})
	ctx := context.Background()
	t0 := time.Now()
	g.Yield(ctx, 10*time.Millisecond) // Idle:不睡
	if time.Since(t0) > 5*time.Millisecond {
		t.Fatal("Idle 不应该让出时间")
	}
	g.ClientActivity()
	t0 = time.Now()
	g.Yield(ctx, 100*time.Millisecond) // Active:睡约 worked
	if d := time.Since(t0); d < 90*time.Millisecond || d > 300*time.Millisecond {
		t.Fatalf("Active 应该睡约 100ms,实际 %v", d)
	}
	g.StreamStart()
	t0 = time.Now()
	g.Yield(ctx, 50*time.Millisecond) // Streaming:睡 4 倍(200ms)
	if d := time.Since(t0); d < 180*time.Millisecond || d > 500*time.Millisecond {
		t.Fatalf("Streaming 应该睡约 200ms,实际 %v", d)
	}
	// ctx 取消立即返回
	cctx, cancel := context.WithCancel(ctx)
	cancel()
	t0 = time.Now()
	g.Yield(cctx, time.Second)
	if time.Since(t0) > 50*time.Millisecond {
		t.Fatal("ctx 取消后应立即返回")
	}
	g.StreamEnd()
	// nil 安全
	var n *Governor
	n.Yield(ctx, time.Second)
	n.ClientActivity()
	if n.Level() != Idle || n.Streams() != 0 {
		t.Fatal("nil Governor 应该是 Idle")
	}
	if s := g.Snapshot(); s.Yields == 0 || s.ThrottledMs == 0 {
		t.Fatalf("snapshot 应有让出统计: %+v", s)
	}
}

func TestWaitIdle(t *testing.T) {
	g := New(Config{Enabled: true, ActiveWindow: 200 * time.Millisecond, StreamGrace: 10 * time.Millisecond})
	g.ClientActivity()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	t0 := time.Now()
	if !g.WaitIdle(ctx) {
		t.Fatal("应该等到 Idle")
	}
	if time.Since(t0) < 150*time.Millisecond {
		t.Fatalf("应该等到窗口过期: %v", time.Since(t0))
	}
	g.ClientActivity()
	c2, cancel2 := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel2()
	if g.WaitIdle(c2) {
		t.Fatal("ctx 超时应该返回 false")
	}
}
