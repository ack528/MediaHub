package update

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestCandidates(t *testing.T) {
	got := candidates("https://github.com/a/b", []string{"https://m1.example/", "https://m2.example", "https://github.com/a/b"})
	want := []string{"https://github.com/a/b", "https://m1.example/https://github.com/a/b", "https://m2.example/https://github.com/a/b"}
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Fatalf("candidates = %v", got)
	}
	if len(candidates("https://github.com/a/b", nil)) != 1 {
		t.Fatal("没有镜像时只有直连")
	}
}

// race 应该选中先响应 200 的那个,慢的或者失败的不选。
func TestRacePicksFastestOK(t *testing.T) {
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(2 * time.Second)
		w.WriteHeader(206)
	}))
	defer slow.Close()
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(404) }))
	defer bad.Close()
	fast := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(206) }))
	defer fast.Close()
	got := race(context.Background(), newHTTPClient(5*time.Second), []string{bad.URL, slow.URL, fast.URL}, "t")
	if got != fast.URL {
		t.Fatalf("race = %s, want %s", got, fast.URL)
	}
	// 全部失败:返回第一个,由调用方回退
	if r := race(context.Background(), newHTTPClient(5*time.Second), []string{bad.URL, bad.URL + "/x"}, "t"); r != bad.URL {
		t.Fatalf("all-fail race = %s", r)
	}
}
