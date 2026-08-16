import type { Env } from "./index";

export async function searchUser(env: Env, meId: string, q: string) {
  const rows = await env.DB.prepare(
    `SELECT id, username, first_name, last_name, color, avatar_url FROM users WHERE username LIKE ? LIMIT 20`
  ).bind(`%${q.toLowerCase()}%`).all();
  const me = rows.results as any[];
  return me.filter(u => u.id !== meId);
}

export async function sendFriendRequest(env: Env, meId: string, targetUsername: string) {
  const target = await env.DB.prepare(`SELECT * FROM users WHERE username = ?`).bind(targetUsername.trim().toLowerCase()).first();
  if (!target) return { error: "Pseudo introuvable." };
  if (target.id === meId) return { error: "C'est toi !" };
  const existing = await env.DB.prepare(
    `SELECT * FROM friendships WHERE user_id = ? AND friend_id = ?`
  ).bind(meId, target.id).first();
  if (existing) return { error: "Demande déjà faite / déjà amis." };
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO friendships (user_id, friend_id, status, requester, created_at, updated_at) VALUES (?,?,?,?,?,?)`)
      .bind(meId, target.id, "pending", meId, now, now),
    env.DB.prepare(`INSERT INTO friendships (user_id, friend_id, status, requester, created_at, updated_at) VALUES (?,?,?,?,?,?)`)
      .bind(target.id, meId, "pending", meId, now, now),
  ]);
  return { ok: true, target: target.username };
}

export async function respondFriendRequest(env: Env, meId: string, requesterId: string, accept: boolean) {
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(`UPDATE friendships SET status = ?, updated_at = ? WHERE user_id = ? AND friend_id = ?`)
      .bind(accept ? "accepted" : "declined", now, meId, requesterId),
    env.DB.prepare(`UPDATE friendships SET status = ?, updated_at = ? WHERE user_id = ? AND friend_id = ?`)
      .bind(accept ? "accepted" : "declined", now, requesterId, meId),
  ]);
  if (!accept) return { ok: true };
  const convId = dmId(meId, requesterId);
  await env.DB.prepare(`INSERT OR IGNORE INTO conversations (id, kind, created_at) VALUES (?,?,?)`).bind(convId, "dm", now).run();
  await env.DB.prepare(`INSERT OR IGNORE INTO conversation_members (conv_id, user_id, role, created_at) VALUES (?,?,?,?)`).bind(convId, meId, "member", now).run();
  await env.DB.prepare(`INSERT OR IGNORE INTO conversation_members (conv_id, user_id, role, created_at) VALUES (?,?,?,?)`).bind(convId, requesterId, "member", now).run();
  return { ok: true, conv_id: convId };
}

export function dmId(a: string, b: string): string {
  return "dm:" + [a, b].sort().join(":");
}

export async function myFriends(env: Env, meId: string) {
  const rows = await env.DB.prepare(
    `SELECT u.id, u.username, u.first_name, u.last_name, u.color, u.avatar_url
     FROM friendships f JOIN users u ON u.id = f.friend_id
     WHERE f.user_id = ? AND f.status = 'accepted'`
  ).bind(meId).all();
  return rows.results;
}

export async function pendingInvites(env: Env, meId: string) {
  const rows = await env.DB.prepare(
    `SELECT u.id, u.username, u.first_name, u.last_name, u.color, u.avatar_url, f.requester
     FROM friendships f JOIN users u ON u.id = f.requester
     WHERE f.user_id = ? AND f.status = 'pending' AND f.requester != ?`
  ).bind(meId, meId).all();
  return rows.results;
}