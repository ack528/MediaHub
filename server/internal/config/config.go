// Package config 读取 MediaHub 的 JSON 配置并填充默认值。
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type Root struct {
	Path  string `json:"path"`
	Label string `json:"label"`
}

type Config struct {
	// Path 是这份配置从哪个文件读来的;RootsUnset:文件不存在或里面根本没有 roots 这一项(多半是更新 / 换目录后配置丢了)
	Path       string `json:"-"`
	RootsUnset bool   `json:"-"`

	Listen  string   `json:"listen"`
	DataDir string   `json:"dataDir"`
	Roots   []Root   `json:"roots"`
	Exclude []string `json:"exclude"`
	Cache   struct {
		PerDriveQuotaGB int    `json:"perDriveQuotaGB"`
		FallbackDir     string `json:"fallbackDir"`
		// MinFreeMarginGB:盘上剩余空间需 >= 配额 + 该余量才启用盘内缓存
		MinFreeMarginGB int `json:"minFreeMarginGB"`
	} `json:"cache"`
	Images struct {
		ThumbMode       string `json:"thumbMode"` // off | exif
		FallbackConvert bool   `json:"fallbackConvert"`
	} `json:"images"`
	Video struct {
		Engine           string `json:"engine"` // auto(默认:有 Jellyfin 且已收录就用它,否则内置 ffmpeg)| ffmpeg(只用内置)
		MaxTranscodes    int    `json:"maxTranscodes"`
		HWAccel          string `json:"hwaccel"`
		SoftwareFallback bool   `json:"softwareFallback"`
		// PosterSource:auto = 优先使用 Jellyfin 已生成的封面,没有再自己生成;own = 只自己生成
		PosterSource string `json:"posterSource"`
	} `json:"video"`
	Jellyfin struct {
		URL    string `json:"url"`
		APIKey string `json:"apiKey"`
	} `json:"jellyfin"`
	Auth struct {
		TokenDays int `json:"tokenDays"`
	} `json:"auth"`
	Tools struct {
		FFmpeg   string `json:"ffmpeg"`
		FFprobe  string `json:"ffprobe"`
		ExifTool string `json:"exiftool"`
		Vips     string `json:"vips"`
	} `json:"tools"`
	// IndexOtherFiles:是否把非媒体文件(文档等)也建索引,默认 false
	IndexOtherFiles bool `json:"indexOtherFiles"`
	// TLS:局域网内的加密传输(HTTPS,自签名证书 + 手机端证书固定)。默认开启;关闭后退回明文 HTTP。
	TLS struct {
		Enabled bool `json:"enabled"`
	} `json:"tls"`
	// Update:自动更新(检查 GitHub Releases,发现新版本后自动下载安装并重启服务;见 internal/update)
	Update struct {
		Enabled         bool     `json:"enabled"`         // 默认开启
		Repo            string   `json:"repo"`            // owner/name,默认 ack528/MediaHub
		IntervalMinutes int      `json:"intervalMinutes"` // 检查间隔,默认 10 分钟
		Mirror          string   `json:"mirror"`          // 自己指定的加速前缀(排在内置镜像前面),默认空
		Mirrors         []string `json:"mirrors"`         // 内置的 GitHub 镜像前缀;直连更慢或失败时自动使用。可在配置里改:[] = 不用镜像
	} `json:"update"`
	// AdminListen:仅本机的管理端口(明文 HTTP,只监听回环地址,桌面管理程序用它)。留空 = 监听端口 + 1。TLS 关闭时不使用。
	AdminListen string `json:"adminListen"`
	// Log.Level:服务端日志级别 debug / info / warn / error,默认 info
	Log struct {
		Level string `json:"level"`
	} `json:"log"`
	Scan struct {
		// SkipWithinHours:启动时,距上次"完整扫描"不足这么多小时就不再重新扫描(被中断的扫描总是会续扫)。0 = 每次启动都扫描
		SkipWithinHours int `json:"skipWithinHours"`
		// IntervalHours:服务运行期间定时重新扫描的间隔(小时);0 = 不定时扫描
		IntervalHours int `json:"intervalHours"`
	} `json:"scan"`
}

// DefaultUpdateMirrors 是内置的 GitHub 下载镜像(前缀拼在完整的 github.com 地址前面)。
// 检查更新时和直连同时探测,谁先响应用谁;失败再按顺序试其余的。
var DefaultUpdateMirrors = []string{
	"https://ghfast.top/",
	"https://gh-proxy.com/",
	"https://ghproxy.net/",
	"https://gh.llkk.cc/",
}

func Default() *Config {
	c := &Config{}
	c.Listen = "0.0.0.0:8480"
	c.DataDir = `C:\MediaHub\data`
	c.Exclude = []string{"$RECYCLE.BIN", "System Volume Information", ".mediahub", "@eaDir", "Thumbs.db", "desktop.ini"}
	c.Cache.PerDriveQuotaGB = 50
	c.Cache.MinFreeMarginGB = 5
	c.Images.ThumbMode = "off"
	c.Images.FallbackConvert = true
	c.Video.Engine = "auto"
	c.Video.MaxTranscodes = 2
	c.Video.HWAccel = "qsv"
	c.Video.SoftwareFallback = true
	c.Video.PosterSource = "auto"
	c.Jellyfin.URL = "http://127.0.0.1:8096"
	c.Auth.TokenDays = 180
	c.Log.Level = "info"
	c.TLS.Enabled = true
	c.Update.Enabled = true
	c.Update.Repo = "ack528/MediaHub"
	c.Update.IntervalMinutes = 10
	c.Update.Mirrors = append([]string(nil), DefaultUpdateMirrors...)
	c.Scan.SkipWithinHours = 12
	c.Scan.IntervalHours = 24
	return c
}

// SaveRoots 只更新配置文件里的 roots 一项(其余设置原样保留),原子写入。
func SaveRoots(path string, roots []Root) error {
	m := map[string]any{}
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal([]byte(strings.TrimPrefix(string(b), "\ufeff")), &m)
	}
	m["roots"] = roots
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// AdminAddr 返回本机管理端口的监听地址(回环)。
func (c *Config) AdminAddr() string {
	if c.AdminListen != "" {
		return c.AdminListen
	}
	i := strings.LastIndex(c.Listen, ":")
	port := 8480
	if i >= 0 {
		fmt.Sscanf(c.Listen[i+1:], "%d", &port)
	}
	if port >= 65535 {
		port = 65534
	} else {
		port++
	}
	return fmt.Sprintf("127.0.0.1:%d", port)
}

// Load 读取配置文件;文件不存在则返回默认值。root 用于解析 tools 的默认位置(项目根目录)。
func Load(path, projectRoot string) (*Config, error) {
	c := Default()
	c.Path = path
	c.RootsUnset = true
	if b, err := os.ReadFile(path); err == nil {
		b = []byte(strings.TrimPrefix(string(b), "\ufeff"))
		if err := json.Unmarshal(b, c); err != nil {
			return nil, fmt.Errorf("config %s: %w", path, err)
		}
		var probe map[string]json.RawMessage
		if json.Unmarshal(b, &probe) == nil {
			_, has := probe["roots"]
			c.RootsUnset = !has
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	if c.Cache.FallbackDir == "" {
		c.Cache.FallbackDir = filepath.Join(projectRoot, "runtime", "cache")
	}
	def := func(cur *string, rel string) {
		if *cur == "" {
			*cur = filepath.Join(projectRoot, "tools", filepath.FromSlash(rel))
		}
	}
	def(&c.Tools.FFmpeg, "ffmpeg/ffmpeg.exe")
	def(&c.Tools.FFprobe, "ffmpeg/ffprobe.exe")
	def(&c.Tools.ExifTool, "exiftool/exiftool.exe")
	def(&c.Tools.Vips, "vips/bin/vips.exe")
	for i := range c.Roots {
		if c.Roots[i].Label == "" {
			c.Roots[i].Label = strings.TrimRight(c.Roots[i].Path, `\/`)
		}
	}
	return c, nil
}
