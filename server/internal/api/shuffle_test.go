package api

import (
	"context"
	"fmt"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestShufflePagesStayWithinSelectionAndVisitEveryItemOnce(t *testing.T) {
	e := newEnv(t)
	e.login()
	if _, err := e.srv.DB.Exec(`UPDATE media SET state=0`); err != nil {
		t.Fatal(err)
	}
	var dialog, root string
	if err := e.srv.DB.QueryRow(`SELECT dialog_id,root_id FROM media WHERE type=0 LIMIT 1`).Scan(&dialog, &root); err != nil {
		t.Fatal(err)
	}
	scan := func(seed int) []string {
		t.Helper()
		cursor := ""
		seen := map[string]bool{}
		ids := []string{}
		for pages := 0; pages < 30; pages++ {
			var page struct {
				Items      []Item `json:"items"`
				NextCursor string `json:"nextCursor"`
			}
			path := fmt.Sprintf("/api/v1/shuffle?types=photo&dialog=%s&roots=%s&limit=11&seed=%d", dialog, root, seed)
			if cursor != "" {
				path += "&cursor=" + url.QueryEscape(cursor)
			}
			e.getJSON(path, &page)
			for _, item := range page.Items {
				if item.Type != "photo" || item.DialogID != dialog || seen[item.ID] {
					t.Fatalf("selection/repeat: %+v", item)
				}
				seen[item.ID] = true
				ids = append(ids, item.ID)
			}
			cursor = page.NextCursor
			if cursor == "" {
				return ids
			}
		}
		t.Fatal("queue never terminated")
		return nil
	}
	a, b, c := scan(500), scan(500), scan(700)
	if len(a) != 130 || strings.Join(a, ",") != strings.Join(b, ",") || strings.Join(a, ",") == strings.Join(c, ",") {
		t.Fatalf("shuffle invalid: sizes %d/%d/%d", len(a), len(b), len(c))
	}
	var page struct {
		NextCursor string `json:"nextCursor"`
	}
	e.getJSON("/api/v1/shuffle?types=photo&seed=500&limit=3", &page)
	resp, _ := e.do("GET", "/api/v1/shuffle?types=video&seed=500&cursor="+url.QueryEscape(page.NextCursor), nil, nil)
	if resp.StatusCode != 400 {
		t.Fatal("cursor accepted changed filter")
	}
}

func TestBusyAuthenticationDoesNotInvalidatePhoneSession(t *testing.T) {
	e := newEnv(t)
	e.login()
	e.srv.DB.SetMaxOpenConns(1)
	conn, err := e.srv.DB.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	req := httptest.NewRequest("GET", "/api/v1/dialogs", nil).WithContext(ctx)
	req.Header.Set("Authorization", "Bearer "+e.token)
	w := httptest.NewRecorder()
	e.srv.Handler().ServeHTTP(w, req)
	if w.Code != 503 {
		t.Fatalf("database busy caused %d, wanted retryable 503", w.Code)
	}
	conn.Close()
	if _, _, _, ok := e.srv.Auth.VerifyInfo(e.token); !ok {
		t.Fatal("session invalidated after database pressure")
	}
}
