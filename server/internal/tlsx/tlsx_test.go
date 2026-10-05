package tlsx

import (
	"crypto/tls"
	"crypto/x509"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEnsureReusesAndServes(t *testing.T) {
	dir := t.TempDir()
	c1, k1, fp1, err := Ensure(dir)
	if err != nil {
		t.Fatal(err)
	}
	_, _, fp2, err := Ensure(dir)
	if err != nil || fp1 != fp2 {
		t.Fatalf("证书应复用,指纹应不变: %v %s %s", err, fp1, fp2)
	}
	if len(strings.Split(fp1, ":")) != 32 {
		t.Fatalf("指纹格式 %q", fp1)
	}
	// 能真的起一个 TLS 1.3 服务并握手
	cert, err := tls.LoadX509KeyPair(c1, k1)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, r.Proto) }))
	ts.TLS = &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}
	ts.EnableHTTP2 = true
	ts.StartTLS()
	defer ts.Close()
	var seen string
	cl := &http.Client{Transport: &http.Transport{ForceAttemptHTTP2: true, TLSClientConfig: &tls.Config{
		InsecureSkipVerify: true, // 与手机端一致:不走 CA 校验,只核对指纹
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			seen = Fingerprint(raw[0])
			return nil
		},
	}}}
	resp, err := cl.Get(ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if seen != fp1 {
		t.Fatalf("握手看到的指纹 %s 与生成的 %s 不一致", seen, fp1)
	}
	if resp.TLS.Version != tls.VersionTLS13 || string(b) != "HTTP/2.0" {
		t.Fatalf("应为 TLS1.3 + HTTP/2,实际 %x %s", resp.TLS.Version, b)
	}
	// 证书损坏 → 重新生成
	os.WriteFile(filepath.Join(dir, certName), []byte("junk"), 0o644)
	_, _, fp3, err := Ensure(dir)
	if err != nil || fp3 == fp1 {
		t.Fatalf("损坏后应重新生成: %v", err)
	}
}
