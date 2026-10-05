package api

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"

	"mediahub/internal/classify"
	"mediahub/internal/meta"
)

type VideoInfo struct {
	Container   string   `json:"container,omitempty"`
	VCodec      string   `json:"vcodec,omitempty"`
	Profile     string   `json:"profile,omitempty"`
	BitrateKbps int64    `json:"bitrateKbps,omitempty"`
	HDR         string   `json:"hdr,omitempty"`
	ACodecs     []string `json:"acodecs,omitempty"`
	Subs        int      `json:"subs"`
}

type Flags struct {
	Animated bool `json:"animated"`
	Corrupt  bool `json:"corrupt"`
}

type Item struct {
	ID         string     `json:"id"`
	DialogID   string     `json:"dialogId"`
	Name       string     `json:"name"`
	Ext        string     `json:"ext"`
	Type       string     `json:"type"`
	Mime       string     `json:"mime"`
	Size       int64      `json:"size"`
	W          *int       `json:"w,omitempty"`
	H          *int       `json:"h,omitempty"`
	Rotation   int        `json:"rotation"`
	TakenAt    string     `json:"takenAt"`
	ModifiedAt string     `json:"modifiedAt"`
	CreatedAt  string     `json:"createdAt"`
	DurationMs *int64     `json:"durationMs,omitempty"`
	Thumbhash  string     `json:"thumbhash,omitempty"`
	V          string     `json:"v"`
	Video      *VideoInfo `json:"video,omitempty"`
	Flags      Flags      `json:"flags"`
}

// itemCols 与 scanItem 的列顺序必须一致。排序键作为额外一列追加在最后。
const itemCols = `m.id, m.dialog_id, m.name, m.ext, m.type, m.size, m.mtime, m.ctime, m.taken_eff,
	m.w, m.h, m.rot, m.duration_ms, m.container, m.vcodec, m.acodecs, m.bitrate, m.hdr, m.state, m.probe_json, m.thumbhash`

type scanner interface{ Scan(dest ...any) error }

func scanItem(sc scanner, extra ...any) (Item, error) {
	var (
		it                                  Item
		id, dlg, size, mtime, ctime, taken  int64
		typ, state                          int
		w, h, rot                           sql.NullInt64
		dur, br                             sql.NullInt64
		container, vcodec, acodecs, hdr, pj sql.NullString
		name, ext                           string
		th                                  []byte
	)
	dest := []any{&id, &dlg, &name, &ext, &typ, &size, &mtime, &ctime, &taken, &w, &h, &rot, &dur, &container, &vcodec, &acodecs, &br, &hdr, &state, &pj, &th}
	dest = append(dest, extra...)
	if err := sc.Scan(dest...); err != nil {
		return it, err
	}
	it.ID, it.DialogID = sid(id), sid(dlg)
	it.Name, it.Ext, it.Size = name, ext, size
	it.Type = classify.Type(typ).String()
	it.Mime = MimeByExt(ext)
	it.TakenAt, it.ModifiedAt, it.CreatedAt = rfc(taken), rfc(mtime), rfc(ctime)
	it.V = fmt.Sprintf("%x", uint64(mtime)^uint64(size)*0x9e3779b97f4a7c15>>20)
	if w.Valid && h.Valid {
		wi, hi := int(w.Int64), int(h.Int64)
		it.W, it.H = &wi, &hi
	}
	if rot.Valid {
		it.Rotation = int(rot.Int64)
	}
	if len(th) > 0 {
		it.Thumbhash = base64.StdEncoding.EncodeToString(th)
	}
	it.Flags.Animated = ext == "gif" || ext == "apng"
	it.Flags.Corrupt = state == 2
	if classify.Type(typ) == classify.Video || classify.Type(typ) == classify.Audio {
		if dur.Valid {
			d := dur.Int64
			it.DurationMs = &d
		}
	}
	if classify.Type(typ) == classify.Video && state == 1 {
		vi := &VideoInfo{Container: container.String, VCodec: vcodec.String, BitrateKbps: br.Int64 / 1000, HDR: hdr.String}
		if acodecs.String != "" {
			vi.ACodecs = splitComma(acodecs.String)
		}
		if pj.Valid {
			var v meta.Video
			if json.Unmarshal([]byte(pj.String), &v) == nil {
				for _, s := range v.Streams {
					switch s.Type {
					case "video":
						if vi.Profile == "" {
							vi.Profile = s.Profile
						}
					case "subtitle":
						vi.Subs++
					}
				}
			}
		}
		it.Video = vi
	}
	return it, nil
}

func splitComma(s string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(s); i++ {
		if i == len(s) || s[i] == ',' {
			if i > start {
				out = append(out, s[start:i])
			}
			start = i + 1
		}
	}
	return out
}

var mimeByExt = map[string]string{
	"jpg": "image/jpeg", "jpeg": "image/jpeg", "jpe": "image/jpeg", "jfif": "image/jpeg", "png": "image/png", "apng": "image/apng",
	"webp": "image/webp", "bmp": "image/bmp", "gif": "image/gif", "tif": "image/tiff", "tiff": "image/tiff",
	"heic": "image/heic", "heif": "image/heif", "avif": "image/avif", "jxl": "image/jxl", "svg": "image/svg+xml", "ico": "image/x-icon",
	"psd": "image/vnd.adobe.photoshop",
	"mp4": "video/mp4", "m4v": "video/mp4", "mov": "video/quicktime", "mkv": "video/x-matroska", "avi": "video/x-msvideo",
	"wmv": "video/x-ms-wmv", "flv": "video/x-flv", "f4v": "video/x-f4v", "webm": "video/webm", "ts": "video/mp2t",
	"m2ts": "video/mp2t", "mts": "video/mp2t", "mpg": "video/mpeg", "mpeg": "video/mpeg", "mpe": "video/mpeg",
	"vob": "video/mpeg", "3gp": "video/3gpp", "3g2": "video/3gpp2", "ogv": "video/ogg", "asf": "video/x-ms-asf",
	"rm": "application/vnd.rn-realmedia", "rmvb": "application/vnd.rn-realmedia-vbr",
	"mp3": "audio/mpeg", "flac": "audio/flac", "wav": "audio/wav", "aac": "audio/aac", "m4a": "audio/mp4",
	"ogg": "audio/ogg", "oga": "audio/ogg", "opus": "audio/ogg", "wma": "audio/x-ms-wma", "aiff": "audio/aiff", "aif": "audio/aiff",
	"mka": "audio/x-matroska", "amr": "audio/amr",
}

// MimeByExt 返回扩展名对应的 MIME(不依赖 Windows 注册表)。RAW 等归为 application/octet-stream。
func MimeByExt(ext string) string {
	if m, ok := mimeByExt[ext]; ok {
		return m
	}
	return "application/octet-stream"
}
