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
		Engine           string `json:"engine"` // jellyfin
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
	Scan            struct {
		// SkipWithinHours:启动时,距上次"完整扫描"不足这么多小时就不再重新扫描(被中断的扫描总是会续扫)。0 = 每次启动都扫描
		SkipWithinHours int `json:"skipWithinHours"`
		// IntervalHours:服务运行期间定时重新扫描的间隔(小时);0 = 不定时扫描
		IntervalHours int `json:"intervalHours"`
	} `json:"scan"`
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
	c.Video.Engine = "jellyfin"
	c.Video.MaxTranscodes = 2
	c.Video.HWAccel = "qsv"
	c.Video.SoftwareFallback = true
	c.Video.PosterSource = "auto"
	c.Jellyfin.URL = "http://127.0.0.1:8096"
	c.Auth.TokenDays = 180
	c.Scan.SkipWithinHours = 12
	c.Scan.IntervalHours = 24
	return c
}

// Load 读取配置文件;文件不存在则返回默认值。root 用于解析 tools 的默认位置(项目根目录)。
func Load(path, projectRoot string) (*Config, error) {
	c := Default()
	if b, err := os.ReadFile(path); err == nil {
		b = []byte(strings.TrimPrefix(string(b), "\ufeff"))
		if err := json.Unmarshal(b, c); err != nil {
			return nil, fmt.Errorf("config %s: %w", path, err)
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
