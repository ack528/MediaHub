package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// 手机端安装包中转(服务端 3.3.0):服务端检查更新时顺便把最新发布里的 LocalBrowse-<版本>.apk 下载到数据目录(校验 SHA-256),
// 手机「检查更新」先问自己的服务器,有就直接从服务器下载 —— 走局域网 / 内网穿透,几秒钟就完;
// 不用每台手机各自去连 GitHub(手机网络下 GitHub 常常连不上,镜像又慢又不稳定)。
// 电脑这边下载慢也没关系:是后台下载,手机来问的时候多半早就下好了。

// AppStatus 是手机端安装包的缓存状态。
type AppStatus struct {
	Latest    string    `json:"latest"`            // 最新发布里的手机端版本;空 = 还不知道
	Size      int64     `json:"size"`              // 已缓存的安装包大小
	Sha256    string    `json:"sha256,omitempty"`  // 已缓存的安装包的 SHA-256(和发布里的 SHA256SUMS.txt 一致)
	Ready     bool      `json:"ready"`             // 已下载并校验,可以从服务器下载
	State     string    `json:"state"`             // idle | checking | downloading | ready | error | disabled
	Progress  int       `json:"progress"`          // 下载进度 0 ~ 100
	Message   string    `json:"message,omitempty"` //
	Notes     string    `json:"notes,omitempty"`   // 发布说明(GitHub API 能用时才有)
	CheckedAt time.Time `json:"checkedAt"`         // 上次检查 GitHub 的时间
}

var apkName = regexp.MustCompile(`^LocalBrowse-(\d+(?:\.\d+){1,3})\.apk$`)

// AppStatus 返回手机端安装包的缓存状态。
func (u *Updater) AppStatus() AppStatus {
	u.appMu.Lock()
	defer u.appMu.Unlock()
	return u.app
}

// AppFile 返回已缓存的安装包路径和版本;还没下好返回空。
func (u *Updater) AppFile() (path, ver string) {
	u.appMu.Lock()
	defer u.appMu.Unlock()
	if !u.app.Ready {
		return "", ""
	}
	return u.appPath, u.app.Latest
}

func (u *Updater) setApp(f func(a *AppStatus)) {
	u.appMu.Lock()
	f(&u.app)
	u.appMu.Unlock()
}

// AppCheck 给手机的「检查更新」用:上次检查超过 maxAge 就现在检查一次(最多等到 ctx 结束),然后返回状态。
// 正在检查(定时检查刚好在跑)就等它跑完。
func (u *Updater) AppCheck(ctx context.Context, maxAge time.Duration) AppStatus {
	if u.o.AppDir == "" {
		return u.AppStatus()
	}
	if st := u.AppStatus(); !st.CheckedAt.IsZero() && time.Since(st.CheckedAt) < maxAge {
		return st
	}
	done := make(chan struct{})
	go func() {
		// 不跟着请求的 ctx 走:手机等不及断开了,检查和安装包下载也继续做完,下次来就有了
		bg, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		u.Check(bg, false)
		close(done)
	}()
	t := time.NewTicker(200 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-done:
			done = nil // 已关闭的通道每次都能读到:置空,免得空转
			if !u.isBusy() {
				return u.AppStatus()
			}
		case <-ctx.Done():
			return u.AppStatus()
		case <-t.C:
		}
		if st := u.AppStatus(); !st.CheckedAt.IsZero() && time.Since(st.CheckedAt) < maxAge && !u.isBusy() {
			return st
		}
	}
}

func (u *Updater) isBusy() bool {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.busy
}

// loadAppCache 启动时读回上次缓存好的安装包(数据目录里的 LocalBrowse-<版本>.apk + 同名 .sha256),重启服务后不用重新下载。
func (u *Updater) loadAppCache() {
	if u.o.AppDir == "" {
		u.app.State = "disabled"
		return
	}
	u.app.State = "idle"
	ents, _ := os.ReadDir(u.o.AppDir)
	best := ""
	for _, e := range ents {
		if m := apkName.FindStringSubmatch(e.Name()); m != nil && (best == "" || CompareVersions(m[1], best) > 0) {
			best = m[1]
		}
	}
	if best == "" {
		return
	}
	p := filepath.Join(u.o.AppDir, "LocalBrowse-"+best+".apk")
	sum, err := os.ReadFile(p + ".sha256")
	fi, serr := os.Stat(p)
	if err != nil || serr != nil {
		return
	}
	if got, herr := fileSHA256(p); herr != nil || !strings.EqualFold(got, strings.TrimSpace(string(sum))) {
		u.o.Log.Warn("缓存的手机端安装包校验不对,丢弃", "file", p)
		_ = os.Remove(p)
		return
	}
	u.app.Latest, u.app.Sha256, u.app.Size, u.app.Ready, u.app.State = best, strings.TrimSpace(string(sum)), fi.Size(), true, "ready"
	u.appPath = p
}

// noteApp 每次检查到最新发布时调用:记下手机端版本;还没缓存就在后台下载。
func (u *Updater) noteApp(rel *release) {
	if u.o.AppDir == "" {
		return
	}
	var apkURL, sumURL, ver string
	for _, a := range rel.Assets {
		if m := apkName.FindStringSubmatch(a.Name); m != nil {
			apkURL, ver = a.URL, m[1]
		}
		if a.Name == "SHA256SUMS.txt" {
			sumURL = a.URL
		}
	}
	u.appMu.Lock()
	u.app.CheckedAt = time.Now()
	if rel.Body != "" {
		u.app.Notes = trunc(rel.Body, 2000)
	}
	if ver == "" {
		u.app.Message = "最新发布里没有手机端安装包"
		u.appMu.Unlock()
		return
	}
	cached := u.app.Ready && u.app.Latest == ver
	busy := u.appBusy
	if !cached && !busy {
		u.app.Latest, u.app.Ready, u.app.State, u.app.Progress, u.app.Message = ver, false, "downloading", 0, "正在下载 "+ver
		// These fields describe the verified file, never a previous version.
		u.app.Sha256, u.app.Size, u.appPath = "", 0, ""
		u.appBusy = true
	}
	u.appMu.Unlock()
	if cached || busy {
		return
	}
	if sumURL == "" {
		u.setApp(func(a *AppStatus) { a.State, a.Message = "error", "发布里没有 SHA256SUMS.txt,无法校验" })
		u.appMu.Lock()
		u.appBusy = false
		u.appMu.Unlock()
		return
	}
	go u.cacheApp(ver, apkURL, sumURL)
}

// cacheApp 后台下载手机端安装包到数据目录,校验通过后换上,删掉旧版本。
func (u *Updater) cacheApp(ver, apkURL, sumURL string) {
	defer func() { u.appMu.Lock(); u.appBusy = false; u.appMu.Unlock() }()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	fail := func(err error) {
		u.o.Log.Warn("手机端安装包下载失败(手机仍可以自己从 GitHub 下载)", "version", ver, "err", err)
		u.setApp(func(a *AppStatus) { a.State, a.Message = "error", "下载失败:"+err.Error() })
	}
	name := "LocalBrowse-" + ver + ".apk"
	want, err := u.wantSum(ctx, sumURL, name)
	if err != nil {
		fail(fmt.Errorf("取校验和失败:%w", err))
		return
	}
	if err := os.MkdirAll(u.o.AppDir, 0o755); err != nil {
		fail(err)
		return
	}
	final := filepath.Join(u.o.AppDir, name)
	tmp := final + ".part"
	urls := candidates(apkURL, u.mirrorList())
	first := race(ctx, u.client, urls, "MediaHub-Updater/"+u.o.Version)
	tried := map[string]bool{}
	var dlErr error
	for _, url := range append([]string{first}, urls...) {
		if tried[url] {
			continue
		}
		tried[url] = true
		u.o.Log.Info("缓存手机端安装包", "version", ver, "from", url)
		dlErr = u.fetchTo(ctx, url, tmp, want, func(p int) { u.setApp(func(a *AppStatus) { a.Progress = p }) })
		if dlErr == nil {
			break
		}
		u.o.Log.Warn("下载手机端安装包失败,换下一个地址", "from", url, "err", dlErr)
	}
	if dlErr != nil {
		_ = os.Remove(tmp)
		fail(dlErr)
		return
	}
	if err := os.Rename(tmp, final); err != nil {
		fail(err)
		return
	}
	_ = os.WriteFile(final+".sha256", []byte(want+"\n"), 0o644)
	fi, _ := os.Stat(final)
	var size int64
	if fi != nil {
		size = fi.Size()
	}
	// 只留最新的一个
	ents, _ := os.ReadDir(u.o.AppDir)
	for _, e := range ents {
		n := e.Name()
		if strings.HasPrefix(n, "LocalBrowse-") && n != name && n != name+".sha256" {
			_ = os.Remove(filepath.Join(u.o.AppDir, n))
		}
	}
	u.appMu.Lock()
	u.appPath = final
	u.app.Latest, u.app.Sha256, u.app.Size, u.app.Ready, u.app.State, u.app.Progress, u.app.Message = ver, want, size, true, "ready", 100, ""
	u.appMu.Unlock()
	u.o.Log.Info("手机端安装包已缓存,手机可以直接从服务器下载", "version", ver, "size", size)
}

// fileSHA256 计算文件的 SHA-256(十六进制小写)。
func fileSHA256(p string) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
