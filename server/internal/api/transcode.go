package api

import (
	"database/sql"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"mediahub/internal/jellyfin"
)

const jfDeviceID = "mediahub-gateway"

// mediaHLS 把 Jellyfin 的 HLS 转码流转给手机:
//
//	GET /api/v1/media/{id}/hls/master.m3u8?maxHeight=720&maxBitrate=3000000&sid=xxx&audio=1
//	GET /api/v1/media/{id}/hls/main.m3u8?...、/hls/hls1/main/0.ts?...(播放列表里的相对地址,原样转发)
//
// 手机访问的始终是 MediaHub(带自己的令牌),Jellyfin 的密钥只在服务端使用。
func (s *Server) mediaHLS(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	var jfid string
	var durMs int64
	err := s.DB.QueryRow(`SELECT COALESCE(jf_item_id,''), COALESCE(duration_ms,0) FROM media WHERE id=? AND type=1`, id).Scan(&jfid, &durMs)
	if err == sql.ErrNoRows {
		writeErr(w, 404, "notfound", "视频不存在")
		return
	} else if err != nil {
		writeErr(w, 500, "internal", err.Error())
		return
	}
	rest := strings.Trim(r.PathValue("rest"), "/")
	if strings.Contains(rest, "..") || rest == "" {
		writeErr(w, 400, "bad.path", "路径无效")
		return
	}
	// 引擎选择:配置了 Jellyfin 且它已收录这个视频 → 用 Jellyfin;否则(没装 Jellyfin / 还没收录 / 引擎设为 ffmpeg)→ 内置 ffmpeg
	if s.JF == nil || jfid == "" || s.Cfg.Video.Engine == "ffmpeg" {
		s.builtinHLS(w, r, id, durMs, rest)
		return
	}

	q := r.URL.Query()
	if rest == "master.m3u8" {
		sid := q.Get("sid")
		if sid == "" {
			sid = "x"
		}
		nq := url.Values{
			"MediaSourceId": {jfid}, "VideoCodec": {"h264"}, "AudioCodec": {"aac"}, "SegmentContainer": {"ts"},
			"TranscodingMaxAudioChannels": {"2"}, "MinSegments": {"1"}, "BreakOnNonKeyFrames": {"true"},
			"PlaySessionId": {"mh" + strconv.FormatInt(id, 10) + "-" + sid}, "DeviceId": {jfDeviceID},
		}
		if h, _ := strconv.Atoi(q.Get("maxHeight")); h > 0 {
			nq.Set("MaxHeight", strconv.Itoa(h))
		}
		if b, _ := strconv.Atoi(q.Get("maxBitrate")); b > 0 {
			nq.Set("VideoBitRate", strconv.Itoa(b))
			nq.Set("MaxStreamingBitrate", strconv.Itoa(b+192000))
		}
		if a := q.Get("audio"); a != "" {
			nq.Set("AudioStreamIndex", a)
		}
		if t := q.Get("startTicks"); t != "" {
			nq.Set("StartTimeTicks", t)
		}
		q = nq
	}
	resp, err := s.JF.Stream(r.Context(), r.Method, "/Videos/"+jfid+"/"+rest+"?"+q.Encode())
	if err != nil {
		if r.Context().Err() == nil {
			s.Log.Warn("转码代理失败", "id", id, "path", rest, "err", err)
			writeErr(w, 502, "transcode.upstream", "转码引擎没有响应")
		}
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		s.Log.Warn("转码引擎返回错误", "id", id, "path", rest, "status", resp.StatusCode)
		writeErr(w, 502, "transcode.upstream", "转码引擎返回 "+strconv.Itoa(resp.StatusCode))
		return
	}
	for _, h := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if v := resp.Header.Get(h); v != "" {
			w.Header().Set(h, v)
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	fl, _ := w.(http.Flusher)
	buf := make([]byte, 64*1024)
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if fl != nil {
				fl.Flush()
			}
		}
		if err != nil {
			if err != io.EOF {
				s.Log.Debug("转码流读取中断", "id", id, "err", err)
			}
			return
		}
	}
}

// mediaHLSStop DELETE /api/v1/media/{id}/hls?sid=xxx —— 手机退出播放时结束对应的转码。
func (s *Server) mediaHLSStop(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		w.WriteHeader(204)
		return
	}
	sid := r.URL.Query().Get("sid")
	if sid == "" {
		sid = "x"
	}
	if s.HLS != nil {
		s.HLS.Stop(id, sid)
	}
	if s.JF == nil {
		w.WriteHeader(204)
		return
	}
	if err := s.JF.StopEncoding(r.Context(), jfDeviceID, "mh"+strconv.FormatInt(id, 10)+"-"+sid); err != nil {
		s.Log.Debug("结束转码失败", "id", id, "err", err)
	}
	w.WriteHeader(204)
}

var _ = jellyfin.NormPath
