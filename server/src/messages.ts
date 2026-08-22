import { makeId } from "./auth";
import { insertMessage, getMessage, convMemberIds } from "./db";
import type { Env } from "./index";
import type { Message, MessageType } from "./types";

export function canAccessConv(env: Env, convId: string, userId: string): Promise<boolean> {
  return env.DB.prepare(`SELECT * FROM conversation_members WHERE conv_id = ? AND user_id = ?`)
    .bind(convId, userId).first().then(Boolean);
}

export const MESSAGE_TYPES: MessageType[] = ["text", "emoji", "sticker", "photo", "video", "audio", "system"];

export async function sendMessage(env: Env, senderId: string, input: {
  conv_id: string; type?: string; body?: string; media_key?: string; mime?: string; duration_ms?: number; reply_to_id?: string;
}) {
  const can = await canAccessConv(env, input.conv_id, senderId);
  if (!can) return { error: "Conversation inaccessible." };
  const type = (input.type ?? "text") as MessageType;
  if (!MESSAGE_TYPES.includes(type)) return { error: "Type de message invalide." };
  if (type === "text" && !String(input.body ?? "").trim()) return { error: "Message vide." };
  const replyToId = input.reply_to_id || null;
  if (replyToId) {
    const target = await getMessage(env, replyToId);
    if (!target || target.conv_id !== input.conv_id || target.deleted) {
      return { error: "Message cité introuvable." };
    }
  }
  const msg: Message = {
    id: makeId(),
    conv_id: input.conv_id,
    sender_id: senderId,
    type,
    body: input.body ?? null,
    media_key: input.media_key ?? null,
    mime: input.mime ?? null,
    duration_ms: input.duration_ms ?? null,
    reply_to_id: replyToId,
    edited: 0,
    deleted: 0,
    pinned: 0,
    status: "sent",
    created_at: Date.now(),
  };
  await insertMessage(env, msg);
  await pushToConv(env, msg.conv_id, { type: "message", message: msg });
  return { ok: true, message: msg };
}

export async function editMessage(env: Env, userId: string, messageId: string, newBody: string): Promise<{ error?: string; message?: Message }> {
  const text = String(newBody ?? "").trim();
  if (!text) return { error: "Message vide." };
  const msg = await getMessage(env, messageId) as unknown as Message | null;
  if (!msg) return { error: "Message introuvable." };
  if (msg.sender_id !== userId) return { error: "Seul l'auteur peut modifier." };
  if (msg.deleted) return { error: "Message supprimé." };
  if (msg.type !== "text" && msg.type !== "emoji") return { error: "Seuls les textes sont modifiables." };
  const edited = Date.now();
  await env.DB.prepare(`UPDATE messages SET body = ?, edited = ? WHERE id = ?`)
    .bind(text, edited, messageId).run();
  const updated = await getMessage(env, messageId) as unknown as Message | null;
  await pushToConv(env, msg.conv_id, { type: "message_edit", conv_id: msg.conv_id, message: updated });
  return { message: updated as Message };
}

export async function deleteMessage(env: Env, userId: string, messageId: string): Promise<{ error?: string; ok?: boolean }> {
  const msg = await getMessage(env, messageId) as unknown as Message | null;
  if (!msg) return { error: "Message introuvable." };
  if (msg.deleted) return { ok: true };
  const member = await canAccessConv(env, msg.conv_id, userId);
  if (!member) return { error: "Non autorisé." };
  await env.DB.prepare(`UPDATE messages SET deleted = 1 WHERE id = ?`).bind(messageId).run();
  await pushToConv(env, msg.conv_id, { type: "message_delete", conv_id: msg.conv_id, message_id: messageId });
  return { ok: true };
}

async function pushToConv(env: Env, convId: string, payload: unknown) {
  const userIds = await convMemberIds(env, convId);
  const globalId = env.CHAT_ROOM.idFromName("global");
  const globalStub = env.CHAT_ROOM.get(globalId);
  // Contrat DO (Task 6) : { userIds, payload } — le DO filtre les destinataires.
  try {
    await globalStub.fetch("https://lightchat/-/push", {
      method: "POST",
      body: JSON.stringify({ userIds, payload }),
    });
  } catch {}
}

export async function myConversations(env: Env, userId: string) {
  const rows = await env.DB.prepare(
    `SELECT cm.conv_id, c.kind, c.created_at,
            (SELECT m.body FROM messages m WHERE m.conv_id = c.id ORDER BY m.created_at DESC LIMIT 1) as last_body,
            (SELECT m.created_at FROM messages m WHERE m.conv_id = c.id ORDER BY m.created_at DESC LIMIT 1) as last_at,
            (SELECT m.type FROM messages m WHERE m.conv_id = c.id ORDER BY m.created_at DESC LIMIT 1) as last_type,
            (SELECT m.id FROM messages m WHERE m.conv_id = c.id AND m.pinned = 1 ORDER BY m.created_at DESC LIMIT 1) as pinned_id,
            (SELECT m.body FROM messages m WHERE m.conv_id = c.id AND m.pinned = 1 ORDER BY m.created_at DESC LIMIT 1) as pinned_body,
            (SELECT COUNT(*) FROM messages m2
               WHERE m2.conv_id = c.id
                 AND m2.created_at > COALESCE((SELECT up_to FROM last_read lr WHERE lr.conv_id = c.id AND lr.user_id = cm.user_id), 0)
                 AND m2.sender_id != cm.user_id
                 AND m2.deleted = 0) as unread
     FROM conversation_members cm JOIN conversations c ON c.id = cm.conv_id
     WHERE cm.user_id = ?
     ORDER BY COALESCE((SELECT m.created_at FROM messages m WHERE m.conv_id = c.id ORDER BY m.created_at DESC, m.id DESC LIMIT 1), c.created_at) DESC`
  ).bind(userId).all();
  return rows.results;
}