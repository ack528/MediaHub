package api

import (
	"net/http"
	"testing"
)

// 群组列表和历史:带上次的 If-None-Match 且内容没变 → 304 没有正文;内容变了(新文件)→ 200 新 ETag。
func TestListETag304(t *testing.T) {
	e := newEnv(t)
	e.login()
	rescanAll(t, e)
	did := e.bigDialog()
	for _, path := range []string{"/api/v1/dialogs", "/api/v1/dialogs/" + did + "/history?limit=20"} {
		resp, b := e.do("GET", path, nil, nil)
		tag := resp.Header.Get("ETag")
		if resp.StatusCode != 200 || tag == "" || len(b) == 0 {
			t.Fatalf("%s: 首次应 200 并带 ETag: %d %q", path, resp.StatusCode, tag)
		}
		resp2, b2 := e.do("GET", path, nil, map[string]string{"If-None-Match": tag})
		if resp2.StatusCode != http.StatusNotModified || len(b2) != 0 {
			t.Fatalf("%s: 内容没变应 304 且没有正文: %d %d 字节", path, resp2.StatusCode, len(b2))
		}
		resp3, _ := e.do("GET", path, nil, map[string]string{"If-None-Match": `W/"0000"`})
		if resp3.StatusCode != 200 {
			t.Fatalf("%s: ETag 不匹配应 200,实际 %d", path, resp3.StatusCode)
		}
	}
}
