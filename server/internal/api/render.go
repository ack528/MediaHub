package api

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/proc"
)

// 手机无法直接显示(或显示不可靠)的图片格式,由服务端转成 JPEG:
//   - 相机 RAW:用 ExifTool 取内嵌的最大预览图(JpgFromRaw / PreviewImage),再按拍摄方向旋转;
//   - HEIC / HEIF / AVIF / TIFF / BMP 等:ffmpeg 解码(HEIC 的旋转信息会被正确应用);
//   - JPEG XL:ffmpeg 没有解码器,用 libvips(可选工具,没有就不支持)。
var rawExts = map[string]bool{
	"cr2": true, "cr3": true, "crw": true, "nef": true, "nrw": true, "arw": true, "sr2": true, "srf": true, "dng": true,
	"raf": true, "rw2": true, "orf": true, "pef": true, "srw": true, "3fr": true, "erf": true, "kdc": true, "mrw": true, "x3f": true, "raw": true,
}

var vipsExts = map[string]bool{"jxl": true}

// renderBuckets 允许的输出长边尺寸,请求会向上取最近的一档,这样缓存里每张图只会有几个固定尺寸。
var renderBuckets = []int{480, 960, 1920, 2880, 4096}

func renderBucket(w int) int {
	for _, b := range renderBuckets {
		if w <= b {
			return b
		}
	}
	return renderBuckets[len(renderBuckets)-1]
}

// Renderer 负责图片格式转换,结果缓存在媒体盘的 .mediahub\render<尺寸> 里(受缓存配额管理)。
type Renderer struct {
	db    *sql.DB
	cfg   *config.Config
	cache *cache.Manager
	sem   chan struct{} // 同时转换的张数,避免把 NAS 的 CPU 占满
}

func NewRenderer(db *sql.DB, cfg *config.Config, cm *cache.Manager) *Renderer {
	return &Renderer{db: db, cfg: cfg, cache: cm, sem: make(chan struct{}, 2)}
}

// Render 返回转换后的 JPEG 路径(没有则生成)。
func (rd *Renderer) Render(ctx context.Context, mediaID int64, w int) (string, error) {
	var rootID int64
	var ext string
	if err := rd.db.QueryRow(`SELECT root_id, ext FROM media WHERE id=?`, mediaID).Scan(&rootID, &ext); err != nil {
		return "", err
	}
	if !rd.cfg.Images.FallbackConvert {
		return "", errors.New("服务端图片转换已关闭")
	}
	ext = strings.ToLower(ext)
	w = renderBucket(w)
	kind := "render" + strconv.Itoa(w)
	if p, ok := rd.cache.Exists(rootID, kind, mediaID, "jpg"); ok {
		return p, nil
	}
	select {
	case rd.sem <- struct{}{}:
		defer func() { <-rd.sem }()
	case <-ctx.Done():
		return "", ctx.Err()
	}
	if p, ok := rd.cache.Exists(rootID, kind, mediaID, "jpg"); ok { // 排队期间别的请求可能已经生成了
		return p, nil
	}
	src, err := MediaPath(rd.db, mediaID)
	if err != nil {
		return "", err
	}
	return rd.cache.Put(rootID, kind, mediaID, "jpg", func(tmp string) error {
		cctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		defer cancel()
		switch {
		case rawExts[ext]:
			return rd.renderRaw(cctx, src, tmp, w)
		case vipsExts[ext]:
			return rd.renderVips(cctx, src, tmp, w)
		default:
			return rd.ffmpegJPEG(cctx, src, tmp, w, "")
		}
	})
}

// ffmpegJPEG 解码第一帧、缩放到长边 w(不放大)、输出 JPEG。pre 是额外的滤镜前缀(如旋转)。
func (rd *Renderer) ffmpegJPEG(ctx context.Context, src, dst string, w int, pre string) error {
	vf := fmt.Sprintf("scale='if(gt(iw,ih),min(%d,iw),-2)':'if(gt(iw,ih),-2,min(%d,ih))',format=yuvj420p", w, w)
	if pre != "" {
		vf = pre + "," + vf
	}
	cmd := exec.CommandContext(ctx, rd.cfg.Tools.FFmpeg, "-hide_banner", "-v", "error", "-y", "-i", src,
		"-frames:v", "1", "-vf", vf, "-q:v", "3", "-f", "mjpeg", dst)
	proc.Hide(cmd)
	var errb bytes.Buffer
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("ffmpeg 转换失败: %v: %s", err, strings.TrimSpace(errb.String()))
	}
	if st, err := os.Stat(dst); err != nil || st.Size() == 0 {
		return errors.New("ffmpeg 没有输出图片")
	}
	return nil
}

func (rd *Renderer) renderVips(ctx context.Context, src, dst string, w int) error {
	vips := rd.cfg.Tools.Vips
	if st, err := os.Stat(vips); err != nil || st.IsDir() {
		return errors.New("缺少 libvips,无法转换 JPEG XL")
	}
	// vips thumbnail 会自动按 EXIF 旋转;输出到带后缀的临时名,再改回目标名
	out := dst + ".jpg"
	defer os.Remove(out)
	cmd := exec.CommandContext(ctx, vips, "thumbnail", src, out+"[Q=88]", strconv.Itoa(w), "--size", "down")
	proc.Hide(cmd)
	cmd.Env = append(os.Environ(), "PATH="+filepath.Dir(vips)+string(os.PathListSeparator)+os.Getenv("PATH"))
	var errb bytes.Buffer
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("vips 转换失败: %v: %s", err, strings.TrimSpace(errb.String()))
	}
	return os.Rename(out, dst)
}

// renderRaw 取 RAW 文件里内嵌的最大预览图,再按拍摄方向旋转并缩放。
func (rd *Renderer) renderRaw(ctx context.Context, src, dst string, w int) error {
	var best []byte
	for _, tag := range []string{"-JpgFromRaw", "-PreviewImage", "-OtherImage"} {
		cmd := exec.CommandContext(ctx, rd.cfg.Tools.ExifTool, "-b", tag, src)
		proc.Hide(cmd)
		var out bytes.Buffer
		cmd.Stdout = &out
		if cmd.Run() == nil && out.Len() > len(best) && out.Len() > 1000 && bytes.HasPrefix(out.Bytes(), []byte{0xFF, 0xD8}) {
			best = append(best[:0], out.Bytes()...)
		}
	}
	if len(best) == 0 {
		return errors.New("RAW 文件里没有可用的内嵌预览图")
	}
	// 拍摄方向:6 = 顺时针 90°,8 = 逆时针 90°,3 = 180°
	pre := ""
	oc := exec.CommandContext(ctx, rd.cfg.Tools.ExifTool, "-n", "-s3", "-Orientation", src)
	proc.Hide(oc)
	if b, err := oc.Output(); err == nil {
		switch strings.TrimSpace(string(b)) {
		case "6":
			pre = "transpose=1"
		case "8":
			pre = "transpose=2"
		case "3":
			pre = "hflip,vflip"
		}
	}
	tmpSrc := dst + ".src.jpg"
	if err := os.WriteFile(tmpSrc, best, 0o644); err != nil {
		return err
	}
	defer os.Remove(tmpSrc)
	return rd.ffmpegJPEG(ctx, tmpSrc, dst, w, pre)
}

// mediaRender GET /api/v1/media/{id}/render?w=960 —— 返回转换后的 JPEG。
func (s *Server) mediaRender(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	width, _ := strconv.Atoi(r.URL.Query().Get("w"))
	if width <= 0 {
		width = 1920
	}
	path, err := s.Render.Render(r.Context(), id, width)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		writeErr(w, 404, "notfound", "媒体不存在")
		return
	case err != nil && r.Context().Err() != nil:
		return
	case err != nil:
		s.Log.Warn("render 失败", "id", id, "err", err)
		writeErr(w, 404, "render.unavailable", "无法转换这张图片")
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, 404, "render.unavailable", "转换结果不可用")
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	w.Header().Set("Content-Type", "image/jpeg")
	if r.URL.Query().Get("v") != "" {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
	http.ServeContent(w, r, "render.jpg", st.ModTime().Truncate(time.Second), f)
}
