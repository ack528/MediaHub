package api

import (
	"io"
	"math/rand"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"
)

// 网络测速(手机端「设置 → 网络与测速」):
//
//	GET  /api/v1/speedtest/ping                 → 204,用来量往返延迟(手机端连续发多次取最小 / 平均 / 抖动)
//	GET  /api/v1/speedtest/down?seconds=15      → 持续发送不可压缩的随机数据 seconds 秒(默认 15,最短 1,最长 60),手机端边收边算速度
//	POST /api/v1/speedtest/up?seconds=15        → 持续接收手机端上传的数据最多 seconds 秒,返回服务端实际收到的字节数和耗时(以服务端量到的为准)
//
// 数据是随机生成的(gzip 压缩不了),响应头 Content-Encoding: identity,避免被中间层压缩后测出虚高的速度。
// 同时最多 3 个测速在跑,避免被滥用把带宽占满。
const speedMaxSeconds = 60

var (
	speedBuf     []byte
	speedRunning atomic.Int32
)

func init() {
	speedBuf = make([]byte, 1<<20)
	rand.New(rand.NewSource(20261008)).Read(speedBuf)
}

func speedSeconds(r *http.Request) int {
	n, _ := strconv.Atoi(r.URL.Query().Get("seconds"))
	if n <= 0 {
		n = 15
	}
	if n > speedMaxSeconds {
		n = speedMaxSeconds
	}
	return n
}

func (s *Server) speedPing(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(204)
}

func (s *Server) speedDown(w http.ResponseWriter, r *http.Request) {
	if speedRunning.Add(1) > 3 {
		speedRunning.Add(-1)
		writeErr(w, 429, "speedtest.busy", "已有测速在进行,稍后再试")
		return
	}
	defer speedRunning.Add(-1)
	secs := speedSeconds(r)
	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Cache-Control", "no-store")
	h.Set("Content-Encoding", "identity")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(200)
	fl, _ := w.(http.Flusher)
	deadline := time.Now().Add(time.Duration(secs) * time.Second)
	var total int64
	t0 := time.Now()
	off := 0
	for time.Now().Before(deadline) {
		if r.Context().Err() != nil {
			break
		}
		// 每次发 256KB,起点循环错开,避免连续两块完全一样
		end := off + 256<<10
		if end > len(speedBuf) {
			end = len(speedBuf)
		}
		n, err := w.Write(speedBuf[off:end])
		total += int64(n)
		if err != nil {
			break
		}
		off = end % len(speedBuf)
		if fl != nil {
			fl.Flush()
		}
	}
	s.Log.Info("测速(下行)", "ip", clientIP(r), "seconds", time.Since(t0).Seconds(), "bytes", total, "mbps", float64(total)*8/1e6/time.Since(t0).Seconds())
}

func (s *Server) speedUp(w http.ResponseWriter, r *http.Request) {
	if speedRunning.Add(1) > 3 {
		speedRunning.Add(-1)
		writeErr(w, 429, "speedtest.busy", "已有测速在进行,稍后再试")
		return
	}
	defer speedRunning.Add(-1)
	secs := speedSeconds(r)
	deadline := time.Now().Add(time.Duration(secs+5) * time.Second) // 多给 5 秒余量:手机端到点自己停
	buf := make([]byte, 256<<10)
	var total int64
	t0 := time.Now()
	body := http.MaxBytesReader(w, r.Body, 8<<30)
	for time.Now().Before(deadline) {
		n, err := body.Read(buf)
		total += int64(n)
		if err != nil {
			if err != io.EOF {
				s.Log.Debug("测速(上行)读取中断", "err", err)
			}
			break
		}
	}
	d := time.Since(t0)
	s.Log.Info("测速(上行)", "ip", clientIP(r), "seconds", d.Seconds(), "bytes", total, "mbps", float64(total)*8/1e6/d.Seconds())
	writeJSON(w, 200, map[string]any{"bytes": total, "ms": d.Milliseconds()})
}
