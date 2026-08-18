-- Telegram-like features: reply, edit, delete (soft)
ALTER TABLE messages ADD COLUMN reply_to_id TEXT;
ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;