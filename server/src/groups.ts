import { makeId } from "./auth";
import type { Env } from "./index";

export async function createGroup(env: Env, ownerId: string, name: string, memberIds: string[]) {
  if (!name.trim()) return { error: "Nom vide." };
  const all = new Set([ownerId, ...memberIds]);
  if (all.size < 2) return { error: "Ajoute au moins un ami." };
  const gid = makeId();
  const convId = "group:" + gid;
  const now = Date.now();
  const inserts = [
    env.DB.prepare(`INSERT INTO conversations (id, kind, created_at) VALUES (?,?,?)`).bind(convId, "group", now),
    env.DB.prepare(`INSERT INTO groups (id, conv_id, name, owner_id, created_at) VALUES (?,?,?,?,?)`).bind(gid, convId, name.trim(), ownerId, now),
  ];
  for (const uid of all) {
    inserts.push(env.DB.prepare(
      `INSERT OR IGNORE INTO conversation_members (conv_id, user_id, role, added_by, created_at) VALUES (?,?,?,?,?)`
    ).bind(convId, uid, uid === ownerId ? "owner" : "member", ownerId, now));
  }
  await env.DB.batch(inserts);
  return { ok: true, conv_id: convId, group_id: gid };
}

export async function addGroupMember(env: Env, groupId: string, adderId: string, newUserId: string) {
  const g = await env.DB.prepare(`SELECT * FROM groups WHERE id = ?`).bind(groupId).first();
  if (!g) return { error: "Groupe introuvable." };
  if (g.owner_id !== adderId) return { error: "Seul l'auteur peut ajouter." };
  await env.DB.prepare(
    `INSERT OR IGNORE INTO conversation_members (conv_id, user_id, role, added_by, created_at) VALUES (?,?,?,?,?)`
  ).bind(g.conv_id, newUserId, "member", adderId, Date.now()).run();
  return { ok: true, conv_id: g.conv_id };
}

export async function removeGroupMember(env: Env, groupId: string, actorId: string, targetUserId: string) {
  const g = await env.DB.prepare(`SELECT * FROM groups WHERE id = ?`).bind(groupId).first();
  if (!g) return { error: "Groupe introuvable." };
  if (g.owner_id !== actorId) return { error: "Non autorisé." };
  await env.DB.prepare(`DELETE FROM conversation_members WHERE conv_id = ? AND user_id = ?`).bind(g.conv_id, targetUserId).run();
  return { ok: true };
}

export async function groupInfo(env: Env, userId: string, convId: string) {
  const g = await env.DB.prepare(`SELECT g.*, cm2.role FROM groups g JOIN conversation_members cm2 ON cm2.conv_id = g.conv_id WHERE g.conv_id = ? AND cm2.user_id = ?`).bind(convId, userId).first();
  if (!g) return null;
  const members = await env.DB.prepare(
    `SELECT u.id, u.username, u.first_name, u.last_name, u.color, u.avatar_url, cm.role
     FROM conversation_members cm JOIN users u ON u.id = cm.user_id WHERE cm.conv_id = ?`
  ).bind(convId).all();
  return { ...g, members: members.results };
}
