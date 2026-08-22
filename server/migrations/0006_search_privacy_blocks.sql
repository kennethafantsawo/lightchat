-- Recherche full-text
-- messages.id est TEXT, donc on ne peut pas utiliser content='messages' (rowid INTEGER requis).
-- On utilise une table FTS5 classique indexée sur le rowid implicite de messages.
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
  message_id UNINDEXED, body, conv_id UNINDEXED, sender_id UNINDEXED
);
INSERT INTO messages_fts(rowid, message_id, body, conv_id, sender_id)
  SELECT rowid, id, body, conv_id, sender_id FROM messages;
CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(rowid, message_id, body, conv_id, sender_id)
    VALUES (new.rowid, new.id, new.body, new.conv_id, new.sender_id);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
  DELETE FROM messages_fts WHERE rowid = old.rowid;
END;
CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
  DELETE FROM messages_fts WHERE rowid = old.rowid;
  INSERT INTO messages_fts(rowid, message_id, body, conv_id, sender_id)
    VALUES (new.rowid, new.id, new.body, new.conv_id, new.sender_id);
END;

-- Confidentialité
ALTER TABLE users ADD COLUMN privacy_settings TEXT DEFAULT '{}';

-- Blocage / signalement
CREATE TABLE IF NOT EXISTS user_blocks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  blocker_id TEXT NOT NULL,
  blocked_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(blocker_id, blocked_id)
);
CREATE TABLE IF NOT EXISTS message_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  reporter_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

-- Stickers (Phase 2)
CREATE TABLE IF NOT EXISTS sticker_packs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id TEXT NOT NULL,
  title TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sticker_pack_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  pack_id INTEGER NOT NULL,
  media_key TEXT NOT NULL,
  emoji TEXT,
  position INTEGER NOT NULL DEFAULT 0
);

-- Sync cloud (Phase 2)
CREATE TABLE IF NOT EXISTS device_sync (
  user_id TEXT PRIMARY KEY,
  last_sync INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_blocks_blocker ON user_blocks(blocker_id);
CREATE INDEX IF NOT EXISTS idx_blocks_blocked ON user_blocks(blocked_id);
