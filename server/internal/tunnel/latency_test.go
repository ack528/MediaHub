package tunnel

import "testing"

func TestStats(t *testing.T) {
	st := stats([]int64{20, 30, -1, 50, 40})
	if st.N != 5 || st.Last != 40 || st.Min != 20 || st.Max != 50 || st.Avg != 35 {
		t.Fatalf("stats = %+v", st)
	}
	if st.LossPc != 20 {
		t.Fatalf("loss = %v", st.LossPc)
	}
	// 相邻成功采样差值:10, 20, 10 → 平均 13.33
	if st.Jitter < 13.3 || st.Jitter > 13.4 {
		t.Fatalf("jitter = %v", st.Jitter)
	}
	if e := stats(nil); e.Last != -1 || e.N != 0 {
		t.Fatalf("empty = %+v", e)
	}
	if f := stats([]int64{-1, -1}); f.LossPc != 100 {
		t.Fatalf("all failed = %+v", f)
	}
}

func TestUDPProtocol(t *testing.T) {
	for p, want := range map[string]bool{"tcp": false, "quic": true, "KCP": true, " quic ": true, "websocket": false, "wss": false, "": false} {
		if UDPProtocol(p) != want {
			t.Errorf("UDPProtocol(%q) != %v", p, want)
		}
	}
}
