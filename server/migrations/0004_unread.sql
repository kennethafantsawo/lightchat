-- Phase 6 : suivi des messages non lus par utilisateur.
CREATE TABLE IF NOT EXISTS last_read (
  user_id TEXT NOT NULL,
  conv_id TEXT NOT NULL,
  up_to INTEGER NOT NULL,
  PRIMARY KEY (user_id, conv_id)
);
