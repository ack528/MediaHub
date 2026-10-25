package api

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRemoteConfigPermissionsRevisionAndPreservation(t *testing.T) {
	e := newEnv(t)
	e.srv.Cfg.Path = filepath.Join(t.TempDir(), "中文配置.json")
	original := []byte(`{"listen":":8480","auth":{"private":"keep-secret"},"tunnel":{"token":"tunnel-secret"},"video":{"maxTranscodes":1,"custom":"keep"}}`)
	if err := os.WriteFile(e.srv.Cfg.Path, original, 0600); err != nil {
		t.Fatal(err)
	}
	resp, _ := e.do("GET", "/api/v1/manage/config", nil, nil)
	if resp.StatusCode != 401 {
		t.Fatalf("anonymous: %d", resp.StatusCode)
	}
	e.login()
	runningTranscodes := e.srv.Cfg.Video.MaxTranscodes
	resp, b := e.do("GET", "/api/v1/manage/config", nil, nil)
	if resp.StatusCode != 200 || strings.Contains(string(b), "secret") || strings.Contains(string(b), "private") {
		t.Fatalf("config response: %d %s", resp.StatusCode, b)
	}
	var cfg struct {
		Revision string        `json:"revision"`
		Fields   []configField `json:"fields"`
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		t.Fatal(err)
	}
	if len(cfg.Fields) != len(remoteFields) {
		t.Fatal("missing fields")
	}
	save := func(rev string, changes map[string]any, intended int) {
		t.Helper()
		r, b := e.do("PUT", "/api/v1/manage/config", map[string]any{"revision": rev, "changes": changes}, nil)
		if r.StatusCode != intended {
			t.Fatalf("save: %d %s", r.StatusCode, b)
		}
	}
	for _, changes := range []map[string]any{{"tools.ffmpeg": "evil.exe"}, {"listen": ":123junk"}, {"video.maxTranscodes": 99}, {"roots": []any{map[string]any{"path": "relative"}}}, {"optimize.window": "25:00-07:00"}} {
		save(cfg.Revision, changes, 400)
	}
	unchanged, _ := os.ReadFile(e.srv.Cfg.Path)
	if string(unchanged) != string(original) {
		t.Fatal("invalid input changed file")
	}
	save(cfg.Revision, map[string]any{"video.maxTranscodes": 2, "priority.enabled": true}, 200)
	b, _ = os.ReadFile(e.srv.Cfg.Path)
	var doc map[string]any
	json.Unmarshal(b, &doc)
	if fieldValue(doc, "auth.private") != "keep-secret" || fieldValue(doc, "tunnel.token") != "tunnel-secret" || fieldValue(doc, "video.custom") != "keep" || fieldValue(doc, "video.maxTranscodes") != float64(2) {
		t.Fatalf("preservation failed: %s", b)
	}
	if e.srv.Cfg.Video.MaxTranscodes != runningTranscodes {
		t.Fatal("live config mutated without restart")
	}
	save(cfg.Revision, map[string]any{"video.maxTranscodes": 3}, 409)
	called := 0
	e.srv.Restart = func() error { called++; return nil }
	resp, _ = e.do("POST", "/api/v1/manage/restart", map[string]any{}, nil)
	if resp.StatusCode != 202 || called != 1 {
		t.Fatal("admin restart failed")
	}
	if _, err := e.srv.DB.Exec(`UPDATE users SET is_admin=0`); err != nil {
		t.Fatal(err)
	}
	for _, route := range []struct{ method, path string }{{"GET", "config"}, {"PUT", "config"}, {"POST", "restart"}, {"POST", "rescan"}} {
		r, b := e.do(route.method, "/api/v1/manage/"+route.path, map[string]any{}, nil)
		if r.StatusCode != 403 {
			t.Fatalf("ordinary access %s: %d %s", route.path, r.StatusCode, b)
		}
	}
	if called != 1 {
		t.Fatal("ordinary account restarted server")
	}
	resp, b = e.do("GET", "/api/v1/manage/status", nil, nil)
	if resp.StatusCode != 200 || !strings.Contains(string(b), `"canManage":false`) {
		t.Fatalf("status: %d %s", resp.StatusCode, b)
	}
}
