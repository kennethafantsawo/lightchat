-- Phase 4 : épinglage, réactions, brouillons, indicateurs de frappe
ALTER TABLE messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS message_reactions (
  message_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  emoji TEXT NOT NULL,
  PRIMARY KEY (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_mr_msg ON message_reactions(message_id);

CREATE TABLE IF NOT EXISTS drafts (
  user_id TEXT NOT NULL,
  conv_id TEXT NOT NULL,
  body TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, conv_id)
);
CREATE INDEX IF NOT EXISTS idx_drafts_user ON drafts(user_id);