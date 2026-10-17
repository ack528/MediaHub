package api

import (
	"bufio"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"mediahub/internal/proc"
	"mediahub/internal/winfs"
)

// 兼容版本(只转需要转的部分):
//
// 手机播不了一个视频,常见原因其实只是 音轨(DTS / TrueHD / 某些 AC3)或容器(avi / 部分 mkv / ts)不支持,视频流本身手机是能解的。
// 这时整条重编码转码(HLS)又费 CPU 又损失画质、起播还慢。兼容版本的做法:视频流原样复制(不重新编码,画质无损),
// 只把音频转成 AAC,装进 MP4(faststart),缓存在媒体盘 .mediahub\compat(受缓存配额 / LRU 管理)。
// 之后这个视频就是普通的直放:秒开、可随意拖动、不占转码路数。
//
//	GET  /api/v1/media/{id}/compat          做好了 → 直接返回文件(支持 Range);没做好 → 202 + 状态 JSON
//	POST /api/v1/media/{id}/compat/prepare  开始(后台)制作;已经有 / 正在做就返回当前状态
//
// 手机端:判断这个视频直放会失败 / 没声音时,先看有没有兼容版本;没有就一边请求制作、一边用转码(HLS)播,下次直放。

type compatState struct {
	State    string  `json:"state"` // none | preparing | ready | failed
	Progress float64 `json:"progress"`
	Err      string  `json:"err,omitempty"`
	Why      string  `json:"why,omitempty"` // 为什么要做兼容版本 / 为什么做不了
}

type compatJobs struct {
	mu   sync.Mutex
	jobs map[int64]*compatState
	run  chan struct{} // 同时只做一个:读源文件 + 写缓存都是磁盘大块读写,多个一起做会互相拖慢
}

func (s *Server) compat() *compatJobs {
	s.compatOnce.Do(func() { s.compatJ = &compatJobs{jobs: map[int64]*compatState{}, run: make(chan struct{}, 1)} })
	return s.compatJ
}

// mp4 能装下、并且手机通常能解的视频编码。别的(vc1 / wmv / rv / mpeg2 …)只能完整转码。
var compatVideo = map[string]bool{"h264": true, "hevc": true, "av1": true, "vp9": true}

func (s *Server) compatPathOf(id int64) (rootID int64, path string, ok bool) {
	if err := s.DB.QueryRow(`SELECT root_id FROM media WHERE id=?`, id).Scan(&rootID); err != nil {
		return 0, "", false
	}
	path, ok = s.Cache.Exists(rootID, "compat", id, "mp4")
	if ok { // 原视频在做完兼容版本之后又被改过(替换 / 重新下载):旧的兼容版本作废
		if src, err := MediaPath(s.DB, id); err == nil {
			if si, e1 := os.Stat(src); e1 == nil {
				if ci, e2 := os.Stat(winfs.Path(path)); e2 == nil && si.ModTime().After(ci.ModTime()) {
					_ = os.Remove(winfs.Path(path))
					return rootID, "", false
				}
			}
		}
	}
	return
}

func (s *Server) mediaCompat(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	if _, p, ok := s.compatPathOf(id); ok {
		f, err := winfs.Open(p)
		if err == nil {
			defer f.Close()
			st, _ := f.Stat()
			w.Header().Set("Content-Type", "video/mp4")
			w.Header().Set("Cache-Control", "no-store")
			http.ServeContent(w, r, "compat.mp4", st.ModTime(), f)
			return
		}
	}
	writeJSON(w, 202, s.compatStatus(id))
}

func (s *Server) compatStatus(id int64) compatState {
	c := s.compat()
	c.mu.Lock()
	defer c.mu.Unlock()
	if j := c.jobs[id]; j != nil {
		return *j
	}
	if _, _, ok := s.compatPathOf(id); ok {
		return compatState{State: "ready", Progress: 1}
	}
	return compatState{State: "none"}
}

func (s *Server) mediaCompatPrepare(w http.ResponseWriter, r *http.Request) {
	id, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	writeJSON(w, 200, s.startCompat(id))
}

// startCompat 开始制作(后台)。返回当前状态。
func (s *Server) startCompat(id int64) compatState {
	c := s.compat()
	c.mu.Lock()
	if j := c.jobs[id]; j != nil && j.State == "preparing" {
		st := *j
		c.mu.Unlock()
		return st
	}
	c.mu.Unlock()
	if _, _, ok := s.compatPathOf(id); ok {
		return compatState{State: "ready", Progress: 1}
	}
	m, err := s.mediaInfoByID(id)
	if err != nil || m.Type != 1 {
		return compatState{State: "failed", Err: "不是视频"}
	}
	vc := strings.ToLower(m.Vcodec)
	if !compatVideo[vc] {
		st := compatState{State: "failed", Err: "视频编码 " + m.Vcodec + " 不能直接封装,只能转码", Why: "video-codec"}
		c.mu.Lock()
		c.jobs[id] = &st
		c.mu.Unlock()
		return st
	}
	if s.Cfg.Tools.FFmpeg == "" {
		return compatState{State: "failed", Err: "没有 ffmpeg"}
	}
	select { // 同时只做一个;忙就先不做(手机会继续用转码播放,下次再请求)
	case c.run <- struct{}{}:
	default:
		return compatState{State: "none", Why: "busy"}
	}
	st := &compatState{State: "preparing"}
	c.mu.Lock()
	c.jobs[id] = st
	c.mu.Unlock()
	go func() {
		defer func() { <-c.run }()
		defer func() {
			if r := recover(); r != nil {
				c.mu.Lock()
				st.State, st.Err = "failed", fmt.Sprint(r)
				c.mu.Unlock()
			}
		}()
		err := s.buildCompat(m, st, c)
		c.mu.Lock()
		if err != nil {
			st.State, st.Err = "failed", err.Error()
			s.Log.Warn("兼容版本制作失败", "id", id, "media", m.Name, "err", err)
		} else {
			delete(c.jobs, id) // 之后状态由缓存里有没有文件决定
			s.Log.Info("兼容版本已制作", "id", id, "media", m.Name)
		}
		c.mu.Unlock()
		if err != nil { // 失败的状态保留 10 分钟,免得手机反复请求;之后可以重试
			time.AfterFunc(10*time.Minute, func() {
				c.mu.Lock()
				if c.jobs[id] == st {
					delete(c.jobs, id)
				}
				c.mu.Unlock()
			})
		}
	}()
	return *st
}

func (s *Server) buildCompat(m mediaInfo, st *compatState, c *compatJobs) error {
	durMs := m.DurMs
	if durMs <= 0 {
		durMs = probeDurationMs(s.Cfg.Tools.FFprobe, m.full())
	}
	vc := strings.ToLower(m.Vcodec)
	_, err := s.Cache.Put(m.RootID, "compat", m.ID, "mp4", func(tmp string) error {
		args := []string{"-hide_banner", "-nostdin", "-y", "-v", "error", "-progress", "pipe:1", "-nostats",
			"-fflags", "+genpts", "-i", m.full(),
			"-map", "0:V:0", "-map", "0:a?", "-sn", "-dn", "-map_metadata", "-1", "-c:v", "copy"}
		if vc == "hevc" {
			args = append(args, "-tag:v", "hvc1") // 苹果 / 部分播放器要求
		}
		// 音频:AAC 原样复制,其它转成 AAC(多声道保留声道数,最多 6 声道)
		args = append(args, "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-movflags", "+faststart", "-f", "mp4", winfs.Path(tmp))
		cmd := proc.Background(exec.Command(s.Cfg.Tools.FFmpeg, args...))
		stderr := &tailBuf{}
		cmd.Stderr = stderr
		out, err := cmd.StdoutPipe()
		if err != nil {
			return err
		}
		if err := cmd.Start(); err != nil {
			return err
		}
		sc := bufio.NewScanner(out)
		for sc.Scan() {
			if v, ok := strings.CutPrefix(sc.Text(), "out_time_us="); ok && durMs > 0 {
				if us, e := strconv.ParseInt(v, 10, 64); e == nil && us > 0 {
					p := float64(us) / 1000 / float64(durMs)
					if p > 0.999 {
						p = 0.999
					}
					c.mu.Lock()
					st.Progress = p
					c.mu.Unlock()
				}
			}
		}
		if err := cmd.Wait(); err != nil {
			return fmt.Errorf("ffmpeg 失败:%s", ffmpegReason(stderr.String()))
		}
		fi, err := os.Stat(winfs.Path(tmp))
		if err != nil || fi.Size() == 0 {
			return fmt.Errorf("没有生成文件")
		}
		// 校验:时长对、视频流在、结尾能解码(和压缩一样的标准)
		dur := probeDurationMs(s.Cfg.Tools.FFprobe, winfs.Path(tmp))
		if durMs > 0 && (dur < durMs-durMs/50-2000 || dur > durMs+durMs/50+2000) {
			return fmt.Errorf("输出时长不对(原 %d 秒,输出 %d 秒)", durMs/1000, dur/1000)
		}
		if why := decodeWindow(s.Cfg.Tools.FFmpeg, winfs.Path(tmp), max(float64(dur)/1000-8, 0), 6); why != "" {
			return fmt.Errorf("输出结尾不能完整解码:%s", why)
		}
		return nil
	})
	return err
}

