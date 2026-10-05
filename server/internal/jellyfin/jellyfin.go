// Package jellyfin 是 MediaHub 访问 Jellyfin(只当转码器/封面来源使用)的精简客户端。
// 只调用到的几个接口:列出视频条目、取主图、探测是否在线。
package jellyfin

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Client struct {
	BaseURL string
	APIKey  string
	HTTP    *http.Client
}

func New(baseURL, apiKey string) *Client {
	return &Client{BaseURL: strings.TrimRight(baseURL, "/"), APIKey: apiKey, HTTP: &http.Client{Timeout: 20 * time.Second}}
}

func (c *Client) req(ctx context.Context, path string) (*http.Request, error) {
	r, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+path, nil)
	if err != nil {
		return nil, err
	}
	// 用请求头带密钥,不放进 URL(避免写进任何日志)
	r.Header.Set("Authorization", `MediaBrowser Token="`+c.APIKey+`"`)
	return r, nil
}

// Ping 判断 Jellyfin 是否在线。
func (c *Client) Ping(ctx context.Context) bool {
	r, err := c.req(ctx, "/System/Info/Public")
	if err != nil {
		return false
	}
	ctx2, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	resp, err := c.HTTP.Do(r.WithContext(ctx2))
	if err != nil {
		return false
	}
	resp.Body.Close()
	return resp.StatusCode == 200
}

type Item struct {
	ID         string
	Path       string
	PrimaryTag string
}

type itemsResp struct {
	Items []struct {
		ID        string            `json:"Id"`
		Path      string            `json:"Path"`
		ImageTags map[string]string `json:"ImageTags"`
	} `json:"Items"`
	Total int `json:"TotalRecordCount"`
}

// ListVideos 分页列出所有视频条目(含路径和主图标签)。
func (c *Client) ListVideos(ctx context.Context) ([]Item, error) {
	var out []Item
	for start := 0; ; {
		q := "/Items?Recursive=true&IncludeItemTypes=Video&Fields=Path&EnableImageTypes=Primary&ImageTypeLimit=1" +
			"&StartIndex=" + strconv.Itoa(start) + "&Limit=500"
		r, err := c.req(ctx, q)
		if err != nil {
			return nil, err
		}
		resp, err := c.HTTP.Do(r)
		if err != nil {
			return nil, err
		}
		var page itemsResp
		err = json.NewDecoder(resp.Body).Decode(&page)
		resp.Body.Close()
		if err != nil || resp.StatusCode != 200 {
			return nil, fmt.Errorf("jellyfin items: HTTP %d %v", resp.StatusCode, err)
		}
		for _, it := range page.Items {
			out = append(out, Item{ID: it.ID, Path: it.Path, PrimaryTag: it.ImageTags["Primary"]})
		}
		start += len(page.Items)
		if len(page.Items) == 0 || start >= page.Total {
			return out, nil
		}
	}
}

// PrimaryImage 取主图(Jellyfin 按需缩放并自己缓存)。format: Webp / Jpg。
func (c *Client) PrimaryImage(ctx context.Context, id, tag string, width int, format string) ([]byte, string, error) {
	q := url.Values{}
	q.Set("fillWidth", strconv.Itoa(width))
	q.Set("quality", "75")
	q.Set("format", format)
	if tag != "" {
		q.Set("tag", tag)
	}
	r, err := c.req(ctx, "/Items/"+url.PathEscape(id)+"/Images/Primary?"+q.Encode())
	if err != nil {
		return nil, "", err
	}
	resp, err := c.HTTP.Do(r)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, "", fmt.Errorf("jellyfin image: HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	return b, resp.Header.Get("Content-Type"), err
}

// NormPath 统一路径写法后用于比较(盘符大小写、斜杠方向、长路径前缀)。
func NormPath(p string) string {
	p = strings.TrimPrefix(p, `\\?\`)
	return strings.ToLower(filepath.ToSlash(filepath.Clean(p)))
}

// Sync 建立 路径 → Jellyfin 条目 的映射,写入 media.jf_item_id / jf_img_tag。
// 返回匹配到的视频数。匹配不到的视频映射被清空(例如已从 Jellyfin 库里移除)。
func Sync(ctx context.Context, db *sql.DB, c *Client) (matched int, err error) {
	items, err := c.ListVideos(ctx)
	if err != nil {
		return 0, err
	}
	byPath := make(map[string]Item, len(items))
	for _, it := range items {
		byPath[NormPath(it.Path)] = it
	}
	rows, err := db.QueryContext(ctx, `SELECT m.id, ro.path, m.dir_rel, m.name, COALESCE(m.jf_item_id,''), COALESCE(m.jf_img_tag,'')
		FROM media m JOIN dialogs d ON d.id=m.dialog_id JOIN roots ro ON ro.id=m.root_id WHERE m.type=1`)
	if err != nil {
		return 0, err
	}
	type upd struct {
		id       int64
		item, tg string
	}
	var todo []upd
	for rows.Next() {
		var id int64
		var root, rel, name, curID, curTag string
		if err := rows.Scan(&id, &root, &rel, &name, &curID, &curTag); err != nil {
			rows.Close()
			return 0, err
		}
		it, ok := byPath[NormPath(filepath.Join(root, rel, name))]
		if ok {
			matched++
		}
		if it.ID != curID || it.PrimaryTag != curTag {
			todo = append(todo, upd{id, it.ID, it.PrimaryTag})
		}
	}
	rows.Close()
	if len(todo) == 0 {
		return matched, nil
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	st, err := tx.PrepareContext(ctx, `UPDATE media SET jf_item_id=NULLIF(?,''), jf_img_tag=NULLIF(?,'') WHERE id=?`)
	if err != nil {
		return 0, err
	}
	defer st.Close()
	for _, u := range todo {
		if _, err := st.ExecContext(ctx, u.item, u.tg, u.id); err != nil {
			return 0, err
		}
	}
	return matched, tx.Commit()
}
