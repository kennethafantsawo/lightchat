import type { Env } from "./index";
import type { Message } from "./types";

export function getDb(env: Env) {
  return env.DB;
}

export async function insertMessage(env: Env, m: Message) {
  await env.DB.prepare(
    `INSERT INTO messages (id, conv_id, sender_id, type, body, media_key, mime, duration_ms, reply_to_id, edited, deleted, status, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  )
    .bind(m.id, m.conv_id, m.sender_id, m.type, m.body, m.media_key, m.mime, m.duration_ms,
      m.reply_to_id ?? null, m.edited ?? 0, m.deleted ?? 0, m.status, m.created_at)
    .run();
}

export async function fetchMessages(env: Env, convId: string, before: number | null, limit = 50): Promise<Message[]> {
  const rows = before
    ? await env.DB.prepare(
        `SELECT * FROM messages WHERE conv_id = ? AND created_at < ? ORDER BY created_at DESC, id DESC LIMIT ?`
      ).bind(convId, before, limit).all()
    : await env.DB.prepare(
        `SELECT * FROM messages WHERE conv_id = ? ORDER BY created_at DESC, id DESC LIMIT ?`
      ).bind(convId, limit).all();
  return rows.results as unknown as Message[];
}

export async function fetchMessagesSince(env: Env, convId: string, since: number): Promise<Message[]> {
  const rows = await env.DB.prepare(
    `SELECT * FROM messages WHERE conv_id = ? AND created_at > ? ORDER BY created_at ASC, id ASC`
  ).bind(convId, since).all();
  return rows.results as unknown as Message[];
}

export async function markMessagesDelivered(env: Env, convId: string, upTo: number, userId: string) {
  await env.DB.prepare(
    `UPDATE messages SET status = 'delivered' WHERE conv_id = ? AND created_at <= ? AND status = 'sent' AND sender_id != ?`
  ).bind(convId, upTo, userId).run();
}

export async function markMessagesRead(env: Env, convId: string, upTo: number, userId: string) {
  await env.DB.prepare(
    `UPDATE messages SET status = 'read' WHERE conv_id = ? AND created_at <= ? AND status IN ('sent','delivered') AND sender_id != ?`
  ).bind(convId, upTo, userId).run();
}

export async function getMessage(env: Env, messageId: string) {
  return await env.DB.prepare(`SELECT * FROM messages WHERE id = ?`).bind(messageId).first();
}

export async function convMemberIds(env: Env, convId: string): Promise<string[]> {
  const rows = await env.DB.prepare(`SELECT user_id FROM conversation_members WHERE conv_id = ?`).bind(convId).all();
  return (rows.results as any[]).map((m) => m.user_id);
}
