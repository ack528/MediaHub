package api

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"
)

func TestBrowseHistoryLimitsIsolationAndManagement(t *testing.T) {
	e := newEnv(t)
	e.login()
	var dialog, media int64
	if err := e.srv.DB.QueryRow(`SELECT dialog_id,id FROM media LIMIT 1`).Scan(&dialog, &media); err != nil {
		t.Fatal(err)
	}
	e.srv.Cfg.BrowseHistory.MaxEntries = 2
	path := fmt.Sprintf("/api/v1/dialogs/%d/browse-history", dialog)
	now := time.Now().UnixMilli()
	js := fmt.Sprintf(`{"itemId":"%d","sort":"name","grid":true}`, media)
	// Manual checkpoints bypass the automatic interval, never the per-group cap.
	for i := int64(3); i > 0; i-- {
		if err := e.srv.recordBrowseHistory(1, dialog, js, now-i*1000, true); err != nil {
			t.Fatal(err)
		}
	}
	var result struct {
		Entries []browseCheckpoint `json:"entries"`
	}
	e.getJSON(path, &result)
	if len(result.Entries) != 2 || result.Entries[0].SavedAt != now-1000 {
		t.Fatalf("order/cap: %+v", result)
	}
	if err := e.srv.recordBrowseHistory(1, dialog, js, now, false); err != nil {
		t.Fatal(err)
	}
	e.getJSON(path, &result)
	if len(result.Entries) != 2 || result.Entries[0].SavedAt != now-1000 {
		t.Fatal("same position generated automatic duplicate")
	}
	// A different user cannot see or delete this account's history.
	if err := e.srv.recordBrowseHistory(999, dialog, js, now, true); err != nil {
		t.Fatal(err)
	}
	resp, _ := e.do("DELETE", path+fmt.Sprintf("?at=%d", now-1000), nil, nil)
	if resp.StatusCode != 204 {
		t.Fatal(resp.Status)
	}
	e.getJSON(path, &result)
	if len(result.Entries) != 1 {
		t.Fatal("single delete")
	}
	resp, body := e.do("POST", path, map[string]any{"itemId": fmt.Sprint(media), "sort": "name", "grid": true}, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("save %s %s", resp.Status, body)
	}
	if json.Unmarshal(body, &result) != nil || len(result.Entries) != 2 {
		t.Fatal("manual save did not return updated history")
	}
	e.srv.Cfg.BrowseHistory.Enabled = false
	resp, _ = e.do("POST", path, map[string]any{"itemId": fmt.Sprint(media)}, nil)
	if resp.StatusCode != 403 {
		t.Fatal("disabled policy ignored")
	}
	resp, _ = e.do("DELETE", path, nil, nil)
	if resp.StatusCode != 204 {
		t.Fatal(resp.Status)
	}
	e.getJSON(path, &result)
	if len(result.Entries) != 0 {
		t.Fatal("clear")
	}
	var n int
	e.srv.DB.QueryRow(`SELECT count(*) FROM user_browse_history WHERE user_id=999`).Scan(&n)
	if n != 1 {
		t.Fatal("other user's history deleted")
	}
}

func TestBrowseHistoryRetentionAndStaleWrites(t *testing.T) {
	e := newEnv(t)
	e.login()
	now := time.Now().UnixMilli()
	js := `{"itemId":"1"}`
	if err := e.srv.recordBrowseHistory(1, 1, js, now, true); err != nil {
		t.Fatal(err)
	}
	if err := e.srv.recordBrowseHistory(1, 1, `{"itemId":"2"}`, now-600000, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.srv.DB.Exec(`INSERT INTO user_browse_history VALUES(1,1,?,?)`, now-100*86400000, js); err != nil {
		t.Fatal(err)
	}
	if err := e.srv.recordBrowseHistory(1, 1, `{"itemId":"3"}`, now+1, true); err != nil {
		t.Fatal(err)
	}
	var n int
	e.srv.DB.QueryRow(`SELECT count(*) FROM user_browse_history`).Scan(&n)
	if n != 2 {
		t.Fatalf("stale/expired checkpoints remained: %d", n)
	}
}
