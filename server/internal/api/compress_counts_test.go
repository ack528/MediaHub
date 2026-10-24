package api

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"mediahub/internal/store"
)

func TestCompressCountsPaginationAndHistory(t *testing.T) {
	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "hub.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{DB: db}
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	for i := 0; i < 205; i++ {
		state := "queued"
		if i == 0 {
			state = "applied"
		} else if i == 1 {
			state = "done"
		}
		if _, err := tx.Exec(`INSERT INTO opt_job(media_id,state,codec,bitrate_kbps,orig_size,new_size,created,updated,display_name,display_root_path) VALUES(?,?,'h265',2000,100,40,1,1,?,'K:\')`, i+1, state, fmt.Sprintf("video-%d.mp4", i)); err != nil {
			t.Fatal(err)
		}
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		query       string
		total, jobs int
	}{
		{"?limit=100&offset=200", 205, 5},
		{"?state=applied", 1, 1},
		{"?state=done", 1, 1},
	} {
		r := httptest.NewRequest("GET", "/api/v1/admin/compress/jobs"+tc.query, nil)
		w := httptest.NewRecorder()
		s.adminCompressJobs(w, r)
		var out struct {
			Jobs                          []optJob
			Total                         int
			SavedBytes, PendingSavedBytes int64
			Counts                        map[string]int
		}
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		if w.Code != 200 || out.Total != tc.total || len(out.Jobs) != tc.jobs || out.SavedBytes != 60 || out.PendingSavedBytes != 60 || out.Counts["queued"] != 203 {
			t.Fatalf("%s: %s", tc.query, w.Body.String())
		}
		if tc.total == 1 && (out.Jobs[0].Name == "" || out.Jobs[0].Path == "") {
			t.Fatalf("lost history: %+v", out.Jobs[0])
		}
	}
}

func TestQSVPixelCompatibility(t *testing.T) {
	for _, tc := range []struct {
		codec, pix string
		want       bool
	}{
		{"h264", "yuv420p", true}, {"h264", "yuv420p10le", false}, {"hevc", "yuv420p10le", true}, {"hevc", "yuv444p", false}, {"hevc", "yuv422p10le", false},
	} {
		if got := qsvPixelFormat(tc.codec, tc.pix); got != tc.want {
			t.Errorf("%s/%s: %v", tc.codec, tc.pix, got)
		}
	}
}
