// Package classify 按扩展名把文件归类为 图片 / 视频 / GIF / 音频 / 其它。
package classify

import "strings"

type Type int

const (
	Photo Type = iota
	Video
	GIF
	Audio
	File
)

func (t Type) String() string {
	switch t {
	case Photo:
		return "photo"
	case Video:
		return "video"
	case GIF:
		return "gif"
	case Audio:
		return "audio"
	}
	return "file"
}

func Parse(s string) (Type, bool) {
	switch s {
	case "photo":
		return Photo, true
	case "video":
		return Video, true
	case "gif":
		return GIF, true
	case "audio":
		return Audio, true
	case "file":
		return File, true
	}
	return 0, false
}

var byExt = map[string]Type{}

func init() {
	add := func(t Type, exts string) {
		for _, e := range strings.Fields(exts) {
			byExt[e] = t
		}
	}
	add(Photo, "jpg jpeg jpe jfif png webp bmp tif tiff heic heif avif jxl svg ico psd apng "+
		"dng cr2 cr3 crw nef nrw arw srf sr2 orf rw2 raf srw pef 3fr erw kdc mrw x3f raw")
	add(GIF, "gif")
	add(Video, "mp4 m4v mov mkv avi wmv flv f4v webm ts m2ts mts mpg mpeg mpe vob 3gp 3g2 rm rmvb asf ogv divx mxf")
	add(Audio, "mp3 flac wav aac m4a ogg oga opus wma ape alac aiff aif mka amr")
}

// Ext 返回小写、不带点的扩展名。
func Ext(name string) string {
	i := strings.LastIndexByte(name, '.')
	if i < 0 || i == len(name)-1 {
		return ""
	}
	return strings.ToLower(name[i+1:])
}

// ByName 返回文件所属类别;ok=false 表示不是已知媒体类型(归入 File)。
func ByName(name string) (t Type, ok bool) {
	t, ok = byExt[Ext(name)]
	if !ok {
		return File, false
	}
	return t, true
}

// IsRaw 是否为相机 RAW(需要走 ExifTool 内嵌预览)。
func IsRaw(ext string) bool {
	switch ext {
	case "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf", "srw", "pef", "3fr", "erw", "kdc", "mrw", "x3f", "raw":
		return true
	}
	return false
}
