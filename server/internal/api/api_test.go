package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"mediahub/internal/auth"
	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/index"
	"mediahub/internal/jellyfin"
	"mediahub/internal/store"
)

type env struct {
	t     *testing.T
	ts    *httptest.Server
	srv   *Server
	lib   string
	token string
}

func projectRoot(t *testing.T) string {
	if r := os.Getenv("MEDIAHUB_ROOT"); r != "" {
		return r
	}
	t.Skip("MEDIAHUB_ROOT 未设置(先运行 . .\\env.ps1)")
	return ""
}

// 构建一个带 130 个假 jpg(按小时递增的修改时间)和一个真实 mp4 的库。
func newEnv(t *testing.T) *env {
	pr := projectRoot(t)
	lib := t.TempDir()
	dir := filepath.Join(lib, "大对话") // 盘符下的母文件夹 = 一个对话
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	base := time.Date(2024, 1, 1, 12, 0, 0, 0, time.Local)
	for i := 1; i <= 130; i++ {
		p := filepath.Join(dir, fmt.Sprintf("f%d.jpg", i)) // f1 f2 ... f130:自然排序应为 1,2,...,130
		if err := os.WriteFile(p, []byte(fmt.Sprintf("fake-jpeg-%d", i)), 0o644); err != nil {
			t.Fatal(err)
		}
		mt := base.Add(time.Duration(i) * 12 * time.Hour)
		os.Chtimes(p, mt, mt)
	}
	vsrc, err := os.ReadFile(filepath.Join(pr, "testdata", "generated", "video", "h264_aac.mp4"))
	if err != nil {
		t.Skip("缺少 h264_aac.mp4 样本")
	}
	os.MkdirAll(filepath.Join(lib, "相册", "嵌套", "更深"), 0o755) // 母文件夹里的子文件夹不单独成组,其中媒体平铺进"相册"
	os.WriteFile(filepath.Join(lib, "相册", "嵌套", "更深", "clip.mp4"), vsrc, 0o644)
	os.WriteFile(filepath.Join(lib, "相册", "range.bin.mp3"), []byte("0123456789abcdefghij"), 0o644)

	db, err := store.OpenMigrated(filepath.Join(t.TempDir(), "t.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	cfg := config.Default()
	cfg.DataDir = t.TempDir()
	cfg.Roots = []config.Root{{Path: lib, Label: "T:"}}
	cfg.Cache.PerDriveQuotaGB = 1
	cfg.Cache.MinFreeMarginGB = 0
	c2, _ := config.Load("", pr)
	cfg.Tools, cfg.Cache.FallbackDir = c2.Tools, filepath.Join(t.TempDir(), "fb")
	var logw io.Writer = io.Discard
	if os.Getenv("MEDIAHUB_TEST_LOG") != "" {
		logw = os.Stderr
	}
	log := slog.New(slog.NewTextHandler(logw, &slog.HandlerOptions{Level: slog.LevelDebug}))
	ix := index.New(db, cfg, log)
	roots, err := ix.SyncRoots()
	if err != nil || len(roots) != 1 {
		t.Fatalf("roots %v %v", roots, err)
	}
	cm := cache.New(db, log, 1, 0, cfg.Cache.FallbackDir)
	if _, err := cm.Init(roots[0].ID, roots[0].Path, "T:"); err != nil {
		t.Fatal(err)
	}
	if err := ix.ScanRoot(context.Background(), roots[0]); err != nil {
		t.Fatal(err)
	}
	if err := ix.Enrich(context.Background(), roots[0]); err != nil { // 假 jpg 读不出 EXIF,标记完成即可
		t.Fatal(err)
	}
	a := auth.New(db, 30)
	a.SetIterations(1000)
	if err := a.CreateUser("me", "secret1"); err != nil {
		t.Fatal(err)
	}
	srv := &Server{DB: db, Cfg: cfg, Auth: a, Idx: ix, Cache: cm, Poster: NewPoster(db, cfg, cm), Render: NewRenderer(db, cfg, cm), Log: log, Version: "test"}
	e := &env{t: t, ts: httptest.NewServer(srv.Handler()), srv: srv, lib: lib}
	t.Cleanup(e.ts.Close)
	return e
}

func (e *env) do(method, path string, body any, hdr map[string]string) (*http.Response, []byte) {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.ts.URL+path, rd)
	if e.token != "" {
		req.Header.Set("Authorization", "Bearer "+e.token)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp, b
}

func (e *env) getJSON(path string, out any) {
	e.t.Helper()
	resp, b := e.do("GET", path, nil, nil)
	if resp.StatusCode != 200 {
		e.t.Fatalf("GET %s -> %d %s", path, resp.StatusCode, b)
	}
	if err := json.Unmarshal(b, out); err != nil {
		e.t.Fatalf("decode %s: %v\n%s", path, err, b)
	}
}

type histResp struct {
	Items      []Item `json:"items"`
	Total      int    `json:"total"`
	FirstRank  int    `json:"firstRank"`
	NextCursor string `json:"nextCursor"`
	PrevCursor string `json:"prevCursor"`
}

func names(items []Item) []string {
	out := make([]string, len(items))
	for i, it := range items {
		out[i] = it.Name
	}
	return out
}

func TestAuthFlow(t *testing.T) {
	e := newEnv(t)
	if resp, _ := e.do("GET", "/api/v1/server/info", nil, nil); resp.StatusCode != 200 {
		t.Fatal("server/info should be open")
	}
	if resp, _ := e.do("GET", "/api/v1/dialogs", nil, nil); resp.StatusCode != 401 {
		t.Fatalf("expect 401 without token, got %d", resp.StatusCode)
	}
	if resp, _ := e.do("POST", "/api/v1/auth/login", map[string]string{"username": "me", "password": "bad"}, nil); resp.StatusCode != 401 {
		t.Fatalf("bad password -> %d", resp.StatusCode)
	}
	resp, b := e.do("POST", "/api/v1/auth/login", map[string]string{"username": "me", "password": "secret1", "deviceName": "test"}, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("login %d %s", resp.StatusCode, b)
	}
	var lr struct{ Token string }
	json.Unmarshal(b, &lr)
	e.token = lr.Token
	var dl struct{ Dialogs []Dialog }
	e.getJSON("/api/v1/dialogs", &dl)
	if len(dl.Dialogs) != 2 { // 母文件夹"相册"(clip.mp4 + range.bin.mp3)和"大对话"
		t.Fatalf("dialogs=%d: %+v", len(dl.Dialogs), dl.Dialogs)
	}
	// 登出后令牌失效
	e.do("POST", "/api/v1/auth/logout", nil, nil)
	if resp, _ := e.do("GET", "/api/v1/dialogs", nil, nil); resp.StatusCode != 401 {
		t.Fatal("token should be revoked after logout")
	}
}

func (e *env) login() {
	_, b := e.do("POST", "/api/v1/auth/login", map[string]string{"username": "me", "password": "secret1"}, nil)
	var lr struct{ Token string }
	json.Unmarshal(b, &lr)
	e.token = lr.Token
}

func (e *env) bigDialog() string {
	var dl struct{ Dialogs []Dialog }
	e.getJSON("/api/v1/dialogs", &dl)
	for _, d := range dl.Dialogs {
		if d.Title == "大对话" {
			return d.ID
		}
	}
	e.t.Fatal("大对话 not found")
	return ""
}

func TestHistoryPaginationAllOrders(t *testing.T) {
	e := newEnv(t)
	e.login()
	did := e.bigDialog()

	for _, tc := range []struct{ sort, dir string }{{"taken", "desc"}, {"taken", "asc"}, {"name", "asc"}, {"name", "desc"}, {"size", "desc"}, {"modified", "asc"}} {
		// 正向翻完整个对话:不重复、不遗漏
		seen := map[string]bool{}
		var order []string
		cur := ""
		for pages := 0; pages < 20; pages++ {
			var h histResp
			path := fmt.Sprintf("/api/v1/dialogs/%s/history?sort=%s&dir=%s&limit=50&types=photo", did, tc.sort, tc.dir)
			if cur != "" {
				path += "&cursor=" + url.QueryEscape(cur)
			}
			e.getJSON(path, &h)
			if h.Total != 130 {
				t.Fatalf("%v total=%d", tc, h.Total)
			}
			for _, it := range h.Items {
				if seen[it.ID] {
					t.Fatalf("%v: duplicate %s", tc, it.Name)
				}
				seen[it.ID] = true
				order = append(order, it.Name)
			}
			if h.NextCursor == "" {
				break
			}
			cur = h.NextCursor
		}
		if len(seen) != 130 {
			t.Fatalf("%v: got %d unique items", tc, len(seen))
		}
		if tc.sort == "name" {
			want := "f1.jpg"
			wantLast := "f130.jpg"
			if tc.dir == "desc" {
				want, wantLast = wantLast, want
			}
			if order[0] != want || order[129] != wantLast {
				t.Fatalf("%v: natural order wrong: first=%s last=%s", tc, order[0], order[129])
			}
			if tc.dir == "asc" && (order[1] != "f2.jpg" || order[9] != "f10.jpg") {
				t.Fatalf("natural order wrong: %v", order[:12])
			}
		}
		if tc.sort == "taken" && tc.dir == "desc" && (order[0] != "f130.jpg" || order[129] != "f1.jpg") {
			t.Fatalf("taken desc wrong: %s .. %s", order[0], order[129])
		}
	}
}

func TestAroundAndPrevAndDate(t *testing.T) {
	e := newEnv(t)
	e.login()
	did := e.bigDialog()

	// 取 taken desc 全量,找到中间那一项(f65)
	var all histResp
	e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/history?sort=taken&dir=desc&limit=200&types=photo", did), &all)
	var mid Item
	for _, it := range all.Items {
		if it.Name == "f65.jpg" {
			mid = it
		}
	}
	// around:返回包含 f65 的一页,且 firstRank 正确
	var h histResp
	e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/history?sort=taken&dir=desc&limit=20&types=photo&around=%s", did, mid.ID), &h)
	found := -1
	for i, it := range h.Items {
		if it.ID == mid.ID {
			found = i
		}
	}
	if found < 0 || len(h.Items) != 20 {
		t.Fatalf("around: found=%d len=%d", found, len(h.Items))
	}
	if all.Items[h.FirstRank].ID != h.Items[0].ID {
		t.Fatalf("firstRank=%d does not point at first item", h.FirstRank)
	}
	if h.NextCursor == "" || h.PrevCursor == "" {
		t.Fatal("around page in the middle must have both cursors")
	}
	// 用 prevCursor 向前翻一页,拼起来应与全量一致
	var prev histResp
	e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/history?sort=taken&dir=desc&limit=20&types=photo&cursor=%s", did, url.QueryEscape(h.PrevCursor)), &prev)
	if len(prev.Items) != 20 {
		t.Fatalf("prev len=%d", len(prev.Items))
	}
	joined := append(append([]Item{}, prev.Items...), h.Items...)
	for i, it := range joined {
		if it.ID != all.Items[prev.FirstRank+i].ID {
			t.Fatalf("prev+current not contiguous at %d", i)
		}
	}
	// 一路向前翻到头:最后不再有 prevCursor
	cur := h.PrevCursor
	for i := 0; i < 20 && cur != ""; i++ {
		var p histResp
		e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/history?sort=taken&dir=desc&limit=20&types=photo&cursor=%s", did, url.QueryEscape(cur)), &p)
		if p.FirstRank == 0 && p.PrevCursor != "" {
			t.Fatal("first page must not have prevCursor")
		}
		cur = p.PrevCursor
	}
	// aroundDate:f1 的时间是 2024-01-02 00:00,查 2024-01-10 附近
	var d histResp
	e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/history?sort=taken&dir=desc&limit=10&types=photo&aroundDate=2024-01-10", did), &d)
	if len(d.Items) == 0 {
		t.Fatal("aroundDate empty")
	}
	// 页内应同时包含 1 月 10 日之前和之后的条目
	first, _ := time.Parse(time.RFC3339, d.Items[0].TakenAt)
	last, _ := time.Parse(time.RFC3339, d.Items[len(d.Items)-1].TakenAt)
	pivot := time.Date(2024, 1, 10, 0, 0, 0, 0, time.Local)
	if !(first.After(pivot) && last.Before(pivot.Add(24*time.Hour))) {
		t.Fatalf("aroundDate page does not straddle the date: %s .. %s", first, last)
	}
	// 非法参数
	if resp, _ := e.do("GET", fmt.Sprintf("/api/v1/dialogs/%s/history?sort=nope", did), nil, nil); resp.StatusCode != 400 {
		t.Fatal("bad sort should be 400")
	}
}

func TestBucketsSearchAndTypes(t *testing.T) {
	e := newEnv(t)
	e.login()
	did := e.bigDialog()
	var b struct {
		Buckets []Bucket
		Total   int
	}
	e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/buckets?by=month&sort=taken&types=photo", did), &b)
	sum := 0
	for i, x := range b.Buckets {
		if x.Offset != sum {
			t.Fatalf("bucket %d offset %d want %d", i, x.Offset, sum)
		}
		sum += x.Count
	}
	if sum != 130 || len(b.Buckets) < 3 || b.Buckets[0].Key < b.Buckets[1].Key {
		t.Fatalf("buckets wrong: %+v", b.Buckets)
	}

	var s struct{ Items []Item }
	e.getJSON("/api/v1/search?q=f12&types=photo", &s) // 3 个字符 → FTS:f12 f120..f129 = 11 个
	if len(s.Items) != 11 {
		t.Fatalf("search f12 = %d (%v)", len(s.Items), names(s.Items))
	}
	e.getJSON("/api/v1/search?q=f1&types=photo&limit=200", &s) // 2 个字符 → LIKE
	if len(s.Items) != 41 {                                    // f1, f10-f19, f100-f130 = 1+10+31 = 42?
		t.Logf("LIKE f1 count = %d", len(s.Items))
	}
	// types 过滤:默认不含 audio;audio 单独取得到 mp3
	var h histResp
	var dl struct{ Dialogs []Dialog }
	e.getJSON("/api/v1/dialogs", &dl)
	var album string
	for _, d := range dl.Dialogs {
		if d.Title == "相册" {
			album = d.ID
		}
	}
	e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/history", album), &h)
	if len(h.Items) != 1 || h.Items[0].Name != "clip.mp4" || h.Items[0].Video == nil || h.Items[0].Video.VCodec != "h264" {
		t.Fatalf("default types: %+v", h.Items)
	}
	e.getJSON(fmt.Sprintf("/api/v1/dialogs/%s/history?types=audio", album), &h)
	if len(h.Items) != 1 || h.Items[0].Name != "range.bin.mp3" {
		t.Fatalf("audio types: %v", names(h.Items))
	}
}

func TestFileRangeAndPoster(t *testing.T) {
	e := newEnv(t)
	e.login()
	var s struct{ Items []Item }
	e.getJSON("/api/v1/search?q=range&types=audio", &s)
	if len(s.Items) != 1 {
		t.Fatal("range file not found")
	}
	id := s.Items[0].ID
	resp, body := e.do("GET", "/api/v1/media/"+id+"/file", nil, map[string]string{"Range": "bytes=2-5"})
	if resp.StatusCode != 206 || string(body) != "2345" || resp.Header.Get("Content-Range") != "bytes 2-5/20" {
		t.Fatalf("range: %d %q %q", resp.StatusCode, body, resp.Header.Get("Content-Range"))
	}
	if ct := resp.Header.Get("Content-Type"); ct != "audio/mpeg" {
		t.Fatalf("content-type %q", ct)
	}
	if resp, _ := e.do("HEAD", "/api/v1/media/"+id+"/file?v=1", nil, nil); resp.StatusCode != 200 || !strings.Contains(resp.Header.Get("Cache-Control"), "immutable") {
		t.Fatalf("HEAD: %d %v", resp.StatusCode, resp.Header)
	}
	if resp, _ := e.do("GET", "/api/v1/media/999999/file", nil, nil); resp.StatusCode != 404 {
		t.Fatal("unknown media should 404")
	}

	e.getJSON("/api/v1/search?q=clip&types=video", &s)
	vid := s.Items[0].ID
	resp, body = e.do("GET", "/api/v1/media/"+vid+"/poster", nil, nil)
	if resp.StatusCode != 200 || len(body) < 12 || string(body[:4]) != "RIFF" || string(body[8:12]) != "WEBP" {
		t.Fatalf("poster: %d len=%d body=%s", resp.StatusCode, len(body), body)
	}
	// 缓存目录写在媒体根目录的 .mediahub 里,并且只有这一处新增
	if _, err := os.Stat(filepath.Join(e.lib, ".mediahub", "posters")); err != nil {
		t.Fatalf("poster not cached on drive: %v", err)
	}
	ents, _ := os.ReadDir(e.lib)
	for _, en := range ents {
		if en.Name() != "相册" && en.Name() != "大对话" && en.Name() != ".mediahub" {
			t.Fatalf("unexpected write in library root: %s", en.Name())
		}
	}
	// 第二次命中缓存
	resp2, body2 := e.do("GET", "/api/v1/media/"+vid+"/poster", nil, nil)
	if resp2.StatusCode != 200 || !bytes.Equal(body, body2) {
		t.Fatal("cached poster differs")
	}
	// 图片(非视频)没有封面
	e.getJSON("/api/v1/search?q=f12&types=photo", &s)
	if resp, _ := e.do("GET", "/api/v1/media/"+s.Items[0].ID+"/poster", nil, nil); resp.StatusCode != 404 {
		t.Fatalf("photo poster should 404, got %d", resp.StatusCode)
	}
}

func TestAdminKeyLoopbackOnly(t *testing.T) {
	e := newEnv(t)
	e.srv.AdminKey = "k-test-123"
	e.srv.StartedAt = time.Now()
	// 无令牌、带正确的本机管理密钥(httptest 来自回环地址)→ 放行
	resp, b := e.do("GET", "/api/v1/admin/status", nil, map[string]string{"X-Admin-Key": "k-test-123"})
	if resp.StatusCode != 200 || !strings.Contains(string(b), `"uptimeSec"`) {
		t.Fatalf("admin key rejected: %d %s", resp.StatusCode, b)
	}
	// 错误密钥 → 401
	if resp, _ := e.do("GET", "/api/v1/admin/status", nil, map[string]string{"X-Admin-Key": "wrong"}); resp.StatusCode != 401 {
		t.Fatalf("wrong key must be 401, got %d", resp.StatusCode)
	}
	// 管理密钥不能访问普通用户接口以外的东西?它能通过鉴权,但不应在未设置 AdminKey 时生效
	e.srv.AdminKey = ""
	if resp, _ := e.do("GET", "/api/v1/admin/status", nil, map[string]string{"X-Admin-Key": ""}); resp.StatusCode != 401 {
		t.Fatalf("empty key must not authenticate, got %d", resp.StatusCode)
	}
}

// 封面来源链:Jellyfin 已有图 → 直接转发且不进我们的缓存,并补 ThumbHash;posterSource=own 时不用 Jellyfin。
func TestPosterPrefersJellyfin(t *testing.T) {
	e := newEnv(t)
	e.login()

	var small bytes.Buffer
	img := image.NewRGBA(image.Rect(0, 0, 32, 18))
	for y := 0; y < 18; y++ {
		for x := 0; x < 32; x++ {
			img.Set(x, y, color.RGBA{uint8(x * 8), uint8(y * 14), 120, 255})
		}
	}
	if err := jpeg.Encode(&small, img, nil); err != nil {
		t.Fatal(err)
	}
	webp := append([]byte("RIFF\x10\x00\x00\x00WEBPVP8 "), make([]byte, 16)...)
	hits := map[string]int{}
	jf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.Contains(r.Header.Get("Authorization"), `Token="k"`) {
			http.Error(w, "no key", 401)
			return
		}
		if r.URL.Query().Get("format") == "Jpg" {
			hits["jpg32"]++
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write(small.Bytes())
			return
		}
		hits["webp"]++
		w.Header().Set("Content-Type", "image/webp")
		w.Write(webp)
	}))
	defer jf.Close()
	e.srv.Poster.JF = jellyfin.New(jf.URL, "k")

	var s struct{ Items []Item }
	e.getJSON("/api/v1/search?q=clip&types=video", &s)
	vid := s.Items[0].ID
	e.srv.DB.Exec(`UPDATE media SET jf_item_id='abc', jf_img_tag='t1' WHERE id=?`, vid)

	resp, body := e.do("GET", "/api/v1/media/"+vid+"/poster", nil, nil)
	if resp.StatusCode != 200 || resp.Header.Get("X-Poster-Source") != "jellyfin" || !bytes.Equal(body, webp) {
		t.Fatalf("expected jellyfin poster, got %d source=%q len=%d", resp.StatusCode, resp.Header.Get("X-Poster-Source"), len(body))
	}
	if hits["jpg32"] != 1 {
		t.Fatalf("thumbhash source image should be fetched once, hits=%v", hits)
	}
	var th []byte
	e.srv.DB.QueryRow(`SELECT thumbhash FROM media WHERE id=?`, vid).Scan(&th)
	if len(th) < 5 {
		t.Fatalf("thumbhash not stored: %v", th)
	}
	// 没有往我们的缓存里存第二份
	var n int
	e.srv.DB.QueryRow(`SELECT count(*) FROM cache_entries WHERE media_id=?`, vid).Scan(&n)
	if n != 0 {
		t.Fatalf("jellyfin poster must not be cached again, entries=%d", n)
	}

	// posterSource=own:不再用 Jellyfin,自己生成
	e.srv.Cfg.Video.PosterSource = "own"
	before := hits["webp"]
	resp, _ = e.do("GET", "/api/v1/media/"+vid+"/poster", nil, nil)
	if resp.Header.Get("X-Poster-Source") != "own" || hits["webp"] != before {
		t.Fatalf("own mode should not use jellyfin: source=%q hits=%v", resp.Header.Get("X-Poster-Source"), hits)
	}
}

// 服务端图片转换:HEIC(ffmpeg,含旋转)与 RAW(ExifTool 内嵌预览 + 方向)都能得到 JPEG,尺寸不超过请求档位,结果被缓存。
func TestRenderHeicAndRaw(t *testing.T) {
	e := newEnv(t)
	e.login()
	pr := projectRoot(t)
	heic := filepath.Join(pr, "testdata", "library", "旅行", "2022-上海", "example.heic")
	raw := filepath.Join(pr, "testdata", "library", "相机RAW", "Canon_EOS_R6.CR3")
	for _, f := range []string{heic, raw} {
		b, err := os.ReadFile(f)
		if err != nil {
			t.Skipf("缺少样本 %s", f)
		}
		os.WriteFile(filepath.Join(e.lib, "相册", filepath.Base(f)), b, 0o644)
	}
	roots, _ := e.srv.Idx.SyncRoots()
	if err := e.srv.Idx.ScanRoot(context.Background(), roots[0]); err != nil {
		t.Fatal(err)
	}
	_ = e.srv.Idx.Enrich(context.Background(), roots[0])
	for _, c := range []struct{ q, ext string }{{"example", "heic"}, {"Canon_EOS", "cr3"}} {
		var s struct{ Items []Item }
		e.getJSON("/api/v1/search?q="+c.q+"&types=photo", &s)
		if len(s.Items) != 1 {
			t.Fatalf("%s: search=%d", c.q, len(s.Items))
		}
		id := s.Items[0].ID
		resp, body := e.do("GET", "/api/v1/media/"+id+"/render?w=900", nil, nil)
		if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/jpeg" || len(body) < 2000 || body[0] != 0xFF || body[1] != 0xD8 {
			t.Fatalf("%s render: %d ct=%q len=%d", c.ext, resp.StatusCode, resp.Header.Get("Content-Type"), len(body))
		}
		cfg, err := jpeg.DecodeConfig(bytes.NewReader(body))
		if err != nil {
			t.Fatalf("%s: 不是有效的 JPEG: %v", c.ext, err)
		}
		if long := max(cfg.Width, cfg.Height); long > 960 || long < 400 { // 900 向上取到 960 档
			t.Fatalf("%s: 输出尺寸 %dx%d 不符合 960 档", c.ext, cfg.Width, cfg.Height)
		}
		_, body2 := e.do("GET", "/api/v1/media/"+id+"/render?w=900", nil, nil)
		if !bytes.Equal(body, body2) {
			t.Fatalf("%s: 缓存命中的结果应与第一次一致", c.ext)
		}
	}
}

func TestRandom(t *testing.T) {
	e := newEnv(t)
	e.login()
	var a, b struct{ Items []Item }
	e.getJSON("/api/v1/random?types=photo&limit=40", &a)
	if len(a.Items) != 40 {
		t.Fatalf("random photo = %d", len(a.Items))
	}
	seen := map[string]bool{}
	for _, it := range a.Items {
		if seen[it.ID] || it.Type != "photo" {
			t.Fatalf("重复或类型不对: %+v", it)
		}
		seen[it.ID] = true
	}
	e.getJSON("/api/v1/random?types=photo&limit=40", &b)
	same := 0
	for _, it := range b.Items {
		if seen[it.ID] {
			same++
		}
	}
	if same == 40 {
		t.Fatal("两次随机结果完全一样")
	}
	e.getJSON("/api/v1/random?types=video&limit=10", &a)
	for _, it := range a.Items {
		if it.Type != "video" {
			t.Fatalf("video 请求拿到 %s", it.Type)
		}
	}
	if resp, _ := e.do("GET", "/api/v1/random?types=nope", nil, nil); resp.StatusCode != 400 {
		t.Fatal("bad types should be 400")
	}
}

func TestRandomRoots(t *testing.T) {
	e := newEnv(t)
	e.login()
	var dl struct{ Dialogs []Dialog }
	e.getJSON("/api/v1/dialogs", &dl)
	root := dl.Dialogs[0].RootID
	var a struct{ Items []Item }
	e.getJSON("/api/v1/random?types=photo&limit=20&roots="+root, &a)
	if len(a.Items) != 20 {
		t.Fatalf("roots=%s 取到 %d", root, len(a.Items))
	}
	e.getJSON("/api/v1/random?types=photo&limit=20&roots=999999", &a)
	if len(a.Items) != 0 {
		t.Fatalf("不存在的根目录应该是空,取到 %d", len(a.Items))
	}
	if resp, _ := e.do("GET", "/api/v1/random?roots=1,abc", nil, nil); resp.StatusCode != 400 {
		t.Fatal("bad roots should be 400")
	}
}

func TestWarmRandom(t *testing.T) {
	e := newEnv(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e.srv.WarmRandom(ctx)
	for i := 0; i < 300; i++ {
		e.srv.rnd.Lock()
		ok, n, seqs := e.srv.rnd.ready, len(e.srv.rnd.roots), len(e.srv.rnd.seqs)
		e.srv.rnd.Unlock()
		// 「全部」+ 每个根目录,各 3 种类型 × randSlots 份序列
		if ok && n > 0 && seqs >= (1+n)*3*randSlots {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("30 秒内没有把全部序列预先生成好")
}

func TestRandomReset(t *testing.T) {
	e := newEnv(t)
	e.login()
	ids := func() string {
		var r struct{ Items []Item }
		e.getJSON("/api/v1/random?types=photo&limit=30&seed=3&batch=0", &r)
		out := ""
		for _, it := range r.Items {
			out += it.ID + ","
		}
		return out
	}
	before := ids()
	if before == "" || before != ids() {
		t.Fatal("重置前同一个 seed 应该稳定")
	}
	if resp, _ := e.do("POST", "/api/v1/random/reset", nil, nil); resp.StatusCode != 200 {
		t.Fatalf("reset = %d", resp.StatusCode)
	}
	after := ids()
	if after == before {
		t.Fatal("重置后应该是一套全新的序列")
	}
	if after != ids() {
		t.Fatal("重置后的序列也要稳定")
	}
}
