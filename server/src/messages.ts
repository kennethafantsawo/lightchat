import { makeId } from "./auth";
import { insertMessage } from "./db";
import type { Env } from "./index";
import type { Message, MessageType } from "./types";

export function canAccessConv(env: Env, convId: string, userId: string): Promise<boolean> {
  return env.DB.prepare(`SELECT * FROM conversation_members WHERE conv_id = ? AND user_id = ?`)
    .bind(convId, userId).first().then(Boolean);
}

export async function sendMessage(env: Env, senderId: string, input: {
  conv_id: string; type?: string; body?: string; media_key?: string; mime?: string; duration_ms?: number;
}) {
  const can = await canAccessConv(env, input.conv_id, senderId);
  if (!can) return { error: "Conversation inaccessible." };
  const msg: Message = {
    id: makeId(),
    conv_id: input.conv_id,
    sender_id: senderId,
    type: (input.type as MessageType) ?? "text",
    body: input.body ?? null,
    media_key: input.media_key ?? null,
    mime: input.mime ?? null,
    duration_ms: input.duration_ms ?? null,
    status: "sent",
    created_at: Date.now(),
  };
  await insertMessage(env, msg);
  await pushToConv(env, msg.conv_id, { type: "message", message: msg });
  return { ok: true, message: msg };
}

async function pushToConv(env: Env, convId: string, payload: unknown) {
  const members = await env.DB.prepare(`SELECT user_id FROM conversation_members WHERE conv_id = ?`).bind(convId).all();
  const userIds = (members.results as any[]).map((m) => m.user_id);
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
            (SELECT m.type FROM messages m WHERE m.conv_id = c.id ORDER BY m.created_at DESC LIMIT 1) as last_type
     FROM conversation_members cm JOIN conversations c ON c.id = cm.conv_id
     WHERE cm.user_id = ?`
  ).bind(userId).all();
  return rows.results;
}