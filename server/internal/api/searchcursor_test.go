package api

import (
	"net/url"
	"testing"
)

// 搜索用键集游标翻页:翻完所有页,不重复、不遗漏,和一次取完的结果一致;旧的数字游标(偏移量)继续可用。
func TestSearchKeysetCursor(t *testing.T) {
	e := newEnv(t)
	e.login()
	rescanAll(t, e)
	type resp struct {
		Items []struct{ ID string }
		NextCursor string
	}
	var all resp
	e.getJSON("/api/v1/search?q=f1&types=photo&limit=200", &all)
	if len(all.Items) < 40 {
		t.Fatalf("样本太少: %d", len(all.Items))
	}
	seen := map[string]bool{}
	var order []string
	cur := ""
	for page := 0; page < 100; page++ {
		var r resp
		u := "/api/v1/search?q=f1&types=photo&limit=7"
		if cur != "" {
			u += "&cursor=" + url.QueryEscape(cur)
		}
		e.getJSON(u, &r)
		for _, it := range r.Items {
			if seen[it.ID] {
				t.Fatalf("翻页结果重复: %s", it.ID)
			}
			seen[it.ID] = true
			order = append(order, it.ID)
		}
		if r.NextCursor == "" {
			break
		}
		if page == 0 && r.NextCursor[:2] != "k:" {
			t.Fatalf("应该返回键集游标: %q", r.NextCursor)
		}
		cur = r.NextCursor
	}
	if len(order) != len(all.Items) {
		t.Fatalf("翻页总数 %d != 一次取完 %d", len(order), len(all.Items))
	}
	for i := range order {
		if order[i] != all.Items[i].ID {
			t.Fatalf("第 %d 项顺序不一致", i)
		}
	}
	// 旧版本的偏移量游标
	var old resp
	e.getJSON("/api/v1/search?q=f1&types=photo&limit=7&cursor=7", &old)
	if len(old.Items) != 7 || old.Items[0].ID != all.Items[7].ID {
		t.Fatalf("旧的偏移量游标应该继续可用")
	}
	if resp, _ := e.do("GET", "/api/v1/search?q=f1&types=photo&cursor=k:abc", nil, nil); resp.StatusCode != 400 {
		t.Fatalf("坏游标应该 400,实际 %d", resp.StatusCode)
	}
}
