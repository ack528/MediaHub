package tunnel

import (
	"compress/gzip"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// 内置的 frpc:打包(tools\package.ps1)时把 tools\frp\frpc.exe 压成 embed\frpc.exe.gz 再编译进服务端,
// 第一次需要时释放到 <数据目录>\tunnel\bin\frpc.exe。开发时没有这个文件(embed 目录里只有说明),就用 tools\frp\ 里的或配置里填的路径。
//
//go:embed embed
var embeddedFS embed.FS

const embeddedName = "embed/frpc.exe.gz"

// EmbeddedAvailable 这个服务端程序里有没有内置 frpc。
func EmbeddedAvailable() bool {
	_, err := fs.Stat(embeddedFS, embeddedName)
	return err == nil
}

// extractEmbedded 把内置的 frpc 释放到 dir\frpc.exe 并返回路径。已经释放过且内容一致(按压缩包的 SHA-256 记在 frpc.exe.sum 里)就直接用,
// 服务端升级带了新版 frpc 会自动覆盖;文件正被运行中的 frpc 占用而覆盖失败时,沿用旧文件。
func extractEmbedded(fsys fs.FS, name, dir string) (string, error) {
	gz, err := fs.ReadFile(fsys, name)
	if err != nil {
		return "", errors.New("这个服务端没有内置 frpc")
	}
	sum := sha256.Sum256(gz)
	want := hex.EncodeToString(sum[:])
	exe := filepath.Join(dir, "frpc.exe")
	sumFile := filepath.Join(dir, "frpc.exe.sum")
	if st, e := os.Stat(exe); e == nil && !st.IsDir() {
		if b, e := os.ReadFile(sumFile); e == nil && strings.TrimSpace(string(b)) == want {
			return exe, nil
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	zr, err := gzip.NewReader(strings.NewReader(string(gz)))
	if err != nil {
		return "", err
	}
	defer zr.Close()
	tmp := exe + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o700)
	if err != nil {
		return "", err
	}
	_, cerr := io.Copy(f, zr)
	if e := f.Close(); cerr == nil {
		cerr = e
	}
	if cerr != nil {
		_ = os.Remove(tmp)
		return "", cerr
	}
	if err := os.Rename(tmp, exe); err != nil {
		_ = os.Remove(tmp)
		if _, se := os.Stat(exe); se == nil {
			return exe, nil // 旧的还在用(被占用无法覆盖)
		}
		return "", err
	}
	_ = os.WriteFile(sumFile, []byte(want), 0o600)
	return exe, nil
}
