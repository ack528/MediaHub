// Package meta 通过 ffprobe(视频)和 ExifTool(照片)读取元数据。
package meta

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"mediahub/internal/proc"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Stream struct {
	Type     string  `json:"type"` // video | audio | subtitle
	Codec    string  `json:"codec"`
	Profile  string  `json:"profile,omitempty"`
	W        int     `json:"w,omitempty"`
	H        int     `json:"h,omitempty"`
	FPS      float64 `json:"fps,omitempty"`
	PixFmt   string  `json:"pix,omitempty"`
	Transfer string  `json:"trc,omitempty"`
	Channels int     `json:"ch,omitempty"`
	Lang     string  `json:"lang,omitempty"`
	Title    string  `json:"title,omitempty"`
	Default  bool    `json:"def,omitempty"`
	Bitrate  int64   `json:"br,omitempty"`
	Level    int     `json:"level,omitempty"`
	Index    int     `json:"idx"`
	Rotation int     `json:"rot,omitempty"`
	BitDepth int     `json:"bits,omitempty"`
	DolbyVis bool    `json:"dv,omitempty"`
}

type Video struct {
	Container  string   `json:"container"`
	DurationMs int64    `json:"durationMs"`
	Bitrate    int64    `json:"bitrate"`
	Streams    []Stream `json:"streams"`

	// 以下为便于入库的派生字段,不进 JSON
	CreatedAt time.Time `json:"-"`
	W, H      int       `json:"-"`
	VCodec    string    `json:"-"`
	ACodecs   []string  `json:"-"`
	HDR       string    `json:"-"`
	Rotation  int       `json:"-"`
}

type ffprobeOut struct {
	Format struct {
		FormatName string            `json:"format_name"`
		Duration   string            `json:"duration"`
		BitRate    string            `json:"bit_rate"`
		Tags       map[string]string `json:"tags"`
	} `json:"format"`
	Streams []struct {
		Index         int               `json:"index"`
		CodecType     string            `json:"codec_type"`
		CodecName     string            `json:"codec_name"`
		Profile       string            `json:"profile"`
		Width         int               `json:"width"`
		Height        int               `json:"height"`
		PixFmt        string            `json:"pix_fmt"`
		ColorTransfer string            `json:"color_transfer"`
		RFrameRate    string            `json:"r_frame_rate"`
		AvgFrameRate  string            `json:"avg_frame_rate"`
		Channels      int               `json:"channels"`
		BitRate       string            `json:"bit_rate"`
		Level         int               `json:"level"`
		BitsPerRaw    string            `json:"bits_per_raw_sample"`
		Tags          map[string]string `json:"tags"`
		Disposition   map[string]int    `json:"disposition"`
		SideData      []map[string]any  `json:"side_data_list"`
	} `json:"streams"`
}

func parseRate(s string) float64 {
	a, b, ok := strings.Cut(s, "/")
	if !ok {
		f, _ := strconv.ParseFloat(s, 64)
		return f
	}
	x, _ := strconv.ParseFloat(a, 64)
	y, _ := strconv.ParseFloat(b, 64)
	if y == 0 {
		return 0
	}
	return x / y
}

// Probe 调用 ffprobe 并整理成 Video。
func Probe(ctx context.Context, ffprobe, file string) (*Video, error) {
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file)
	proc.Hide(cmd)
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("ffprobe: %w: %s", err, strings.TrimSpace(errb.String()))
	}
	var p ffprobeOut
	if err := json.Unmarshal(out.Bytes(), &p); err != nil {
		return nil, err
	}
	v := &Video{}
	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(file), "."))
	first, _, _ := strings.Cut(p.Format.FormatName, ",")
	switch {
	case strings.Contains(p.Format.FormatName, "matroska"):
		v.Container = "mkv"
		if ext == "webm" {
			v.Container = "webm"
		}
	case strings.Contains(p.Format.FormatName, "mp4"):
		v.Container = "mp4"
		if ext == "mov" || ext == "3gp" || ext == "3g2" || ext == "m4v" {
			v.Container = ext
		}
	case first == "mpegts":
		v.Container = "ts"
	default:
		v.Container = first
	}
	if d, err := strconv.ParseFloat(p.Format.Duration, 64); err == nil {
		v.DurationMs = int64(d * 1000)
	}
	v.Bitrate, _ = strconv.ParseInt(p.Format.BitRate, 10, 64)
	if ct := p.Format.Tags["creation_time"]; ct != "" {
		if t, err := time.Parse(time.RFC3339Nano, ct); err == nil && t.Year() > 1990 {
			v.CreatedAt = t
		}
	}
	for _, s := range p.Streams {
		st := Stream{Index: s.Index, Type: s.CodecType, Codec: s.CodecName, Profile: s.Profile, W: s.Width, H: s.Height,
			PixFmt: s.PixFmt, Transfer: s.ColorTransfer, Channels: s.Channels, Level: s.Level,
			Lang: s.Tags["language"], Title: s.Tags["title"], Default: s.Disposition["default"] == 1}
		st.Bitrate, _ = strconv.ParseInt(s.BitRate, 10, 64)
		st.BitDepth, _ = strconv.Atoi(s.BitsPerRaw)
		fps := parseRate(s.AvgFrameRate)
		if fps == 0 {
			fps = parseRate(s.RFrameRate)
		}
		st.FPS = fps
		for _, sd := range s.SideData {
			if r, ok := sd["rotation"].(float64); ok {
				st.Rotation = int(r)
			}
			if t, _ := sd["side_data_type"].(string); strings.Contains(t, "DOVI") {
				st.DolbyVis = true
			}
		}
		if r := s.Tags["rotate"]; r != "" && st.Rotation == 0 {
			st.Rotation, _ = strconv.Atoi(r)
		}
		v.Streams = append(v.Streams, st)
		switch s.CodecType {
		case "video":
			if v.VCodec == "" && s.Disposition["attached_pic"] != 1 {
				v.VCodec, v.W, v.H, v.Rotation = s.CodecName, s.Width, s.Height, st.Rotation
				switch {
				case st.DolbyVis:
					v.HDR = "dv"
				case s.ColorTransfer == "smpte2084":
					v.HDR = "hdr10"
				case s.ColorTransfer == "arib-std-b67":
					v.HDR = "hlg"
				}
			}
		case "audio":
			v.ACodecs = append(v.ACodecs, s.CodecName)
		}
	}
	// 竖拍视频:旋转 90/270 时显示宽高互换
	if r := ((v.Rotation % 360) + 360) % 360; r == 90 || r == 270 {
		v.W, v.H = v.H, v.W
	}
	return v, nil
}

// Photo 是 ExifTool 读出的照片元数据。
type Photo struct {
	Taken       time.Time
	W, H        int
	Orientation int
}

type exifRec struct {
	SourceFile       string `json:"SourceFile"`
	DateTimeOriginal any    `json:"DateTimeOriginal"`
	CreateDate       any    `json:"CreateDate"`
	ImageWidth       any    `json:"ImageWidth"`
	ImageHeight      any    `json:"ImageHeight"`
	Orientation      any    `json:"Orientation"`
	Rotation         any    `json:"Rotation"` // HEIF 容器自带的旋转(irot):1 / 3 表示需要转 90°
}

func num(v any) int {
	switch x := v.(type) {
	case float64:
		return int(x)
	case string:
		n, _ := strconv.Atoi(x)
		return n
	}
	return 0
}

func parseExifTime(v any, loc *time.Location) time.Time {
	s, _ := v.(string)
	if len(s) < 19 {
		return time.Time{}
	}
	s = strings.Replace(s[:10], ":", "-", 2) + s[10:]
	for _, layout := range []string{"2006-01-02 15:04:05-07:00", "2006-01-02 15:04:05Z07:00", "2006-01-02 15:04:05"} {
		if t, err := time.ParseInLocation(layout, s, loc); err == nil && t.Year() > 1990 {
			return t
		}
	}
	return time.Time{}
}

// ExifBatch 一次调用读取多张照片(只读文件头)。返回键为传入的原路径。
func ExifBatch(ctx context.Context, exiftool string, files []string) (map[string]*Photo, error) {
	if len(files) == 0 {
		return nil, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, exiftool, "-charset", "filename=utf8", "-json", "-n", "-q", "-fast2",
		"-DateTimeOriginal", "-CreateDate", "-ImageWidth", "-ImageHeight", "-Orientation", "-Rotation", "-@", "-")
	proc.Hide(cmd)
	var in bytes.Buffer
	for _, f := range files {
		in.WriteString(f)
		in.WriteByte('\n')
	}
	cmd.Stdin = &in
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	// exiftool 遇到个别损坏文件会返回非 0,但仍输出可用结果,因此只在没有输出时才算失败
	runErr := cmd.Run()
	if out.Len() == 0 {
		if runErr != nil {
			return nil, fmt.Errorf("exiftool: %w: %s", runErr, strings.TrimSpace(errb.String()))
		}
		return map[string]*Photo{}, nil
	}
	var recs []exifRec
	if err := json.Unmarshal(out.Bytes(), &recs); err != nil {
		return nil, fmt.Errorf("exiftool json: %w", err)
	}
	byNorm := make(map[string]string, len(files))
	for _, f := range files {
		byNorm[norm(f)] = f
	}
	res := make(map[string]*Photo, len(recs))
	for _, r := range recs {
		orig, ok := byNorm[norm(r.SourceFile)]
		if !ok {
			continue
		}
		p := &Photo{W: num(r.ImageWidth), H: num(r.ImageHeight), Orientation: num(r.Orientation)}
		p.Taken = parseExifTime(r.DateTimeOriginal, time.Local)
		if p.Taken.IsZero() {
			p.Taken = parseExifTime(r.CreateDate, time.Local)
		}
		// EXIF 方向 5–8 表示需要转 90°;HEIF 容器的旋转(irot)只在 EXIF 没有表达旋转时才计入,
		// 否则 iPhone 这种两个标签都写了的文件会被算两次。
		swap := p.Orientation >= 5 && p.Orientation <= 8
		if rot := num(r.Rotation); !swap && (rot == 1 || rot == 3) {
			swap = true
		}
		if swap {
			p.W, p.H = p.H, p.W
		}
		res[orig] = p
	}
	return res, nil
}

func norm(p string) string { return strings.ToLower(filepath.ToSlash(filepath.Clean(p))) }
