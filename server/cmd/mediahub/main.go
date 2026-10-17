// mediahub:局域网媒体网关。用法:
//
//	mediahub serve [-config path]          启动服务(首次启动会先扫描)
//	mediahub scan  [-config path]          只扫描并补全元数据,然后退出
//	mediahub user add <name>               创建账号(密码从标准输入读取一行)
//	mediahub user list                     以 JSON 列出账号
//	mediahub user delete <name>            删除账号
//	mediahub user passwd <name>            重置密码
//	mediahub version
package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/tls"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"mediahub/internal/api"
	"mediahub/internal/auth"
	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/governor"
	"mediahub/internal/index"
	"mediahub/internal/jellyfin"
	"mediahub/internal/logx"
	"mediahub/internal/proc"
	"mediahub/internal/store"
	"mediahub/internal/tlsx"
	"mediahub/internal/tunnel"
	"mediahub/internal/update"
)

const version = "3.2.0"

func projectRoot() string {
	if r := os.Getenv("MEDIAHUB_ROOT"); r != "" {
		return r
	}
	// 没有环境变量时,从 exe 所在目录向上找含 runtime 子目录的那一层(<根>\runtime\bin\mediahub.exe)
	exe, _ := os.Executable()
	for d := filepath.Dir(exe); ; d = filepath.Dir(d) {
		if st, err := os.Stat(filepath.Join(d, "runtime")); err == nil && st.IsDir() {
			return d
		}
		if parent := filepath.Dir(d); parent == d {
			break
		}
	}
	return filepath.Dir(exe)
}

func main() {
	defer func() {
		if rec := recover(); rec != nil {
			if lm := logx.Default(); lm != nil {
				lm.Log.Error("主流程崩溃", "panic", fmt.Sprint(rec))
				p := lm.WriteCrash("main", rec, debug.Stack())
				fmt.Fprintln(os.Stderr, "MediaHub 崩溃,报告已保存:", p)
			} else {
				fmt.Fprintf(os.Stderr, "MediaHub 崩溃: %v\n%s\n", rec, debug.Stack())
			}
			os.Exit(3)
		}
	}()
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "version":
		fmt.Println("mediahub", version)
	case "serve":
		os.Exit(run(os.Args[2:], true))
	case "scan":
		os.Exit(run(os.Args[2:], false))
	case "user":
		os.Exit(userCmd(os.Args[2:]))
	default:
		usage()
		os.Exit(2)
	}
}

// restoreRoots:配置里一个根目录都没有(更新 / 换了程序目录后配置文件丢了),就按数据库里上次使用的恢复,
// 并写回配置文件 —— 不用每次更新都重新添加媒体根目录。
// 优先恢复上次还处于启用状态的;没有的话(例如已经用空配置启动过一次,全被停用了),配置文件缺失时恢复所有登记过且路径还在的。
func restoreRoots(db *sql.DB, cfg *config.Config, log *slog.Logger) {
	if len(cfg.Roots) > 0 {
		return
	}
	query := func(q string) []config.Root {
		var out []config.Root
		rows, err := db.Query(q)
		if err != nil {
			return nil
		}
		defer rows.Close()
		for rows.Next() {
			var p, l string
			if rows.Scan(&p, &l) != nil {
				continue
			}
			if _, err := os.Stat(p); err != nil {
				continue
			}
			out = append(out, config.Root{Path: p, Label: l})
		}
		return out
	}
	got := query(`SELECT path, label FROM roots WHERE enabled=1 ORDER BY id`)
	if len(got) == 0 && cfg.RootsUnset {
		got = query(`SELECT path, label FROM roots ORDER BY id`)
	}
	if len(got) == 0 {
		return
	}
	cfg.Roots = got
	if cfg.Path != "" {
		if err := config.SaveRoots(cfg.Path, got); err != nil {
			log.Warn("恢复的根目录写回配置文件失败", "err", err)
		}
	}
	paths := make([]string, len(got))
	for i, r := range got {
		paths[i] = r.Path
	}
	log.Info("配置里没有根目录(多半是更新后配置丢了),已按上次使用的恢复", "roots", paths)
}

func usage() {
	fmt.Fprintln(os.Stderr, "用法: mediahub serve|scan [-config path] | user add|passwd <name> | version")
}

func loadCfg(fs *flag.FlagSet, args []string) (*config.Config, *slog.Logger, error) {
	pr := projectRoot()
	cfgPath := fs.String("config", filepath.Join(pr, "runtime", "mediahub", "config.json"), "配置文件")
	debugFlag := fs.Bool("debug", false, "输出调试日志")
	if err := fs.Parse(args); err != nil {
		return nil, nil, err
	}
	cfg, err := config.Load(*cfgPath, pr)
	if err != nil {
		return nil, nil, err
	}
	level := cfg.Log.Level
	if *debugFlag {
		level = "debug"
	}
	// 日志与崩溃捕获:写到 <数据目录>\logs,自动轮转;panic 会被记录并写崩溃报告
	lm, err := logx.Setup(cfg.DataDir, version, level, os.Stderr)
	if err != nil {
		return nil, nil, err
	}
	return cfg, lm.Log, nil
}

func run(args []string, serve bool) int {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	cfg, log, err := loadCfg(fs, args)
	if err != nil {
		fmt.Fprintln(os.Stderr, "配置错误:", err)
		return 1
	}
	// 同一个数据目录只允许一个服务进程:以前重复启动(更新重启的间隙、手动又点了一次)会让两个进程抢管理端口和数据库,
	// 日志里出现过"管理端口被占用"和"database is locked"。新进程等旧进程退出最多 20 秒,等不到就退出。
	if serve {
		var release func()
		var lerr error
		for i := 0; i < 40; i++ {
			if release, lerr = proc.LockFile(filepath.Join(cfg.DataDir, "mediahub.lock")); lerr == nil {
				break
			}
			time.Sleep(500 * time.Millisecond)
		}
		if lerr != nil {
			log.Error("已经有另一个 MediaHub 服务在使用这个数据目录,本次启动退出(先在管理程序里停止旧的服务)", "dir", cfg.DataDir, "err", lerr)
			return 3
		}
		defer release()
	}
	// 管理程序登记了"用某份备份还原":在打开数据库之前换文件(现在的库留底为 hub.sqlite.before-restore)
	if serve {
		if name, err := store.ApplyPendingRestore(cfg.DataDir); err != nil {
			log.Error("数据库还原失败,继续使用现有的库", "err", err)
		} else if name != "" {
			log.Warn("数据库已用备份还原", "backup", name)
		}
	}
	db, err := store.OpenMigrated(filepath.Join(cfg.DataDir, "hub.sqlite"))
	if err != nil {
		log.Error("打开数据库失败", "err", err)
		return 1
	}
	defer db.Close()

	restoreRoots(db, cfg, log)
	ix := index.New(db, cfg, log)
	// 调度:有客户端连着时给传输和播放让路(后台扫描 / 补全 / 重复检测 / 封面预生成降速,压缩暂停),没有客户端时全速(见 internal/governor)
	gov := governor.New(governor.Config{
		Enabled: cfg.Priority.Enabled, ActiveWindow: time.Duration(cfg.Priority.ActiveWindowSec) * time.Second, PauseCompress: cfg.Priority.PauseCompress,
	})
	proc.LowPriority.Store(cfg.Priority.Enabled && cfg.Priority.LowerChild)
	ix.Throttle = gov.Yield
	roots, err := ix.SyncRoots()
	if err != nil {
		log.Error("登记根目录失败", "err", err)
		return 1
	}
	if len(roots) == 0 {
		log.Error("没有可用的根目录,请在 config.json 的 roots 里配置")
		return 1
	}
	cm := cache.New(db, log, cfg.Cache.PerDriveQuotaGB, cfg.Cache.MinFreeMarginGB, cfg.Cache.FallbackDir)
	for _, r := range roots {
		rc, err := cm.Init(r.ID, r.Path, r.Label)
		if err != nil {
			log.Error("初始化缓存失败", "root", r.Path, "err", err)
			return 1
		}
		log.Info("缓存", "root", r.Path, "mode", rc.Mode, "dir", rc.Dir)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	// 后台索引:每个根一个 goroutine,启动先扫一次,之后每 24 小时校对一次;/admin/rescan 可立即触发
	var wg sync.WaitGroup
	triggers := map[int64]chan struct{}{}
	for _, r := range roots {
		triggers[r.ID] = make(chan struct{}, 1)
	}
	poster := api.NewPoster(db, cfg, cm)
	poster.Throttle = gov.Yield
	// Jellyfin:配置了密钥才启用。用它已生成的封面(posterSource=auto),并为以后的转码建立路径映射
	var jf *jellyfin.Client
	if cfg.Jellyfin.URL != "" && cfg.Jellyfin.APIKey != "" {
		jf = jellyfin.New(cfg.Jellyfin.URL, cfg.Jellyfin.APIKey)
		poster.JF = jf
	}
	syncJF := func() {
		if jf == nil {
			return
		}
		cctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
		defer cancel()
		if !jf.Ping(cctx) {
			log.Debug("Jellyfin 不在线,跳过映射同步")
			return
		}
		if n, err := jellyfin.Sync(cctx, db, jf); err != nil {
			log.Warn("Jellyfin 映射同步失败", "err", err)
		} else {
			log.Info("Jellyfin 映射同步完成", "matchedVideos", n)
		}
	}
	// 转码引擎的一次性准备:把硬件加速设置同步给 Jellyfin、为每个媒体根目录建库(失败只记日志,不影响浏览)
	if jf != nil {
		logx.Go("Jellyfin 初始化", func() {
			cctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
			defer cancel()
			if !jf.Ping(cctx) {
				log.Warn("Jellyfin 不在线,转码功能暂不可用(启动 Jellyfin 后重启服务即可)")
				return
			}
			if err := jf.ApplyEncoding(cctx, cfg.Video.HWAccel); err != nil {
				log.Warn("同步硬件加速设置到 Jellyfin 失败", "err", err)
			}
			var paths []string
			for _, r := range roots {
				paths = append(paths, r.Path)
			}
			if n, err := jf.EnsureLibraries(cctx, paths, log); err != nil {
				log.Warn("检查 Jellyfin 媒体库失败", "err", err)
			} else if n > 0 {
				time.AfterFunc(2*time.Minute, func() { defer logx.Recover("Jellyfin 映射同步"); syncJF() }) // 新库刚建好,Jellyfin 在后台扫描,稍后再建立视频映射
			}
		})
	}
	// first = 本次启动后的第一轮:被中断的扫描续扫,近期已完整扫描过的根目录直接跳过;之后的定时 / 手动扫描总是执行
	scanOne := func(r index.Root, first bool) bool {
		defer ix.LockRoot(r.ID)() // 和增量索引互斥:同一个根目录同一时间只做一件事
		skipAge := time.Duration(cfg.Scan.SkipWithinHours) * time.Hour
		skipped := first && (r.Reused || !ix.ScanDue(r, skipAge))
		if skipped {
			if r.Reused {
				log.Info("这个文件夹之前扫描过,直接使用已有索引(需要时可在管理程序点\"重新扫描\")", "root", r.Path)
			} else {
				log.Info("近期已完整扫描过,启动时不重新扫描(需要时可在管理程序点\"重新扫描\")", "root", r.Path)
			}
		} else {
			if err := ix.ScanRoot(ctx, r); err != nil {
				if !errors.Is(err, context.Canceled) {
					log.Error("扫描失败", "root", r.Path, "err", err)
				}
				return false
			}
		}
		if err := ix.Enrich(ctx, r); err != nil && !errors.Is(err, context.Canceled) {
			log.Error("补全元数据失败", "root", r.Path, "err", err)
			return false
		}
		if jf != nil && !skipped {
			rc, c0 := context.WithTimeout(ctx, 10*time.Second)
			_ = jf.Refresh(rc) // 让 Jellyfin 也重新扫描一遍,发现新增的视频
			c0()
		}
		syncJF() // 先建立 Jellyfin 映射,这样预热时能直接用 Jellyfin 已有的封面
		if jf != nil {
			time.AfterFunc(90*time.Second, func() { defer logx.Recover("Jellyfin 映射同步"); syncJF() }) // Jellyfin 扫描是异步的,过一会儿再对一次,新视频才能马上转码
		}
		// 低优先级后台任务:为视频生成封面与 ThumbHash,滚动时列表先显示模糊预览
		if n := poster.Warm(ctx, r.ID); n > 0 {
			log.Info("封面预热完成", "root", r.Path, "videos", n)
		}
		if n := poster.WarmPhotos(ctx, r.ID); n > 0 { // 图片的模糊占位图数据(滑动时先显示色块轮廓)
			log.Info("图片占位图预热完成", "root", r.Path, "photos", n)
		}
		return true
	}
	// 升级后 / 启动后先把对话计数整理一遍,手机马上看到正确的列表(不用等扫描)
	for _, r := range roots {
		if err := ix.Finalize(r.ID); err != nil {
			log.Warn("整理对话计数失败", "root", r.Path, "err", err)
		}
	}
	// 实时增量索引:监视每个根目录的文件变化,几秒后把变化反映到索引里(第一轮扫描 / 续扫过去之后才开始)
	if serve && cfg.Scan.Watch {
		for _, r := range roots {
			rr := r
			logx.Go("目录监视 "+rr.Path, func() {
				select {
				case <-ctx.Done():
					return
				case <-time.After(90 * time.Second):
				}
				err := ix.Watch(ctx, rr, func() func() { return ix.LockRoot(rr.ID) }, index.WatchOptions{
					Full: func() { // 一下子变化太多 / 通知缓冲区溢出:改做一次整盘扫描
						select {
						case triggers[rr.ID] <- struct{}{}:
						default:
						}
					},
					After: func(changed bool) {
						if !changed {
							return
						}
						unlock := ix.LockRoot(rr.ID)
						defer unlock()
						if err := ix.Enrich(ctx, rr); err != nil && !errors.Is(err, context.Canceled) {
							log.Warn("增量索引后补全元数据失败", "root", rr.Path, "err", err)
						}
						syncJF()
						if n := poster.Warm(ctx, rr.ID); n > 0 {
							log.Debug("增量索引后封面预热", "root", rr.Path, "videos", n)
						}
						poster.WarmPhotos(ctx, rr.ID)
					},
				})
				if err != nil {
					log.Info("这个根目录的变更监视不可用(网络盘 / 权限?),只用定时扫描", "root", rr.Path, "err", err)
				} else {
					log.Info("目录变更监视已启动", "root", rr.Path)
				}
			})
		}
	}
	for _, r := range roots {
		wg.Add(1)
		go func(r index.Root) {
			defer wg.Done()
			defer logx.Recover("索引 " + r.Path)
			var tick <-chan time.Time
			if cfg.Scan.IntervalHours > 0 {
				t := time.NewTicker(time.Duration(cfg.Scan.IntervalHours) * time.Hour)
				defer t.Stop()
				tick = t.C
			}
			first := true
			fails := 0
			for {
				ok := scanOne(r, first)
				first = false
				if !serve {
					return
				}
				if !ok && ctx.Err() == nil {
					// 扫描 / 补全失败(数据库忙、磁盘读不了…):隔一会儿自动重试(扫描可续扫,不会从头来),不再停在那里等下一个定时周期
					fails++
					wait := time.Duration(min(fails, 10)) * 30 * time.Second
					log.Warn("索引失败,稍后自动重试", "root", r.Path, "after", wait.String(), "attempt", fails)
					select {
					case <-ctx.Done():
						return
					case <-time.After(wait):
					case <-triggers[r.ID]:
						ix.ResetScan(r.ID)
					}
					continue
				}
				fails = 0
				select {
				case <-ctx.Done():
					return
				case <-tick:
				case <-triggers[r.ID]:
					ix.ResetScan(r.ID) // 手动"重新扫描":丢弃被中断的记录,从头完整扫一遍
				}
			}
		}(r)
	}
	if !serve {
		wg.Wait()
		log.Info("扫描结束")
		return 0
	}

	// 本机管理密钥:桌面管理程序读取该文件后,用 X-Admin-Key 从回环地址访问管理接口
	keyBytes := make([]byte, 32)
	_, _ = rand.Read(keyBytes)
	adminKey := hex.EncodeToString(keyBytes)
	keyPath := filepath.Join(cfg.DataDir, "admin.key")
	if err := os.WriteFile(keyPath, []byte(adminKey), 0o600); err != nil {
		log.Warn("写入 admin.key 失败,桌面管理程序将无法查询状态", "err", err)
	}
	defer os.Remove(keyPath)

	srv := &api.Server{AdminKey: adminKey, StartedAt: time.Now(),
		DB: db, Cfg: cfg, Auth: auth.New(db, cfg.Auth.TokenDays), Idx: ix, Cache: cm,
		Poster: poster, Render: api.NewRenderer(db, cfg, cm), HLS: api.NewHLS(cfg, log), JF: jf, Log: log, Version: version, LoginDelay: 0, LoginLock: time.Duration(cfg.Auth.LoginLockSec) * time.Second, Gov: gov,
		Rescan: func(id int64) {
			for rid, ch := range triggers {
				if id == 0 || id == rid {
					select {
					case ch <- struct{}{}:
					default:
					}
				}
			}
		},
	}
	// 自动更新:每隔 10 分钟(可配置)检查 GitHub Releases,有新版本就下载、替换并重启服务(只有便携版布局才会自动安装)
	srv.Update = update.New(update.Options{
		Version: version, Repo: cfg.Update.Repo, Interval: time.Duration(cfg.Update.IntervalMinutes) * time.Minute, Mirror: cfg.Update.Mirror, Mirrors: cfg.Update.Mirrors,
		Root: projectRoot(), ConfigPath: cfg.Path, Log: log, Exit: stop,
	}, cfg.Update.Enabled)
	if cfg.Update.Enabled {
		logx.Go("自动更新", func() { srv.Update.Run(ctx) })
	}
	srv.WarmRandom(ctx)
	srv.Render.Gov = gov // 播放 / 传输期间图片转换最多 2 路同时
	srv.StartOptimizeWorker(ctx) // 存储优化:视频压缩队列(没有任务时什么都不做)
	srv.StartClients(ctx)        // 客户端连接追踪:速度采样、过期清理
	go dbMaintenance(ctx, db, cfg.DataDir, log) // 数据库:PRAGMA optimize + 每日自动备份
	// 内网穿透:托管 frpc / 自定义穿透客户端(配置了才启动),掉线自动重连,定期探测外网地址
	if cfg.Tunnel.Enabled {
		port := 8480
		if i := strings.LastIndex(cfg.Listen, ":"); i >= 0 {
			fmt.Sscanf(cfg.Listen[i+1:], "%d", &port)
		}
		srv.Tunnel = tunnel.New(cfg.Tunnel, port, cfg.TLS.Enabled, cfg.DataDir, log)
		srv.Tunnel.KillStale()
		srv.Tunnel.Run(ctx)
	}
	var certFile, keyFile string
	if cfg.TLS.Enabled {
		var fp string
		certFile, keyFile, fp, err = tlsx.Ensure(cfg.DataDir)
		if err != nil {
			log.Error("准备 TLS 证书失败", "err", err)
			return 1
		}
		srv.TLSFingerprint = fp
	}
	handler := srv.Handler()
	hs := &http.Server{Addr: cfg.Listen, Handler: handler, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 5 * time.Minute}
	// HTTP/2 保活:手机切换网络 / 云转发掉线后,服务端这边的连接不会收到任何通知(没有 RST),正在发送的大文件会一直占着协程和磁盘读取直到 TCP 超时(很久)。
	// 每 20 秒发一次 PING,15 秒没有回应就断开,资源马上释放。
	hs.HTTP2 = &http.HTTP2Config{SendPingTimeout: 20 * time.Second, PingTimeout: 15 * time.Second}
	var adminHS *http.Server
	if cfg.TLS.Enabled {
		hs.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
		// 桌面管理程序走仅本机的明文端口(回环地址,不出网卡),不必处理自签名证书
		ln, err := net.Listen("tcp", cfg.AdminAddr())
		if err != nil {
			log.Error("本机管理端口无法监听(被占用?可在 config.json 用 adminListen 改)", "addr", cfg.AdminAddr(), "err", err)
			return 1
		}
		adminHS = &http.Server{Handler: handler, ReadHeaderTimeout: 10 * time.Second}
		logx.Go("本机管理端口", func() { _ = adminHS.Serve(ln) })
	}
	go func() {
		<-ctx.Done()
		sc, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = hs.Shutdown(sc)
		if adminHS != nil {
			_ = adminHS.Shutdown(sc)
		}
	}()
	log.Info("MediaHub 启动", "listen", cfg.Listen, "version", version, "roots", len(roots), "https", cfg.TLS.Enabled,
		"fingerprint", srv.TLSFingerprint, "admin", map[bool]string{true: cfg.AdminAddr(), false: ""}[cfg.TLS.Enabled])
	var serveErr error
	if cfg.TLS.Enabled {
		serveErr = hs.ListenAndServeTLS(certFile, keyFile)
	} else {
		serveErr = hs.ListenAndServe()
	}
	if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
		log.Error("HTTP 服务退出", "err", serveErr)
		return 1
	}
	wg.Wait()
	return 0
}

func userCmd(args []string) int {
	if len(args) < 1 || (args[0] != "add" && args[0] != "passwd" && args[0] != "list" && args[0] != "delete") {
		usage()
		return 2
	}
	if args[0] != "list" && len(args) < 2 {
		usage()
		return 2
	}
	rest := args[1:]
	if args[0] != "list" {
		rest = args[2:]
	}
	fs := flag.NewFlagSet("user", flag.ExitOnError)
	cfg, _, err := loadCfg(fs, rest)
	if err != nil {
		fmt.Fprintln(os.Stderr, "配置错误:", err)
		return 1
	}
	db, err := store.OpenMigrated(filepath.Join(cfg.DataDir, "hub.sqlite"))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer db.Close()
	a := auth.New(db, cfg.Auth.TokenDays)
	switch args[0] {
	case "list": // 供桌面管理程序读取:输出 JSON
		users, err := a.ListUsers()
		if err != nil {
			fmt.Fprintln(os.Stderr, "失败:", err)
			return 1
		}
		_ = json.NewEncoder(os.Stdout).Encode(users)
		return 0
	case "delete":
		if err := a.DeleteUser(args[1]); err != nil {
			fmt.Fprintln(os.Stderr, "失败:", err)
			return 1
		}
		return 0
	}
	fmt.Fprint(os.Stderr, "密码: ")
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	pw := strings.TrimPrefix(strings.TrimRight(line, string([]rune{13, 10})), string(rune(0xFEFF)))
	if args[0] == "add" {
		err = a.CreateUser(args[1], pw)
	} else {
		err = a.SetPassword(args[1], pw)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "失败:", err)
		return 1
	}
	fmt.Fprintln(os.Stderr, "完成")
	return 0
}
