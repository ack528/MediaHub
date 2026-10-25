package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

type browseCheckpoint struct {
	JSON    json.RawMessage `json:"view"`
	SavedAt int64           `json:"savedAt"`
}

func (s *Server) historyLimits() (int, int64, int64) {
	c := s.Cfg.BrowseHistory
	limit := max(1, min(1000, c.MaxEntries))
	days := max(1, min(3650, c.RetentionDays))
	interval := max(10, min(86400, c.IntervalSeconds))
	return limit, time.Now().Add(-time.Duration(days) * 24 * time.Hour).UnixMilli(), int64(interval) * 1000
}

// A checkpoint is separate from the latest resume location. One transaction bounds
// history growth even when several devices save simultaneously.
func (s *Server) recordBrowseHistory(uid, dialog int64, js string, at int64, manual bool) error {
	if !s.Cfg.BrowseHistory.Enabled {
		return nil
	}
	var view struct {
		ItemID string `json:"itemId"`
	}
	if json.Unmarshal([]byte(js), &view) != nil || view.ItemID == "" {
		return nil
	}
	if at > time.Now().Add(time.Minute).UnixMilli() {
		at = time.Now().UnixMilli()
	}
	limit, cutoff, interval := s.historyLimits()
	if at < cutoff {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var last int64
	var previous string
	err = tx.QueryRowContext(ctx, `SELECT saved_at,json FROM user_browse_history WHERE user_id=? AND dialog_id=? ORDER BY saved_at DESC LIMIT 1`, uid, dialog).Scan(&last, &previous)
	if err != nil && err != sql.ErrNoRows {
		return err
	}
	var old struct {
		ItemID string `json:"itemId"`
	}
	_ = json.Unmarshal([]byte(previous), &old)
	if manual || (at-last >= interval && old.ItemID != view.ItemID) {
		if _, err = tx.ExecContext(ctx, `INSERT INTO user_browse_history(user_id,dialog_id,saved_at,json) VALUES(?,?,?,?) ON CONFLICT(user_id,dialog_id,saved_at) DO UPDATE SET json=excluded.json`, uid, dialog, at, js); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM user_browse_history WHERE saved_at<?`, cutoff); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM user_browse_history WHERE user_id=? AND dialog_id=? AND saved_at NOT IN (SELECT saved_at FROM user_browse_history WHERE user_id=? AND dialog_id=? ORDER BY saved_at DESC LIMIT ?)`, uid, dialog, uid, dialog, limit); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Server) listBrowseHistory(w http.ResponseWriter, r *http.Request) {
	dialog, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	limit, cutoff, _ := s.historyLimits()
	rows, err := s.DB.QueryContext(r.Context(), `SELECT json,saved_at FROM user_browse_history WHERE user_id=? AND dialog_id=? AND saved_at>=? ORDER BY saved_at DESC LIMIT ?`, userID(r), dialog, cutoff, limit)
	if err != nil {
		writeErr(w, 500, "history.read", err.Error())
		return
	}
	defer rows.Close()
	entries := []browseCheckpoint{}
	for rows.Next() {
		var entry browseCheckpoint
		var raw string
		if err = rows.Scan(&raw, &entry.SavedAt); err != nil {
			break
		}
		entry.JSON = json.RawMessage(raw)
		entries = append(entries, entry)
	}
	if err == nil {
		err = rows.Err()
	}
	if err != nil {
		writeErr(w, 500, "history.read", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"entries": entries, "config": s.Cfg.BrowseHistory})
}
func (s *Server) saveBrowseHistory(w http.ResponseWriter, r *http.Request) {
	dialog, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	if !s.Cfg.BrowseHistory.Enabled {
		writeErr(w, 403, "history.disabled", "服务器已关闭浏览历史保存")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 16<<10))
	var head struct {
		ItemID string `json:"itemId"`
	}
	if err != nil || json.Unmarshal(body, &head) != nil || head.ItemID == "" {
		writeErr(w, 400, "bad.request", "请选择有效的浏览位置")
		return
	}
	// Validate that a checkpoint belongs to this dialog and still exists.
	var found int
	if err = s.DB.QueryRowContext(r.Context(), `SELECT 1 FROM media WHERE id=? AND dialog_id=?`, head.ItemID, dialog).Scan(&found); err != nil {
		if err == sql.ErrNoRows {
			writeErr(w, 404, "notfound", "文件已删除或已移动")
		} else {
			writeErr(w, 500, "history.read", err.Error())
		}
		return
	}
	if err = s.recordBrowseHistory(userID(r), dialog, string(body), time.Now().UnixMilli(), true); err != nil {
		writeErr(w, 500, "history.write", err.Error())
		return
	}
	s.listBrowseHistory(w, r)
}
func (s *Server) deleteBrowseHistory(w http.ResponseWriter, r *http.Request) {
	dialog, ok := idParam(r, "id")
	if !ok {
		writeErr(w, 400, "bad.id", "id 无效")
		return
	}
	query := `DELETE FROM user_browse_history WHERE user_id=? AND dialog_id=?`
	args := []any{userID(r), dialog}
	if raw := r.URL.Query().Get("at"); raw != "" {
		at, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || at <= 0 {
			writeErr(w, 400, "bad.request", fmt.Sprintf("无效记录时间 %q", raw))
			return
		}
		query += " AND saved_at=?"
		args = append(args, at)
	}
	if _, err := s.DB.ExecContext(r.Context(), query, args...); err != nil {
		writeErr(w, 500, "history.write", err.Error())
		return
	}
	w.WriteHeader(204)
}
