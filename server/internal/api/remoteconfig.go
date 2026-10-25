package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type configField struct {
	Key     string   `json:"key"`
	Group   string   `json:"group"`
	Title   string   `json:"title"`
	Kind    string   `json:"kind"`
	Min     float64  `json:"min,omitempty"`
	Max     float64  `json:"max,omitempty"`
	Options []string `json:"options,omitempty"`
	Value   any      `json:"value"`
}

var remoteFields = []configField{
	{Key: "browseHistory.enabled", Group: "浏览历史", Title: "保存浏览历史", Kind: "bool"},
	{Key: "browseHistory.intervalSeconds", Group: "浏览历史", Title: "自动保存间隔（秒）", Kind: "number", Min: 10, Max: 86400},
	{Key: "browseHistory.maxEntries", Group: "浏览历史", Title: "每用户每群历史上限", Kind: "number", Min: 1, Max: 1000},
	{Key: "browseHistory.retentionDays", Group: "浏览历史", Title: "历史保留天数", Kind: "number", Min: 1, Max: 3650},
	{Key: "listen", Group: "网络", Title: "监听地址", Kind: "string"},
	{Key: "tls.enabled", Group: "网络", Title: "HTTPS 加密", Kind: "bool"},
	{Key: "roots", Group: "媒体库", Title: "媒体根目录", Kind: "roots"},
	{Key: "exclude", Group: "媒体库", Title: "排除的目录名", Kind: "lines"},
	{Key: "indexOtherFiles", Group: "媒体库", Title: "索引其他类型文件", Kind: "bool"},
	{Key: "cache.perDriveQuotaGB", Group: "缓存与图片", Title: "每盘缓存上限（GB）", Kind: "number", Min: 1, Max: 10000},
	{Key: "cache.minFreeMarginGB", Group: "缓存与图片", Title: "保留磁盘空闲空间（GB）", Kind: "number", Max: 10000},
	{Key: "images.fallbackConvert", Group: "缓存与图片", Title: "兼容格式图片转换", Kind: "bool"},
	{Key: "images.thumbMode", Group: "缓存与图片", Title: "图片内嵌缩略图", Kind: "choice", Options: []string{"off", "exif"}},
	{Key: "video.engine", Group: "视频转码", Title: "转码引擎", Kind: "choice", Options: []string{"auto", "ffmpeg"}},
	{Key: "video.hwaccel", Group: "视频转码", Title: "硬件加速", Kind: "choice", Options: []string{"qsv", "none"}},
	{Key: "video.maxTranscodes", Group: "视频转码", Title: "最大同时转码数", Kind: "number", Min: 1, Max: 8},
	{Key: "video.maxTranscodeBitrate", Group: "视频转码", Title: "转码码率上限（bps）", Kind: "number", Min: 200000, Max: 60000000},
	{Key: "video.softwareFallback", Group: "视频转码", Title: "硬编失败后软件编码", Kind: "bool"},
	{Key: "video.allowSoftwareHEVC", Group: "视频转码", Title: "允许软件 H.265 编码", Kind: "bool"},
	{Key: "optimize.autoReplace", Group: "存储优化", Title: "压缩后自动替换", Kind: "bool"},
	{Key: "optimize.purgeOriginal", Group: "存储优化", Title: "替换后永久删除原文件", Kind: "bool"},
	{Key: "optimize.trashKeepDays", Group: "存储优化", Title: "原文件保留天数（0 为永久保留）", Kind: "number", Max: 3650},
	{Key: "optimize.maxThreads", Group: "存储优化", Title: "压缩线程数（0 为自动）", Kind: "number", Max: 256},
	{Key: "optimize.window", Group: "存储优化", Title: "压缩时段（空为全天，例 01:00-07:00）", Kind: "string"},
	{Key: "priority.enabled", Group: "后台调度", Title: "播放与浏览优先", Kind: "bool"},
	{Key: "priority.pauseCompress", Group: "后台调度", Title: "客户端活跃时暂停压缩", Kind: "bool"},
	{Key: "priority.activeWindowSec", Group: "后台调度", Title: "客户端活跃判定（秒）", Kind: "number", Min: 5, Max: 3600},
	{Key: "update.enabled", Group: "更新与日志", Title: "自动更新服务端", Kind: "bool"},
	{Key: "update.cacheApp", Group: "更新与日志", Title: "缓存安卓更新包", Kind: "bool"},
	{Key: "update.intervalMinutes", Group: "更新与日志", Title: "检查更新间隔（分钟）", Kind: "number", Min: 1, Max: 10080},
	{Key: "log.level", Group: "更新与日志", Title: "日志级别", Kind: "choice", Options: []string{"debug", "info", "warn", "error"}},
}

func (s *Server) isRemoteAdmin(r *http.Request) bool {
	var yes int
	return s.DB.QueryRow(`SELECT is_admin FROM users WHERE id=?`, userID(r)).Scan(&yes) == nil && yes == 1
}

func (s *Server) remoteAdmin(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.isRemoteAdmin(r) {
			writeErr(w, 403, "admin.required", "此账号没有服务器配置权限，请使用管理员账号")
			return
		}
		h(w, r)
	}
}

func (s *Server) remoteStatus(w http.ResponseWriter, r *http.Request) {
	var media, dialogs int64
	_ = s.DB.QueryRow(`SELECT count(*) FROM media WHERE root_id IN (SELECT id FROM roots WHERE enabled=1)`).Scan(&media)
	_ = s.DB.QueryRow(`SELECT count(*) FROM dialogs WHERE root_id IN (SELECT id FROM roots WHERE enabled=1)`).Scan(&dialogs)
	jobs := map[string]int64{}
	if rows, err := s.DB.Query(`SELECT state,count(*) FROM opt_job GROUP BY state`); err == nil {
		for rows.Next() {
			var state string
			var n int64
			if rows.Scan(&state, &n) == nil {
				jobs[state] = n
			}
		}
		rows.Close()
	}
	var progress any = []any{}
	if s.Idx != nil {
		progress = s.Idx.Progress()
	}
	writeJSON(w, 200, map[string]any{"version": s.Version, "uptimeSec": int64(time.Since(s.StartedAt).Seconds()), "media": media, "dialogs": dialogs, "index": progress, "jobs": jobs, "canManage": s.isRemoteAdmin(r), "restartAvailable": s.Restart != nil, "priority": s.Gov.Snapshot()})
}

func fieldValue(doc map[string]any, key string) any {
	var v any = doc
	for _, part := range strings.Split(key, ".") {
		m, ok := v.(map[string]any)
		if !ok {
			return nil
		}
		v = m[part]
	}
	return v
}
func setField(doc map[string]any, key string, v any) {
	parts := strings.Split(key, ".")
	m := doc
	for _, p := range parts[:len(parts)-1] {
		n, ok := m[p].(map[string]any)
		if !ok {
			n = map[string]any{}
			m[p] = n
		}
		m = n
	}
	m[parts[len(parts)-1]] = v
}
func revision(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func (s *Server) configDocument() (map[string]any, []byte, error) {
	b, err := os.ReadFile(s.Cfg.Path)
	if err != nil && !os.IsNotExist(err) {
		return nil, nil, err
	}
	if len(b) == 0 {
		b, err = json.Marshal(s.Cfg)
		if err != nil {
			return nil, nil, err
		}
	}
	var doc map[string]any
	err = json.Unmarshal([]byte(strings.TrimPrefix(string(b), "\ufeff")), &doc)
	if err == nil && doc == nil {
		err = fmt.Errorf("配置文件必须是 JSON 对象")
	}
	return doc, b, err
}
func (s *Server) remoteConfig(w http.ResponseWriter, r *http.Request) {
	s.configMu.Lock()
	defer s.configMu.Unlock()
	doc, b, err := s.configDocument()
	if err != nil {
		writeErr(w, 500, "config.read", err.Error())
		return
	}
	var defaults map[string]any
	raw, _ := json.Marshal(s.Cfg)
	_ = json.Unmarshal(raw, &defaults)
	fields := append([]configField(nil), remoteFields...)
	for i := range fields {
		fields[i].Value = fieldValue(doc, fields[i].Key)
		if fields[i].Value == nil {
			fields[i].Value = fieldValue(defaults, fields[i].Key)
		}
	}
	writeJSON(w, 200, map[string]any{"revision": revision(b), "fields": fields, "restartRequired": true, "configPath": s.Cfg.Path, "dataDir": s.Cfg.DataDir})
}
func validateConfigValue(f configField, v any) error {
	bad := func() error { return fmt.Errorf("%s 的值不合法", f.Title) }
	switch f.Kind {
	case "bool":
		if _, ok := v.(bool); !ok {
			return bad()
		}
	case "number":
		n, ok := v.(float64)
		if !ok || math.IsNaN(n) || math.Trunc(n) != n || n < f.Min || n > f.Max {
			return bad()
		}
	case "choice":
		s, ok := v.(string)
		if !ok {
			return bad()
		}
		for _, o := range f.Options {
			if s == o {
				return nil
			}
		}
		return bad()
	case "string":
		str, ok := v.(string)
		if !ok || len(str) > 2048 {
			return bad()
		}
		if f.Key == "listen" {
			_, port, e := net.SplitHostPort(str)
			n, ne := strconv.Atoi(port)
			if e != nil || ne != nil || n < 1 || n > 65534 {
				return bad()
			}
		}
		if f.Key == "optimize.window" && str != "" {
			parts := strings.Split(str, "-")
			if len(parts) != 2 {
				return bad()
			}
			for _, p := range parts {
				if _, e := time.Parse("15:04", p); e != nil {
					return bad()
				}
			}
		}
	case "lines":
		a, ok := v.([]any)
		if !ok || len(a) > 200 {
			return bad()
		}
		for _, x := range a {
			str, ok := x.(string)
			if !ok || len(str) > 255 || strings.ContainsAny(str, "/\\") {
				return bad()
			}
		}
	case "roots":
		a, ok := v.([]any)
		if !ok || len(a) > 100 {
			return bad()
		}
		seen := map[string]bool{}
		for _, x := range a {
			m, ok := x.(map[string]any)
			if !ok || len(m) > 2 {
				return bad()
			}
			for key := range m {
				if key != "path" && key != "label" {
					return bad()
				}
			}
			p, ok := m["path"].(string)
			if !ok || !filepath.IsAbs(p) || len(p) > 4096 {
				return bad()
			}
			if l, ok := m["label"]; ok {
				str, ok := l.(string)
				if !ok || len(str) > 128 {
					return bad()
				}
			}
			key := strings.ToLower(filepath.Clean(p))
			if seen[key] {
				return fmt.Errorf("媒体目录重复：%s", p)
			}
			seen[key] = true
		}
	default:
		return bad()
	}
	return nil
}
func (s *Server) remoteSaveConfig(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Revision string         `json:"revision"`
		Changes  map[string]any `json:"changes"`
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128<<10))
	dec.DisallowUnknownFields()
	if dec.Decode(&in) != nil || len(in.Changes) == 0 {
		writeErr(w, 400, "bad.request", "没有可保存的修改")
		return
	}
	s.configMu.Lock()
	defer s.configMu.Unlock()
	doc, b, err := s.configDocument()
	if err != nil {
		writeErr(w, 500, "config.read", err.Error())
		return
	}
	if in.Revision != revision(b) {
		writeErr(w, 409, "config.changed", "配置已被其他设备修改，请刷新后重试")
		return
	}
	for key, v := range in.Changes {
		var field *configField
		for i := range remoteFields {
			if remoteFields[i].Key == key {
				field = &remoteFields[i]
				break
			}
		}
		if field == nil {
			writeErr(w, 400, "config.field", "不支持修改此参数："+key)
			return
		}
		if err := validateConfigValue(*field, v); err != nil {
			writeErr(w, 400, "config.value", err.Error())
			return
		}
		setField(doc, key, v)
	}
	// A dedicated revision response is enough; never return the unfiltered document.
	next, err := json.MarshalIndent(doc, "", "  ")
	if err == nil {
		err = os.MkdirAll(filepath.Dir(s.Cfg.Path), 0700)
	}
	if err == nil {
		err = os.WriteFile(s.Cfg.Path+".mobile.tmp", next, 0600)
	}
	if err == nil {
		err = os.Rename(s.Cfg.Path+".mobile.tmp", s.Cfg.Path)
	}
	if err != nil {
		_ = os.Remove(s.Cfg.Path + ".mobile.tmp")
		writeErr(w, 500, "config.save", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"revision": revision(next), "restartRequired": true, "message": "配置已保存，重启服务后生效"})
}
func (s *Server) remoteRestart(w http.ResponseWriter, r *http.Request) {
	if s.Restart == nil {
		writeErr(w, 503, "restart.unavailable", "此服务不支持远程重启")
		return
	}
	if err := s.Restart(); err != nil {
		writeErr(w, 500, "restart.failed", err.Error())
		return
	}
	w.WriteHeader(http.StatusAccepted)
}
