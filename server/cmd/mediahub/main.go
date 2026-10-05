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
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"mediahub/internal/api"
	"mediahub/internal/auth"
	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/index"
	"mediahub/internal/jellyfin"
	"mediahub/internal/store"
)

const version = "0.4.0"

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

func usage() {
	fmt.Fprintln(os.Stderr, "用法: mediahub serve|scan [-config path] | user add|passwd <name> | version")
}

func loadCfg(fs *flag.FlagSet, args []string) (*config.Config, *slog.Logger, error) {
	pr := projectRoot()
	cfgPath := fs.String("config", filepath.Join(pr, "runtime", "mediahub", "config.json"), "配置文件")
	debug := fs.Bool("debug", false, "输出调试日志")
	if err := fs.Parse(args); err != nil {
		return nil, nil, err
	}
	cfg, err := config.Load(*cfgPath, pr)
	if err != nil {
		return nil, nil, err
	}
	if err := os.MkdirAll(filepath.Join(cfg.DataDir, "logs"), 0o755); err != nil {
		return nil, nil, err
	}
	lf, err := os.OpenFile(filepath.Join(cfg.DataDir, "logs", "mediahub-"+time.Now().Format("20060102")+".log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	var w io.Writer = os.Stderr
	if err == nil {
		w = io.MultiWriter(os.Stderr, lf)
	}
	lvl := slog.LevelInfo
	if *debug {
		lvl = slog.LevelDebug
	}
	return cfg, slog.New(slog.NewTextHandler(w, &slog.HandlerOptions{Level: lvl})), nil
}

func run(args []string, serve bool) int {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	cfg, log, err := loadCfg(fs, args)
	if err != nil {
		fmt.Fprintln(os.Stderr, "配置错误:", err)
		return 1
	}
	db, err := store.OpenMigrated(filepath.Join(cfg.DataDir, "hub.sqlite"))
	if err != nil {
		log.Error("打开数据库失败", "err", err)
		return 1
	}
	defer db.Close()

	ix := index.New(db, cfg, log)
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
	// first = 本次启动后的第一轮:被中断的扫描续扫,近期已完整扫描过的根目录直接跳过;之后的定时 / 手动扫描总是执行
	scanOne := func(r index.Root, first bool) {
		skipAge := time.Duration(cfg.Scan.SkipWithinHours) * time.Hour
		if first && !ix.ScanDue(r, skipAge) {
			log.Info("近期已完整扫描过,启动时不重新扫描(需要时可在管理程序点\"重新扫描\")", "root", r.Path)
		} else {
			if err := ix.ScanRoot(ctx, r); err != nil {
				if !errors.Is(err, context.Canceled) {
					log.Error("扫描失败", "root", r.Path, "err", err)
				}
				return
			}
		}
		if err := ix.Enrich(ctx, r); err != nil && !errors.Is(err, context.Canceled) {
			log.Error("补全元数据失败", "root", r.Path, "err", err)
			return
		}
		syncJF() // 先建立 Jellyfin 映射,这样预热时能直接用 Jellyfin 已有的封面
		// 低优先级后台任务:为视频生成封面与 ThumbHash,滚动时列表先显示模糊预览
		if n := poster.Warm(ctx, r.ID); n > 0 {
			log.Info("封面预热完成", "root", r.Path, "videos", n)
		}
	}
	// 升级后 / 启动后先把对话计数整理一遍,手机马上看到正确的列表(不用等扫描)
	for _, r := range roots {
		if err := ix.Finalize(r.ID); err != nil {
			log.Warn("整理对话计数失败", "root", r.Path, "err", err)
		}
	}
	for _, r := range roots {
		wg.Add(1)
		go func(r index.Root) {
			defer wg.Done()
			var tick <-chan time.Time
			if cfg.Scan.IntervalHours > 0 {
				t := time.NewTicker(time.Duration(cfg.Scan.IntervalHours) * time.Hour)
				defer t.Stop()
				tick = t.C
			}
			first := true
			for {
				scanOne(r, first)
				first = false
				if !serve {
					return
				}
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
		Poster: poster, Log: log, Version: version, LoginDelay: time.Second,
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
	hs := &http.Server{Addr: cfg.Listen, Handler: srv.Handler(), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		<-ctx.Done()
		sc, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = hs.Shutdown(sc)
	}()
	log.Info("MediaHub 启动", "listen", cfg.Listen, "version", version, "roots", len(roots))
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("HTTP 服务退出", "err", err)
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
