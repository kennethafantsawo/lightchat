import type { Env } from "./index";
import type { Message, MessageReaction } from "./types";
import { canAccessConv } from "./messages";
import { getMessage } from "./db";

export async function togglePin(env: Env, convId: string, messageId: string, userId: string, pinned: boolean) {
  const member = await canAccessConv(env, convId, userId);
  if (!member) return { error: "Non autorisé." };
  const msg = await getMessage(env, messageId) as unknown as Message | null;
  if (!msg || msg.conv_id !== convId) return { error: "Message introuvable." };
  await env.DB.prepare(`UPDATE messages SET pinned = ? WHERE id = ?`).bind(pinned ? 1 : 0, messageId).run();
  const updated = await getMessage(env, messageId) as unknown as Message | null;
  return { ok: true, message: updated as Message };
}

export async function getReactions(env: Env, messageId: string): Promise<MessageReaction[]> {
  const rows = await env.DB.prepare(`SELECT message_id, user_id, emoji FROM message_reactions WHERE message_id = ?`).bind(messageId).all();
  return rows.results as unknown as MessageReaction[];
}

export async function toggleReaction(env: Env, messageId: string, userId: string, emoji: string) {
  const msg = await getMessage(env, messageId) as unknown as Message | null;
  if (!msg) return { error: "Message introuvable." };
  const member = await canAccessConv(env, msg.conv_id, userId);
  if (!member) return { error: "Non autorisé." };
  const existing = await env.DB.prepare(`SELECT 1 FROM message_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?`).bind(messageId, userId, emoji).first();
  if (existing) {
    await env.DB.prepare(`DELETE FROM message_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?`).bind(messageId, userId, emoji).run();
  } else {
    await env.DB.prepare(`INSERT OR IGNORE INTO message_reactions (message_id, user_id, emoji) VALUES (?, ?, ?)`).bind(messageId, userId, emoji).run();
  }
  const reactions = await getReactions(env, messageId);
  return { ok: true, reactions };
}

export async function reactionsForMessages(env: Env, messageIds: string[]): Promise<Map<string, MessageReaction[]>> {
  const out = new Map<string, MessageReaction[]>();
  if (messageIds.length === 0) return out;
  const placeholders = messageIds.map(() => "?").join(",");
  const rows = await env.DB.prepare(
    `SELECT message_id, user_id, emoji FROM message_reactions WHERE message_id IN (${placeholders})`
  ).bind(...messageIds).all();
  for (const r of rows.results as any[]) {
    const key = String(r.message_id);
    const list = out.get(key);
    if (list) list.push(r as MessageReaction);
    else out.set(key, [r as MessageReaction]);
  }
  return out;
}

export async function saveDraft(env: Env, userId: string, convId: string, body: string) {
  const member = await canAccessConv(env, convId, userId);
  if (!member) return { error: "Non autorisé." };
  const text = String(body ?? "");
  if (text.trim().length === 0) {
    await env.DB.prepare(`DELETE FROM drafts WHERE user_id = ? AND conv_id = ?`).bind(userId, convId).run();
    return { ok: true, draft: null };
  }
  await env.DB.prepare(
    `INSERT INTO drafts (user_id, conv_id, body, updated_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(user_id, conv_id) DO UPDATE SET body = excluded.body, updated_at = excluded.updated_at`
  ).bind(userId, convId, text, Date.now()).run();
  return { ok: true, draft: { body: text } };
}

export async function getDraft(env: Env, userId: string, convId: string) {
  const member = await canAccessConv(env, convId, userId);
  if (!member) return { error: "Non autorisé." };
  const row = await env.DB.prepare(`SELECT body FROM drafts WHERE user_id = ? AND conv_id = ?`).bind(userId, convId).first();
  return { draft: row ? { body: String(row.body) } : null };
}