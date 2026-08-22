-- F1 présence : last_seen des utilisateurs.
ALTER TABLE users ADD COLUMN last_seen INTEGER NOT NULL DEFAULT 0;

-- F5 chats éphémères : TTL de auto-destruction par conversation + expiration par message.
ALTER TABLE conversations ADD COLUMN ephemeral_ttl INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN expires_at INTEGER;
