import { makeId } from "./auth";
import type { Env } from "./index";

const TTL_MS = 7 * 24 * 3600 * 1000;

export async function uploadMedia(env: Env, userId: string, body: ArrayBuffer, contentType: string, filename?: string) {
  const size = body.byteLength;
  const MAX = 50 * 1024 * 1024;
  if (size === 0) return { error: "Fichier vide." };
  if (size > MAX) return { error: "Fichier > 50 Mo." };
  const ext = (filename && filename.includes(".")) ? filename.split(".").pop() : (contentType.split("/")[1] || "bin");
  const key = `${userId}/${Date.now()}_${makeId()}.${ext}`;
  await env.MEDIA.put(key, body, { httpMetadata: { contentType } });
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO media (key, owner_id, size, mime, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(key, userId, size, contentType, now, now + TTL_MS).run();
  return { key, size, mime: contentType };
}

export async function canAccessMedia(env: Env, key: string, userId: string): Promise<boolean> {
  const row = await env.DB.prepare(
    `SELECT 1 FROM media m
     WHERE m.key = ?
       AND (m.owner_id = ?
         OR EXISTS (
           SELECT 1 FROM messages msg
           JOIN conversation_members cm ON cm.conv_id = msg.conv_id
           WHERE msg.media_key = m.key AND cm.user_id = ?
         ))`
  ).bind(key, userId, userId).first();
  return Boolean(row);
}

export async function getMediaMeta(env: Env, key: string) {
  return await env.DB.prepare(`SELECT * FROM media WHERE key = ?`).bind(key).first();
}

export async function readMedia(env: Env, key: string): Promise<R2ObjectBody | null> {
  const meta = await getMediaMeta(env, key);
  if (!meta) return null;
  const expiresAt = Number((meta as { expires_at: number }).expires_at);
  if (expiresAt < Date.now()) return null;
  return await env.MEDIA.get(key);
}

export async function purgeExpired(env: Env) {
  const now = Date.now();
  const rows = await env.DB.prepare(`SELECT key FROM media WHERE expires_at < ?`).bind(now).all();
  const keys = (rows.results as any[]).map(r => r.key);
  if (keys.length > 0) {
    await env.MEDIA.delete(keys);
    await env.DB.prepare(`DELETE FROM media WHERE expires_at < ?`).bind(now).run();
  }
  return keys.length;
}