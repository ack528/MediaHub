package jellyfin

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"
)

// 下面是 MediaHub 管理 Jellyfin 的几个接口:自动为媒体根目录建库(用户不用进 Jellyfin 后台配置)、
// 把转码设置(硬件加速)同步给 Jellyfin、把 HLS 转码流转发给手机。

// Stream 发起一个不限总时长的请求(转码分片 / 播放列表可能要等 ffmpeg 出第一段),用 ctx 控制取消。
func (c *Client) Stream(ctx context.Context, method, pathAndQuery string) (*http.Response, error) {
	r, err := http.NewRequestWithContext(ctx, method, c.BaseURL+pathAndQuery, nil)
	if err != nil {
		return nil, err
	}
	r.Header.Set("Authorization", `MediaBrowser Token="`+c.APIKey+`"`)
	cl := &http.Client{Transport: &http.Transport{ResponseHeaderTimeout: 90 * time.Second, MaxIdleConnsPerHost: 8}}
	return cl.Do(r)
}

func (c *Client) doJSON(ctx context.Context, method, path string, in any, out any) error {
	var body io.Reader
	if in != nil {
		b, _ := json.Marshal(in)
		body = bytes.NewReader(b)
	}
	r, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, body)
	if err != nil {
		return err
	}
	r.Header.Set("Authorization", `MediaBrowser Token="`+c.APIKey+`"`)
	if in != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.HTTP.Do(r)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 400))
		return fmt.Errorf("Jellyfin %s %s -> %d %s", method, strings.SplitN(path, "?", 2)[0], resp.StatusCode, strings.TrimSpace(string(b)))
	}
	if out != nil {
		return json.NewDecoder(resp.Body).Decode(out)
	}
	return nil
}

type VirtualFolder struct {
	Name           string   `json:"Name"`
	Locations      []string `json:"Locations"`
	CollectionType string   `json:"CollectionType"`
	ItemID         string   `json:"ItemId"`
}

// Folders 列出 Jellyfin 现有的媒体库。
func (c *Client) Folders(ctx context.Context) ([]VirtualFolder, error) {
	var out []VirtualFolder
	err := c.doJSON(ctx, http.MethodGet, "/Library/VirtualFolders", nil, &out)
	return out, err
}

// EnsureLibraries 保证每个媒体根目录都在 Jellyfin 里有对应的媒体库(类型"家庭视频",
// 关闭实时监控、章节图、trickplay、本地元数据,只为转码和现成封面服务)。已被现有库覆盖的目录不会重复创建。
// 返回新建的库数量。
func (c *Client) EnsureLibraries(ctx context.Context, roots []string, log *slog.Logger) (int, error) {
	folders, err := c.Folders(ctx)
	if err != nil {
		return 0, err
	}
	created := 0
	for _, root := range roots {
		nr := strings.TrimRight(NormPath(root), "/")
		covered := false
		for _, f := range folders {
			for _, loc := range f.Locations {
				nl := strings.TrimRight(NormPath(loc), "/")
				if nl == nr || strings.HasPrefix(nr+"/", nl+"/") || strings.HasPrefix(nl+"/", nr+"/") {
					covered = true // 已有库就是这个目录、它的上级,或它下面的子目录(Jellyfin 不允许路径重叠)
				}
			}
		}
		if covered {
			continue
		}
		trimmed := strings.TrimRight(root, `\/`)
		base := filepath.Base(trimmed)
		if vol := filepath.VolumeName(root); vol != "" && trimmed == vol {
			base = vol // 盘符根目录,库名就叫 "MediaHub D:"
		}
		name := "MediaHub " + base
		q := url.Values{"name": {name}, "collectionType": {"homevideos"}, "paths": {root}, "refreshLibrary": {"true"}}
		opts := map[string]any{"LibraryOptions": map[string]any{
			"Enabled": true, "EnablePhotos": false, "EnableRealtimeMonitor": false,
			"EnableChapterImageExtraction": false, "ExtractChapterImagesDuringLibraryScan": false,
			"EnableTrickplayImageExtraction": false, "ExtractTrickplayImagesDuringLibraryScan": false,
			"SaveLocalMetadata": false, "EnableEmbeddedTitles": false, "AutomaticRefreshIntervalDays": 0,
			"PathInfos": []map[string]string{{"Path": root}},
		}}
		if err := c.doJSON(ctx, http.MethodPost, "/Library/VirtualFolders?"+q.Encode(), opts, nil); err != nil {
			if log != nil {
				log.Warn("为 Jellyfin 创建媒体库失败(可能与现有库路径重叠)", "root", root, "err", err)
			}
			continue
		}
		created++
		if log != nil {
			log.Info("已在 Jellyfin 里创建媒体库", "name", name, "path", root)
		}
	}
	return created, nil
}

// Refresh 让 Jellyfin 重新扫描所有媒体库(异步,马上返回)。
func (c *Client) Refresh(ctx context.Context) error {
	return c.doJSON(ctx, http.MethodPost, "/Library/Refresh", nil, nil)
}

// ApplyEncoding 把硬件加速设置同步给 Jellyfin(hw = "qsv" 或 "none")。失败不影响其它功能。
func (c *Client) ApplyEncoding(ctx context.Context, hw string) error {
	var cur map[string]any
	if err := c.doJSON(ctx, http.MethodGet, "/System/Configuration/encoding", nil, &cur); err != nil {
		return err
	}
	if hw == "qsv" {
		cur["HardwareAccelerationType"] = "qsv"
		cur["EnableHardwareEncoding"] = true
		cur["EnableTonemapping"] = true
		cur["EnableDecodingColorDepth10Hevc"] = true
		cur["EnableDecodingColorDepth10Vp9"] = true
		cur["AllowHevcEncoding"] = false // 输出统一 H.264,手机兼容性最好
	} else {
		cur["HardwareAccelerationType"] = "none"
		cur["EnableHardwareEncoding"] = false
	}
	return c.doJSON(ctx, http.MethodPost, "/System/Configuration/encoding", cur, nil)
}

// StopEncoding 结束某个播放会话对应的转码(手机退出播放时调用,及时释放 CPU / 核显)。
func (c *Client) StopEncoding(ctx context.Context, deviceID, playSessionID string) error {
	q := url.Values{"deviceId": {deviceID}, "playSessionId": {playSessionID}}
	return c.doJSON(ctx, http.MethodDelete, "/Videos/ActiveEncodings?"+q.Encode(), nil, nil)
}
