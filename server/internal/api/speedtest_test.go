package api

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"net/http"
	"testing"
	"time"
)

func TestSpeedtestAndGzipAndCaps(t *testing.T) {
	e := newEnv(t)
	e.login()

	// ping
	if resp, _ := e.do("GET", "/api/v1/speedtest/ping", nil, nil); resp.StatusCode != 204 {
		t.Fatalf("ping = %d", resp.StatusCode)
	}
	// 下行:持续 2 秒,收到的字节数 > 0,没有 gzip 压缩
	req, _ := http.NewRequest("GET", e.ts.URL+"/api/v1/speedtest/down?seconds=2", nil)
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Accept-Encoding", "gzip")
	t0 := time.Now()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	n, _ := io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.Header.Get("Content-Encoding") == "gzip" {
		t.Fatal("测速数据不能被压缩")
	}
	if d := time.Since(t0); d < 1800*time.Millisecond || d > 5*time.Second || n < 1<<20 {
		t.Fatalf("下行 2 秒: 耗时 %v 字节 %d", d, n)
	}
	// 上行:发 3MB,服务端返回收到的字节数
	body := bytes.Repeat([]byte{7}, 3<<20)
	req, _ = http.NewRequest("POST", e.ts.URL+"/api/v1/speedtest/up?seconds=2", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+e.token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var up struct{ Bytes, Ms int64 }
	_ = json.NewDecoder(resp.Body).Decode(&up)
	resp.Body.Close()
	if up.Bytes != int64(len(body)) {
		t.Fatalf("上行字节数 = %d, want %d", up.Bytes, len(body))
	}

	// gzip:JSON 接口在客户端接受 gzip 时压缩,内容解压后一致
	req, _ = http.NewRequest("GET", e.ts.URL+"/api/v1/dialogs", nil)
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Accept-Encoding", "gzip") // 手动设置后 Go 不会自动解压
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.Header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("dialogs 应该 gzip,实际 %q", resp.Header.Get("Content-Encoding"))
	}
	zr, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	plain, _ := io.ReadAll(zr)
	if !json.Valid(plain) {
		t.Fatalf("解压后不是 JSON: %.80s", plain)
	}
	// 不带 Accept-Encoding 时不压缩
	req, _ = http.NewRequest("GET", e.ts.URL+"/api/v1/dialogs", nil)
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Accept-Encoding", "identity")
	resp, _ = http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.Header.Get("Content-Encoding") != "" {
		t.Fatal("没要 gzip 就不该压缩")
	}

	// caps
	var info struct {
		Caps struct {
			Codecs              []string `json:"codecs"`
			MaxTranscodeBitrate int      `json:"maxTranscodeBitrate"`
			Speedtest           bool     `json:"speedtest"`
		} `json:"caps"`
	}
	resp2, b := e.do("GET", "/api/v1/server/info", nil, nil)
	if resp2.StatusCode != 200 || json.Unmarshal(b, &info) != nil || info.Caps.MaxTranscodeBitrate != 6_000_000 || !info.Caps.Speedtest {
		t.Fatalf("caps: %d %s", resp2.StatusCode, b)
	}
}
