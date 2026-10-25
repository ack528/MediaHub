package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNewAppVersionDiscardsPreviousFileMetadata(t *testing.T) {
	u := New(Options{Version: "1.0.0", AppDir: t.TempDir(), Log: slog.New(slog.NewTextHandler(io.Discard, nil))}, false)
	u.app = AppStatus{Latest: "1.0.0", Sha256: "previous-sha", Size: 999, Ready: true, State: "ready"}
	u.appPath = "previous.apk"
	var rel release
	if err := json.Unmarshal([]byte(`{"tag_name":"v2","assets":[{"name":"LocalBrowse-2.0.0.apk","browser_download_url":"http://127.0.0.1/new.apk"}]}`), &rel); err != nil {
		t.Fatal(err)
	}
	u.noteApp(&rel) // Missing manifest deliberately stops before any network download.
	st := u.AppStatus()
	if st.Latest != "2.0.0" || st.Ready || st.Sha256 != "" || st.Size != 0 {
		t.Fatalf("new version inherited old file metadata: %+v", st)
	}
	if path, _ := u.AppFile(); path != "" {
		t.Fatal("previous file exposed as new version")
	}
}

// 服务端缓存手机端安装包:下载、校验、只留最新一个、重启服务后读回;缓存文件被改坏了就丢弃。
func TestAppCacheDownloadsVerifiesAndRestores(t *testing.T) {
	apk := []byte("PK\x03\x04 这是一个假的安装包")
	h := sha256.Sum256(apk)
	sum := hex.EncodeToString(h[:])
	mux := http.NewServeMux()
	mux.HandleFunc("/LocalBrowse-9.9.9.apk", func(w http.ResponseWriter, r *http.Request) { w.Write(apk) })
	mux.HandleFunc("/SHA256SUMS.txt", func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, sum+"  LocalBrowse-9.9.9.apk\nabcdef  MediaHub-9.9.9-update.zip\n")
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "LocalBrowse-1.0.0.apk"), []byte("old"), 0o644) // 旧版本(没有 .sha256,不会被当成缓存)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	opts := Options{Version: "1.0.0", AppDir: dir, Log: log}
	u := New(opts, false)
	if u.AppStatus().Ready {
		t.Fatal("没有校验文件的旧安装包不应该算缓存好")
	}

	var rel release
	if err := json.Unmarshal([]byte(`{"tag_name":"v9","body":"说明","assets":[
		{"name":"LocalBrowse-9.9.9.apk","browser_download_url":"`+srv.URL+`/LocalBrowse-9.9.9.apk"},
		{"name":"SHA256SUMS.txt","browser_download_url":"`+srv.URL+`/SHA256SUMS.txt"}]}`), &rel); err != nil {
		t.Fatal(err)
	}
	u.noteApp(&rel)
	deadline := time.Now().Add(10 * time.Second)
	for !u.AppStatus().Ready && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	st := u.AppStatus()
	if !st.Ready || st.Latest != "9.9.9" || st.Sha256 != sum || st.Size != int64(len(apk)) || st.Notes != "说明" {
		t.Fatalf("缓存状态不对: %+v", st)
	}
	p, ver := u.AppFile()
	if b, _ := os.ReadFile(p); string(b) != string(apk) || ver != "9.9.9" {
		t.Fatalf("缓存的文件不对: %s %s", p, ver)
	}
	if _, err := os.Stat(filepath.Join(dir, "LocalBrowse-1.0.0.apk")); !os.IsNotExist(err) {
		t.Fatal("旧版本的安装包应该被删掉")
	}
	if u.Status().App == nil || !u.Status().App.Ready {
		t.Fatal("管理程序看到的状态里应该带上手机端安装包")
	}

	// 同一个版本再检查一次:不重新下载
	u.noteApp(&rel)
	if s := u.AppStatus(); s.State != "ready" {
		t.Fatalf("已缓存的版本不应该重新下载: %+v", s)
	}

	// 重启服务:读回缓存
	u2 := New(opts, false)
	if s := u2.AppStatus(); !s.Ready || s.Latest != "9.9.9" || s.Sha256 != sum {
		t.Fatalf("重启后没有读回缓存: %+v", s)
	}
	// 文件被改坏:丢弃
	_ = os.WriteFile(p, []byte("坏了"), 0o644)
	if s := New(opts, false).AppStatus(); s.Ready {
		t.Fatalf("校验不对的缓存应该丢弃: %+v", s)
	}
}

// 校验和不对的下载不能当成缓存好的安装包。
func TestAppCacheRejectsWrongHash(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/LocalBrowse-9.9.9.apk", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "被篡改的内容") })
	mux.HandleFunc("/SHA256SUMS.txt", func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "0000000000000000000000000000000000000000000000000000000000000000  LocalBrowse-9.9.9.apk\n")
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	dir := t.TempDir()
	u := New(Options{Version: "1.0.0", AppDir: dir, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}, false)
	var rel release
	_ = json.Unmarshal([]byte(`{"tag_name":"v9","assets":[
		{"name":"LocalBrowse-9.9.9.apk","browser_download_url":"`+srv.URL+`/LocalBrowse-9.9.9.apk"},
		{"name":"SHA256SUMS.txt","browser_download_url":"`+srv.URL+`/SHA256SUMS.txt"}]}`), &rel)
	u.noteApp(&rel)
	deadline := time.Now().Add(10 * time.Second)
	for u.AppStatus().State == "downloading" && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if s := u.AppStatus(); s.Ready || s.State != "error" {
		t.Fatalf("校验和不对应该失败: %+v", s)
	}
	if p, _ := u.AppFile(); p != "" {
		t.Fatal("不应该有可下载的文件")
	}
	ents, _ := os.ReadDir(dir)
	if len(ents) != 0 {
		t.Fatalf("失败后不应该留下文件: %v", ents)
	}
}

// 下载中途卡住(连接还在、一直不给数据):到时间就放弃这个地址,不会一直等。
func TestFetchToGivesUpWhenStalled(t *testing.T) {
	old := stallTimeout
	stallTimeout = 300 * time.Millisecond
	defer func() { stallTimeout = old }()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "1000")
		w.Write([]byte("PK"))
		w.(http.Flusher).Flush()
		select {
		case <-r.Context().Done():
		case <-time.After(10 * time.Second):
		}
	}))
	defer srv.Close()
	u := New(Options{Version: "1.0.0", Log: slog.New(slog.NewTextHandler(io.Discard, nil))}, false)
	t0 := time.Now()
	err := u.fetchTo(context.Background(), srv.URL, filepath.Join(t.TempDir(), "x"), "00", func(int) {})
	if err == nil || time.Since(t0) > 5*time.Second {
		t.Fatalf("卡住时应该很快放弃: err=%v 用时 %v", err, time.Since(t0))
	}
}
