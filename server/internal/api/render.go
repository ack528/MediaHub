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
	"runtime"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"mediahub/internal/cache"
	"mediahub/internal/config"
	"mediahub/internal/governor"
	"mediahub/internal/proc"
	"mediahub/internal/thumbhash"
	"mediahub/internal/winfs"
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

// thumbBuckets 缩略图(WebP)允许的长边尺寸,请求向上取最近的一档:网格 / 聊天流的小图不用下载原图(一张几 MB 的照片只为显示一个小格子)。
var thumbBuckets = []int{240, 360, 480, 720, 960, 1440}

func thumbBucket(w int) int {
	for _, b := range thumbBuckets {
		if w <= b {
			return b
		}
	}
	return thumbBuckets[len(thumbBuckets)-1]
}

// Renderer 负责图片格式转换,结果缓存在媒体盘的 .mediahub\render<尺寸> 里(受缓存配额管理)。
type Renderer struct {
	db    *sql.DB
	cfg   *config.Config
	cache *cache.Manager
	sem   chan struct{} // 同时转换的张数上限(随 CPU 核数,2 ~ 6),避免把 NAS 的 CPU 占满
	// Gov:有客户端在播放 / 传输(Streaming)时同时转换的张数降到 2,让带宽和 CPU 留给视频流;可为空
	Gov    *governor.Governor
	active atomic.Int32
}

func NewRenderer(db *sql.DB, cfg *config.Config, cm *cache.Manager) *Renderer {
	// 以前固定 2 张:第一次打开一个几千张照片的相册,缩略图一张张排队,滑动时大片灰块。每张图是一个独立的 ffmpeg 进程(单线程解码),核数多就应该并行
	n := runtime.NumCPU() / 2
	if n < 2 {
		n = 2
	}
	if n > 6 {
		n = 6
	}
	return &Renderer{db: db, cfg: cfg, cache: cm, sem: make(chan struct{}, n)}
}

// acquire 拿一个转换名额。播放 / 传输期间(governor 的 Streaming 级别)最多 2 张同时转,其余排队。
func (rd *Renderer) acquire(ctx context.Context) (release func(), err error) {
	select {
	case rd.sem <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	for rd.Gov != nil && rd.Gov.Level() == governor.Streaming && rd.active.Load() >= 2 {
		select {
		case <-ctx.Done():
			<-rd.sem
			return nil, ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
	rd.active.Add(1)
	return func() { rd.active.Add(-1); <-rd.sem }, nil
}

// lowresFor JPEG 的"缩小解码":MJPEG 解码器可以在 DCT 阶段直接按 1/2、1/4、1/8 解出小图,解码量降到 1/4 ~ 1/64,
// 再缩放到目标尺寸(质量和先全尺寸解码再缩小几乎一样)。选最大的 k,使解出来的长边仍然 >= 目标尺寸。0 = 不用。
func lowresFor(longEdge, target int) int {
	k := 0
	for k < 3 && target > 0 && (longEdge>>(k+1)) >= target {
		k++
	}
	return k
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
	release, aerr := rd.acquire(ctx)
	if aerr != nil {
		return "", aerr
	}
	defer release()
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

// RenderThumb 生成(或取缓存)一张小尺寸 WebP 缩略图(长边 w,不放大),缓存在媒体盘的 .mediahub	humb<尺寸> 里。
// 只处理手机本来就能直接显示的静态图片(jpg / png / webp / bmp);RAW / HEIC / JPEG XL 等仍走 /render 的 JPEG 转换。
// WebP 比同画质的 JPEG 小约 25% ~ 35%,原图 5MB 的照片缩略图只有十几 KB。
func (rd *Renderer) RenderThumb(ctx context.Context, mediaID int64, w int) (string, error) {
	var rootID int64
	var ext string
	var typ, sw, sh int
	var needHash bool
	if err := rd.db.QueryRow(`SELECT root_id, ext, type, COALESCE(w,0), COALESCE(h,0), thumbhash IS NULL FROM media WHERE id=?`, mediaID).Scan(&rootID, &ext, &typ, &sw, &sh, &needHash); err != nil {
		return "", err
	}
	ext = strings.ToLower(ext)
	if typ != 0 || !nativeThumbExts[ext] {
		return "", errors.New("这种文件不生成缩略图")
	}
	w = thumbBucket(w)
	kind := "thumb" + strconv.Itoa(w)
	if p, ok := rd.cache.Exists(rootID, kind, mediaID, "webp"); ok {
		return p, nil
	}
	release, aerr := rd.acquire(ctx)
	if aerr != nil {
		return "", aerr
	}
	defer release()
	if p, ok := rd.cache.Exists(rootID, kind, mediaID, "webp"); ok {
		return p, nil
	}
	src, err := MediaPath(rd.db, mediaID)
	if err != nil {
		return "", err
	}
	return rd.cache.Put(rootID, kind, mediaID, "webp", func(tmp string) error {
		cctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		vf := fmt.Sprintf("scale='if(gt(iw,ih),min(%d,iw),-2)':'if(gt(iw,ih),-2,min(%d,ih))',format=yuv420p", w, w)
		args := []string{"-hide_banner", "-v", "error", "-y"}
		if k := lowresFor(max(sw, sh), w); k > 0 && (ext == "jpg" || ext == "jpeg" || ext == "jpe" || ext == "jfif") {
			args = append(args, "-lowres", strconv.Itoa(k))
		}
		// ThumbHash(滑动时的模糊占位图)顺手算:同一次解码多输出一路 32 像素的 RGBA,几乎不花时间。
		// 以前是后台把整个图库逐张补算(每张一个 ffmpeg 进程,几十万张,9 个盘同时跑),概览里什么都没干 CPU 却很高。
		tw, th := 0, 0
		if needHash && sw > 0 && sh > 0 {
			if sw >= sh {
				tw, th = 32, max(1, (32*sh+sw/2)/sw)
			} else {
				tw, th = max(1, (32*sw+sh/2)/sh), 32
			}
		}
		// compression_level 2(原来 4):编码更快,体积差不到 2%
		webpOpts := []string{"-c:v", "libwebp", "-quality", "76", "-compression_level", "2", "-f", "webp", tmp}
		if tw > 0 {
			fc := "[0:v:0]split=2[a][b];[a]" + vf + "[pa];[b]scale=" + strconv.Itoa(tw) + ":" + strconv.Itoa(th) + ":flags=area,format=rgba[pb]"
			args = append(args, "-i", src, "-filter_complex", fc, "-map", "[pa]", "-frames:v", "1")
			args = append(args, webpOpts...)
			args = append(args, "-map", "[pb]", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1")
		} else {
			args = append(args, "-i", src, "-frames:v", "1", "-vf", vf)
			args = append(args, webpOpts...)
		}
		cmd := exec.CommandContext(cctx, rd.cfg.Tools.FFmpeg, args...)
		proc.Hide(cmd)
		var errb, outb bytes.Buffer
		cmd.Stderr, cmd.Stdout = &errb, &outb
		if err := cmd.Run(); err != nil {
			return fmt.Errorf("ffmpeg 生成缩略图失败: %v: %s", err, strings.TrimSpace(errb.String()))
		}
		if tw > 0 && outb.Len() == tw*th*4 {
			_, _ = rd.db.Exec(`UPDATE media SET thumbhash=? WHERE id=? AND thumbhash IS NULL`, thumbhash.Encode(tw, th, outb.Bytes()), mediaID)
		}
		if st, err := os.Stat(tmp); err != nil || st.Size() == 0 {
			return errors.New("ffmpeg 没有输出缩略图")
		}
		return nil
	})
}

var nativeThumbExts = map[string]bool{"jpg": true, "jpeg": true, "jpe": true, "jfif": true, "png": true, "webp": true, "bmp": true}

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
	webp := r.URL.Query().Get("fmt") == "webp"
	var path string
	var err error
	if webp {
		path, err = s.Render.RenderThumb(r.Context(), id, width)
	} else {
		path, err = s.Render.Render(r.Context(), id, width)
	}
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
	f, err := winfs.Open(path)
	if err != nil {
		writeErr(w, 404, "render.unavailable", "转换结果不可用")
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	ctype, fname := "image/jpeg", "render.jpg"
	if webp {
		ctype, fname = "image/webp", "thumb.webp"
	}
	w.Header().Set("Content-Type", ctype)
	if r.URL.Query().Get("v") != "" {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
	http.ServeContent(w, r, fname, st.ModTime().Truncate(time.Second), f)
}
