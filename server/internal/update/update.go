// Package update 实现服务端自动更新:每隔一段时间(默认 10 分钟)去 GitHub Releases 看有没有新版本,
// 有就自动下载(校验 SHA-256)、解压,然后交给一个独立的 PowerShell 脚本完成"停服务 → 覆盖文件 → 启动新服务",
// 新版本起不来时脚本会还原旧文件再把旧服务拉起来。
//
// 约定:发布的 Release 里有 MediaHub-<版本>-update.zip(只含 MediaHub.exe 与 runtime\bin,体积小)
// 和 SHA256SUMS.txt(每行 "<sha256>  <文件名>")。版本号取自资源文件名,和本程序的版本比较(语义化版本,数字逐段比)。
// 只有按便携版布局运行(<根>\runtime\bin\mediahub.exe 且 <根>\MediaHub.exe 存在)才会自动安装;开发环境只检查、不安装。
package update

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed apply.ps1
var applyScript string

// Options 是 Updater 的配置。
type Options struct {
	Version    string        // 当前版本
	Repo       string        // owner/name
	Interval   time.Duration // 检查间隔
	Mirror     string        // 下载地址前缀(给访问不了 GitHub 的网络用,例如 https://ghfast.top/),留空直连
	Root       string        // 便携版根目录
	ConfigPath string        // 当前使用的配置文件(重启服务时原样带上)
	Log        *slog.Logger
	// Exit 在安装脚本启动之后调用:让服务优雅退出(脚本等进程结束后覆盖文件)。
	Exit func()
}

// Status 是给管理程序看的状态。
type Status struct {
	Current     string    `json:"current"`
	Latest      string    `json:"latest"`
	Tag         string    `json:"tag,omitempty"`
	Notes       string    `json:"notes,omitempty"`
	State       string    `json:"state"` // idle | checking | uptodate | available | downloading | installing | error | disabled
	Message     string    `json:"message,omitempty"`
	Progress    int       `json:"progress"` // 下载进度 0 ~ 100
	CheckedAt   time.Time `json:"checkedAt"`
	Installable bool      `json:"installable"` // 按便携版布局运行,可以自动安装
	Auto        bool      `json:"auto"`
}

type Updater struct {
	o      Options
	client *http.Client
	mu     sync.Mutex
	st     Status
	busy   bool
	failed map[string]time.Time // 版本 → 上次失败时间(1 小时内不再自动重试)
	auto   bool
}

func New(o Options, auto bool) *Updater {
	if o.Interval <= 0 {
		o.Interval = 10 * time.Minute
	}
	if o.Repo == "" {
		o.Repo = "ack528/MediaHub"
	}
	u := &Updater{o: o, auto: auto, failed: map[string]time.Time{}, client: &http.Client{Timeout: 30 * time.Second}}
	u.st = Status{Current: o.Version, State: "idle", Installable: u.installable(), Auto: auto}
	return u
}

// installable:便携版布局才允许原地覆盖文件。
func (u *Updater) installable() bool {
	if u.o.Root == "" {
		return false
	}
	if _, err := os.Stat(filepath.Join(u.o.Root, "MediaHub.exe")); err != nil {
		return false
	}
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	want := filepath.Join(u.o.Root, "runtime", "bin", "mediahub.exe")
	return strings.EqualFold(filepath.Clean(exe), filepath.Clean(want))
}

func (u *Updater) Status() Status {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.st
}

func (u *Updater) set(f func(s *Status)) {
	u.mu.Lock()
	f(&u.st)
	u.mu.Unlock()
}

// Run 循环检查,直到 ctx 结束。启动后先等一小会儿(让服务先完成启动和索引),然后每隔 Interval 检查一次。
func (u *Updater) Run(ctx context.Context) {
	select {
	case <-ctx.Done():
		return
	case <-time.After(45 * time.Second):
	}
	t := time.NewTicker(u.o.Interval)
	defer t.Stop()
	for {
		u.Check(ctx, u.auto)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

type release struct {
	Tag    string `json:"tag_name"`
	Body   string `json:"body"`
	Assets []struct {
		Name string `json:"name"`
		URL  string `json:"browser_download_url"`
		Size int64  `json:"size"`
	} `json:"assets"`
}

var updateName = regexp.MustCompile(`^MediaHub-(\d+(?:\.\d+){1,3})-update\.zip$`)

// Check 检查一次更新;install = true 且发现新版本、条件允许时自动下载并安装。
func (u *Updater) Check(ctx context.Context, install bool) {
	u.mu.Lock()
	if u.busy {
		u.mu.Unlock()
		return
	}
	u.busy = true
	u.mu.Unlock()
	defer func() { u.mu.Lock(); u.busy = false; u.mu.Unlock() }()

	u.set(func(s *Status) { s.State = "checking"; s.Message = "" })
	rel, err := u.fetchLatest(ctx)
	if err != nil {
		u.o.Log.Warn("检查更新失败", "err", err)
		u.set(func(s *Status) {
			s.State = "error"
			s.Message = "检查更新失败:" + err.Error()
			s.CheckedAt = time.Now()
		})
		return
	}
	var zipURL, sumURL, ver string
	for _, a := range rel.Assets {
		if m := updateName.FindStringSubmatch(a.Name); m != nil {
			zipURL, ver = a.URL, m[1]
		}
		if a.Name == "SHA256SUMS.txt" {
			sumURL = a.URL
		}
	}
	u.set(func(s *Status) { s.CheckedAt = time.Now(); s.Tag = rel.Tag; s.Notes = trunc(rel.Body, 2000) })
	if zipURL == "" || CompareVersions(ver, u.o.Version) <= 0 {
		u.set(func(s *Status) {
			s.State = "uptodate"
			s.Latest = firstNonEmpty(ver, s.Current)
			s.Message = "已是最新版本"
		})
		return
	}
	u.set(func(s *Status) { s.State = "available"; s.Latest = ver; s.Message = "发现新版本 " + ver })
	u.o.Log.Info("发现新版本", "current", u.o.Version, "latest", ver, "tag", rel.Tag)
	if !install {
		return
	}
	if !u.installable() {
		u.set(func(s *Status) {
			s.Message = "发现新版本 " + ver + "(当前不是便携版布局,请手动更新)"
		})
		return
	}
	if t, ok := u.failed[ver]; ok && time.Since(t) < time.Hour && ctx.Err() == nil {
		return // 这个版本刚失败过,隔一小时再试
	}
	if sumURL == "" {
		u.fail(ver, errors.New("发布里没有 SHA256SUMS.txt,无法校验,已放弃"))
		return
	}
	if err := u.download(ctx, ver, zipURL, sumURL); err != nil {
		u.fail(ver, err)
	}
}

func (u *Updater) fail(ver string, err error) {
	u.o.Log.Error("自动更新失败", "version", ver, "err", err)
	u.failed[ver] = time.Now()
	u.set(func(s *Status) { s.State = "error"; s.Message = "更新失败:" + err.Error() })
}

func (u *Updater) get(ctx context.Context, url string, accept string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "MediaHub-Updater/"+u.o.Version)
	if accept != "" {
		req.Header.Set("Accept", accept)
	}
	resp, err := u.client.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != 200 {
		resp.Body.Close()
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return resp, nil
}

func (u *Updater) withMirror(url string) string {
	if u.o.Mirror == "" {
		return url
	}
	return strings.TrimRight(u.o.Mirror, "/") + "/" + url
}

// fetchViaRedirect 不走 GitHub API(匿名每小时只有 60 次,共用出口 IP / 代理时很容易被限流返回 403):
// 访问 github.com/<repo>/releases/latest 会 302 到 .../releases/tag/<tag>,取到 tag;
// 再下载这个 tag 下的 SHA256SUMS.txt,里面每行 "<sha256>  <文件名>" 就是这次发布的全部资源。没有更新说明(body 为空)。
func (u *Updater) fetchViaRedirect(ctx context.Context, prefix string) (*release, error) {
	noRedirect := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	req, _ := http.NewRequestWithContext(ctx, "GET", prefix+"https://github.com/"+u.o.Repo+"/releases/latest", nil)
	req.Header.Set("User-Agent", "MediaHub-Updater/"+u.o.Version)
	resp, err := noRedirect.Do(req)
	if err != nil {
		return nil, err
	}
	resp.Body.Close()
	loc := resp.Header.Get("Location")
	i := strings.LastIndex(loc, "/releases/tag/")
	if resp.StatusCode/100 != 3 || i < 0 {
		return nil, fmt.Errorf("没有取到最新发布的标签(HTTP %d)", resp.StatusCode)
	}
	tag := loc[i+len("/releases/tag/"):]
	base := "https://github.com/" + u.o.Repo + "/releases/download/" + tag + "/"
	sresp, err := u.get(ctx, prefix+base+"SHA256SUMS.txt", "")
	if err != nil {
		return nil, err
	}
	b, _ := io.ReadAll(io.LimitReader(sresp.Body, 1<<20))
	sresp.Body.Close()
	r := &release{Tag: tag}
	r.Assets = append(r.Assets, struct {
		Name string `json:"name"`
		URL  string `json:"browser_download_url"`
		Size int64  `json:"size"`
	}{Name: "SHA256SUMS.txt", URL: base + "SHA256SUMS.txt"})
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) >= 2 {
			name := strings.TrimPrefix(f[len(f)-1], "*")
			r.Assets = append(r.Assets, struct {
				Name string `json:"name"`
				URL  string `json:"browser_download_url"`
				Size int64  `json:"size"`
			}{Name: name, URL: base + name})
		}
	}
	return r, nil
}

func (u *Updater) fetchLatest(ctx context.Context) (*release, error) {
	if r, err := u.fetchLatestAPI(ctx); err == nil {
		return r, nil
	} else {
		u.o.Log.Info("GitHub API 不可用(多半是匿名限流),改用 releases/latest 跳转 + SHA256SUMS.txt", "err", err)
	}
	r, err := u.fetchViaRedirect(ctx, "")
	if err != nil && u.o.Mirror != "" {
		r, err = u.fetchViaRedirect(ctx, strings.TrimRight(u.o.Mirror, "/")+"/")
	}
	return r, err
}

func (u *Updater) fetchLatestAPI(ctx context.Context) (*release, error) {
	api := "https://api.github.com/repos/" + u.o.Repo + "/releases/latest"
	var lastErr error
	for _, url := range []string{api, u.withMirror(api)} {
		resp, err := u.get(ctx, url, "application/vnd.github+json")
		if err != nil {
			lastErr = err
			if u.o.Mirror == "" {
				break
			}
			continue
		}
		var r release
		err = json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&r)
		resp.Body.Close()
		if err != nil {
			lastErr = err
			continue
		}
		return &r, nil
	}
	return nil, lastErr
}

func (u *Updater) wantSum(ctx context.Context, sumURL, name string) (string, error) {
	var lastErr error
	for _, url := range []string{sumURL, u.withMirror(sumURL)} {
		resp, err := u.get(ctx, url, "")
		if err != nil {
			lastErr = err
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		for _, line := range strings.Split(string(b), "\n") {
			f := strings.Fields(line)
			if len(f) >= 2 && strings.EqualFold(strings.TrimPrefix(f[len(f)-1], "*"), name) {
				return strings.ToLower(f[0]), nil
			}
		}
		lastErr = fmt.Errorf("SHA256SUMS.txt 里没有 %s", name)
	}
	return "", lastErr
}

// download 下载 + 校验 + 解压 + 启动安装脚本。
func (u *Updater) download(ctx context.Context, ver, zipURL, sumURL string) error {
	name := fmt.Sprintf("MediaHub-%s-update.zip", ver)
	want, err := u.wantSum(ctx, sumURL, name)
	if err != nil {
		return fmt.Errorf("取校验和失败:%w", err)
	}
	dir := filepath.Join(u.o.Root, "update")
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	zipPath := filepath.Join(dir, name)
	u.set(func(s *Status) { s.State = "downloading"; s.Progress = 0; s.Message = "正在下载 " + ver })
	var dlErr error
	for _, url := range []string{zipURL, u.withMirror(zipURL)} {
		dlErr = u.fetchFile(ctx, url, zipPath, want)
		if dlErr == nil || u.o.Mirror == "" {
			break
		}
	}
	if dlErr != nil {
		return dlErr
	}
	u.set(func(s *Status) { s.State = "installing"; s.Progress = 100; s.Message = "正在安装 " + ver })
	stage := filepath.Join(dir, "stage")
	if err := unzip(zipPath, stage); err != nil {
		return fmt.Errorf("解压失败:%w", err)
	}
	for _, must := range []string{filepath.Join("runtime", "bin", "mediahub.exe"), "MediaHub.exe"} {
		if _, err := os.Stat(filepath.Join(stage, must)); err != nil {
			return fmt.Errorf("更新包不完整,缺少 %s", must)
		}
	}
	return u.launchInstaller(dir, stage, ver)
}

func (u *Updater) fetchFile(ctx context.Context, url, dst, wantSum string) error {
	dctx, cancel := context.WithTimeout(ctx, 30*time.Minute)
	defer cancel()
	req, _ := http.NewRequestWithContext(dctx, "GET", url, nil)
	req.Header.Set("User-Agent", "MediaHub-Updater/"+u.o.Version)
	c := &http.Client{} // 大文件:不设整体超时(由 dctx 控制)
	resp, err := c.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("下载失败 HTTP %d", resp.StatusCode)
	}
	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	h := sha256.New()
	total := resp.ContentLength
	var done int64
	buf := make([]byte, 256<<10)
	last := time.Now()
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				f.Close()
				return werr
			}
			h.Write(buf[:n])
			done += int64(n)
			if total > 0 && time.Since(last) > 500*time.Millisecond {
				last = time.Now()
				p := int(done * 100 / total)
				u.set(func(s *Status) { s.Progress = p })
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			f.Close()
			return rerr
		}
	}
	if err := f.Close(); err != nil {
		return err
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != wantSum {
		_ = os.Remove(dst)
		return fmt.Errorf("校验和不一致(下载损坏或被篡改):期望 %s…,实际 %s…", wantSum[:8], got[:8])
	}
	return nil
}

func unzip(src, dst string) error {
	r, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer r.Close()
	root := filepath.Clean(dst) + string(os.PathSeparator)
	for _, f := range r.File {
		p := filepath.Join(dst, f.Name)
		if !strings.HasPrefix(filepath.Clean(p)+string(os.PathSeparator), root) && filepath.Clean(p) != filepath.Clean(dst) {
			return fmt.Errorf("压缩包里有越界路径:%s", f.Name)
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(p, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(p)
		if err != nil {
			rc.Close()
			return err
		}
		_, err = io.Copy(out, rc)
		rc.Close()
		if cerr := out.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// launchInstaller 写出安装脚本并以独立进程启动,然后让服务退出。
func (u *Updater) launchInstaller(dir, stage, ver string) error {
	script := filepath.Join(dir, "apply.ps1")
	if err := os.WriteFile(script, append([]byte{0xEF, 0xBB, 0xBF}, []byte(applyScript)...), 0o644); err != nil {
		return err
	}
	cmd := exec.Command("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script,
		"-ServerPid", strconv.Itoa(os.Getpid()), "-Root", u.o.Root, "-Stage", stage, "-Version", ver, "-Config", u.o.ConfigPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 | 0x00000200} // CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("启动安装脚本失败:%w", err)
	}
	u.o.Log.Info("更新包已就绪,服务即将退出并由安装脚本完成替换和重启", "version", ver)
	u.set(func(s *Status) { s.Message = "安装中,服务即将重启…" })
	go func() {
		time.Sleep(1500 * time.Millisecond) // 让管理接口先把"安装中"状态回给调用方
		if u.o.Exit != nil {
			u.o.Exit()
		}
	}()
	return nil
}

// CompareVersions 逐段按数字比较 "1.3.2" 和 "1.10":a > b 返回 1,相等 0,小于 -1。缺的段当 0。
func CompareVersions(a, b string) int {
	pa, pb := strings.Split(strings.TrimPrefix(a, "v"), "."), strings.Split(strings.TrimPrefix(b, "v"), ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			if x > y {
				return 1
			}
			return -1
		}
	}
	return 0
}

func trunc(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}
