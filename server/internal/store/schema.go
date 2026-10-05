package store

import "database/sql"

// 迁移:按 PRAGMA user_version 顺序执行。只追加,不修改已发布的迁移。
var migrations = []string{
	// v1
	`
CREATE TABLE roots(
  id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, label TEXT, volume_serial INTEGER, volume_guid TEXT,
  enabled INTEGER NOT NULL DEFAULT 1, cache_dir TEXT, cache_mode TEXT, last_scan INTEGER
);
CREATE TABLE dialogs(
  id INTEGER PRIMARY KEY, root_id INTEGER NOT NULL, parent_id INTEGER, file_id INTEGER,
  rel_path TEXT NOT NULL, title TEXT NOT NULL, title_sort TEXT NOT NULL,
  cnt_photo INTEGER NOT NULL DEFAULT 0, cnt_video INTEGER NOT NULL DEFAULT 0, cnt_gif INTEGER NOT NULL DEFAULT 0,
  cnt_audio INTEGER NOT NULL DEFAULT 0, cnt_file INTEGER NOT NULL DEFAULT 0,
  cnt_recursive INTEGER NOT NULL DEFAULT 0, topics INTEGER NOT NULL DEFAULT 0,
  last_item_id INTEGER, last_taken INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
  gen INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_d_root ON dialogs(root_id, last_taken DESC);
CREATE INDEX ix_d_parent ON dialogs(parent_id);
CREATE TABLE media(
  id INTEGER PRIMARY KEY, dialog_id INTEGER NOT NULL, root_id INTEGER NOT NULL, file_id INTEGER,
  name TEXT NOT NULL, name_sort TEXT NOT NULL, ext TEXT NOT NULL, type INTEGER NOT NULL,
  size INTEGER NOT NULL, mtime INTEGER NOT NULL, ctime INTEGER NOT NULL,
  taken_at INTEGER, taken_eff INTEGER NOT NULL,
  w INTEGER, h INTEGER, rot INTEGER, duration_ms INTEGER,
  container TEXT, vcodec TEXT, acodecs TEXT, bitrate INTEGER, hdr TEXT,
  thumbhash BLOB, fingerprint TEXT NOT NULL, state INTEGER NOT NULL DEFAULT 0, err TEXT, probe_json TEXT,
  jf_item_id TEXT, gen INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_m_taken ON media(dialog_id, type, taken_eff DESC, id DESC);
CREATE INDEX ix_m_mtime ON media(dialog_id, type, mtime DESC, id DESC);
CREATE INDEX ix_m_ctime ON media(dialog_id, type, ctime DESC, id DESC);
CREATE INDEX ix_m_name  ON media(dialog_id, type, name_sort, id);
CREATE INDEX ix_m_size  ON media(dialog_id, type, size, id);
CREATE INDEX ix_m_ext   ON media(dialog_id, type, ext, id);
CREATE INDEX ix_m_state ON media(state, root_id, dialog_id);
CREATE INDEX ix_m_gen   ON media(root_id, gen);
CREATE VIRTUAL TABLE media_fts USING fts5(name, tokenize='trigram');

CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, pw_hash TEXT NOT NULL, created INTEGER NOT NULL);
CREATE TABLE tokens(hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL, device TEXT, expires INTEGER NOT NULL, last_seen INTEGER NOT NULL);
CREATE TABLE user_dialog(user_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0,
  archived INTEGER NOT NULL DEFAULT 0, last_read_taken INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id, dialog_id));
CREATE TABLE cache_entries(kind TEXT NOT NULL, media_id INTEGER NOT NULL, root_id INTEGER NOT NULL, path TEXT NOT NULL,
  bytes INTEGER NOT NULL, last_access INTEGER NOT NULL, PRIMARY KEY(kind, media_id));
CREATE INDEX ix_cache_root ON cache_entries(root_id, last_access);
`,
	// v2:记录 Jellyfin 已生成的主图标签(有标签说明 Jellyfin 那边已经有封面,可直接转发)
	`ALTER TABLE media ADD COLUMN jf_img_tag TEXT;`,
	// v3:对话 = 盘符下的母文件夹(其下所有子文件夹平铺进去),所以每个文件要自己记录真实所在目录(相对根目录),用来拼出文件路径
	`ALTER TABLE media ADD COLUMN dir_rel TEXT NOT NULL DEFAULT '';`,
	// v4:扫描可续扫 + 旧数据整理
	//  - roots.scan_state / scan_gen:本次扫描的状态('' 从未扫描 / scanning 进行中或被中断 / complete)与代号;
	//    dialogs.scanned_gen:该母文件夹在哪一代扫描中已经完整扫完,中断后续扫时据此跳过。
	//  - 旧版本(每个文件夹一个对话)留下的数据立即整理成新规则:媒体并入所在的母文件夹对话,删掉子文件夹对话,
	//    这样升级后哪怕扫描中途被关掉,手机上也不会再看到子文件夹的群。
	`
ALTER TABLE roots ADD COLUMN scan_state TEXT NOT NULL DEFAULT '';
ALTER TABLE roots ADD COLUMN scan_gen INTEGER NOT NULL DEFAULT 0;
ALTER TABLE dialogs ADD COLUMN scanned_gen INTEGER NOT NULL DEFAULT 0;
UPDATE media SET dir_rel = COALESCE((SELECT d.rel_path FROM dialogs d WHERE d.id = media.dialog_id), '') WHERE dir_rel = '';
UPDATE media SET dialog_id = COALESCE((
  SELECT t.id FROM dialogs d JOIN dialogs t ON t.root_id = d.root_id
   AND t.rel_path = CASE WHEN instr(d.rel_path, '\') > 0 THEN substr(d.rel_path, 1, instr(d.rel_path, '\') - 1) ELSE d.rel_path END
  WHERE d.id = media.dialog_id), dialog_id);
DELETE FROM dialogs WHERE instr(rel_path, '\') > 0;
UPDATE dialogs SET parent_id = NULL, topics = 0;
`,
	// v5:文件完整性检查标记(chk);并让之前"解析失败"的视频重新补全一次,以便写上更明确的原因
	`
ALTER TABLE media ADD COLUMN chk INTEGER NOT NULL DEFAULT 0;
UPDATE media SET state=0 WHERE state=2;
`,
	// v6:旧版本(0.4.0 之前)没有记录扫描状态,升级后会被当成"从没扫描过"而整盘重扫。
	// 凡是已经有索引数据的根目录,视为"已完整扫描过":直接使用原有数据,之后按定时间隔 / 手动重新扫描更新。
	`
UPDATE roots SET scan_state='complete', last_scan=COALESCE(last_scan, CAST(strftime('%s','now') AS INTEGER))
 WHERE scan_state='' AND EXISTS (SELECT 1 FROM media WHERE media.root_id = roots.id);
`,
}

// Migrate 把数据库升级到最新模式。
func Migrate(db *sql.DB) error {
	var cur int
	if err := db.QueryRow("PRAGMA user_version").Scan(&cur); err != nil {
		return err
	}
	for i := cur; i < len(migrations); i++ {
		tx, err := db.Begin()
		if err != nil {
			return err
		}
		if _, err := tx.Exec(migrations[i]); err != nil {
			tx.Rollback()
			return err
		}
		if _, err := tx.Exec("PRAGMA user_version = " + itoa(i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
