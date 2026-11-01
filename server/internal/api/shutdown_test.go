package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLocalShutdownRequiresLocalKey(t *testing.T) {
	stopped := make(chan struct{}, 1)
	s := &Server{AdminKey: "local-secret", Shutdown: func() { stopped <- struct{}{} }}
	for _, tc := range []struct {
		addr, key string
		status    int
	}{
		{"127.0.0.1:1234", "", 403},
		{"127.0.0.1:1234", "wrong", 403},
		{"192.0.2.1:1234", "local-secret", 403},
		{"127.0.0.1:1234", "local-secret", 202},
	} {
		r := httptest.NewRequest(http.MethodPost, "/api/v1/admin/local-shutdown", nil)
		r.RemoteAddr = tc.addr
		r.Header.Set("X-Admin-Key", tc.key)
		w := httptest.NewRecorder()
		s.localShutdown(w, r)
		if w.Code != tc.status {
			t.Fatalf("%s: got %d, want %d", tc.addr, w.Code, tc.status)
		}
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("shutdown not called")
	}
	select {
	case <-stopped:
		t.Fatal("unauthorized shutdown")
	default:
	}
}
