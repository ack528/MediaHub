package api

import "time"

// 压缩替换后文件换了名字 / 扩展名,媒体 id 是按文件系统的文件 ID 算的,新文件会得到一个新的 id,
// 旧 id 上记的播放进度就找不到了。替换前先把进度记下来,等重新扫描把新文件入库后再接到新 id 上。
type carryRow struct {
	rootID        int64
	dirRel, name  string
	user, pos, at int64
	tries         int
}

func (s *Server) carryPlayback(m mediaInfo, finalName string) {
	rows, err := s.DB.Query(`SELECT user_id, pos_ms, saved_at FROM user_playback WHERE media_id=?`, m.ID)
	if err != nil {
		return
	}
	var add []carryRow
	for rows.Next() {
		r := carryRow{rootID: m.RootID, dirRel: m.DirRel, name: finalName}
		if rows.Scan(&r.user, &r.pos, &r.at) == nil {
			add = append(add, r)
		}
	}
	rows.Close()
	if len(add) == 0 {
		return
	}
	s.opt.carryMu.Lock()
	s.opt.carry = append(s.opt.carry, add...)
	s.opt.carryMu.Unlock()
	s.opt.carryOnce.Do(func() { go s.carryLoop() })
}

func (s *Server) carryLoop() {
	for range time.Tick(20 * time.Second) {
		s.opt.carryMu.Lock()
		keep := s.opt.carry[:0]
		for _, r := range s.opt.carry {
			var id int64
			if s.DB.QueryRow(`SELECT id FROM media WHERE root_id=? AND dir_rel=? AND name=?`, r.rootID, r.dirRel, r.name).Scan(&id) == nil {
				_, _ = s.DB.Exec(`INSERT OR REPLACE INTO user_playback(user_id, media_id, pos_ms, saved_at) VALUES(?,?,?,?)`, r.user, id, r.pos, r.at)
				continue
			}
			if r.tries++; r.tries < 45 { // 约 15 分钟:重扫在 90 秒后才开始
				keep = append(keep, r)
			}
		}
		s.opt.carry = keep
		s.opt.carryMu.Unlock()
	}
}
