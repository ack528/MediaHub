package main

import (
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"mediahub/internal/api"
	"mediahub/internal/config"
	"mediahub/internal/governor"
	"mediahub/internal/logx"
	"mediahub/internal/proc"
)

// 配置热更新:重新读取配置文件,和正在使用的配置比较,
//   - 日志级别、后台调度、内网穿透、自动更新:对应的模块自己重新加载(穿透只重启 frpc,更新只换更新模块),
//   - 浏览历史、转码上限、存储优化等"开关 / 数字"类设置:读取它们的地方每次都看当前值,直接原地改,
//   - 其余(监听地址、HTTPS、媒体目录、缓存、账号、各种路径、字符串类转码设置…)在启动时就交给了别的模块,需要重启服务。
// 日常重启(没有崩溃)现在也只要几秒钟(不再做完整的数据库检查),所以"需要重启"不再是负担;这里的目的是能不重启的就不重启。

// hotScalar 是可以直接原地修改的设置(只限开关和数字,字符串不在内:改字符串不是原子的)。
var hotScalar = map[string]bool{
	"browseHistory.enabled": true, "browseHistory.maxEntries": true, "browseHistory.retentionDays": true, "browseHistory.intervalSeconds": true,
	"video.maxTranscodes": true, "video.maxTranscodeBitrate": true, "video.allowSoftwareHEVC": true, "video.softwareFallback": true,
	"images.fallbackConvert": true,
	"optimize.autoReplace":   true, "optimize.purgeOriginal": true, "optimize.trashKeepDays": true, "optimize.maxThreads": true,
}

func newReloader(cfg *config.Config, root string, srv *api.Server, gov *governor.Governor, log *slog.Logger,
	startTunnel, startUpdater func(*config.Config)) func() (api.ReloadResult, error) {
	var mu sync.Mutex
	return func() (api.ReloadResult, error) {
		mu.Lock()
		defer mu.Unlock()
		nu, err := config.Load(cfg.Path, root)
		if err != nil {
			return api.ReloadResult{}, fmt.Errorf("读取配置失败:%w", err)
		}
		res := api.ReloadResult{Changed: config.Diff(cfg, nu), Applied: []string{}, RestartRequired: []string{}}
		var tun, upd, prio bool
		for _, k := range res.Changed {
			switch {
			case k == "log.level":
				if lm := logx.Default(); lm != nil {
					lm.SetLevel(nu.Log.Level)
				}
				cfg.Log.Level = nu.Log.Level
				res.Applied = append(res.Applied, "日志级别")
			case strings.HasPrefix(k, "tunnel."):
				tun = true
			case strings.HasPrefix(k, "update."):
				upd = true
			case strings.HasPrefix(k, "priority."):
				prio = true
			case hotScalar[k]:
				if config.CopyScalar(cfg, nu, k) {
					res.Applied = append(res.Applied, k)
				} else {
					res.RestartRequired = append(res.RestartRequired, k)
				}
			default:
				res.RestartRequired = append(res.RestartRequired, k)
			}
		}
		if prio {
			cfg.Priority = nu.Priority
			gov.Reconfigure(governor.Config{
				Enabled: nu.Priority.Enabled, ActiveWindow: time.Duration(nu.Priority.ActiveWindowSec) * time.Second, PauseCompress: nu.Priority.PauseCompress,
			})
			proc.LowPriority.Store(nu.Priority.Enabled && nu.Priority.LowerChild)
			res.Applied = append(res.Applied, "后台调度")
		}
		if upd {
			cfg.Update = nu.Update
			startUpdater(cfg)
			res.Applied = append(res.Applied, "自动更新")
		}
		if tun {
			cfg.Tunnel = nu.Tunnel
			startTunnel(cfg)
			res.Applied = append(res.Applied, "内网穿透")
		}
		log.Info("配置已重新加载", "applied", res.Applied, "restartRequired", res.RestartRequired)
		return res, nil
	}
}
