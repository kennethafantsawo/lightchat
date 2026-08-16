import { createUser, createSession, getUserBySession, publicUser, verifyPassword } from "./auth";
import { searchUser, sendFriendRequest, respondFriendRequest, dmId, myFriends, pendingInvites } from "./friends";
import { ChatRoom } from "./ChatRoom";
import { fetchMessages, fetchMessagesSince, markMessagesDelivered } from "./db";
import { sendMessage, myConversations, canAccessConv } from "./messages";
import { uploadMedia, readMedia, canAccessMedia, purgeExpired } from "./media";
import { createGroup, addGroupMember, removeGroupMember, groupInfo } from "./groups";
import type { User } from "./types";

export interface Env {
  DB: D1Database;
  MEDIA: R2Bucket;
  CHAT_ROOM: DurableObjectNamespace;
}

async function json(data: unknown, status = 200): Promise<Response> {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });
}

async function readJson(req: Request): Promise<any> {
  try { return await req.json(); } catch { return {}; }
}

function getToken(req: Request): string {
  const h = req.headers.get("authorization") || "";
  return h.replace("Bearer ", "").trim();
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;
    const token = getToken(req);

    if (path === "/api/auth/register" && req.method === "POST") {
      const b = await readJson(req);
      const res = await createUser(env, b);
      if (res.error || !res.user) return json({ error: res.error }, 400);
      const session = await createSession(env, res.user.id);
      return json({ token: session, user: publicUser(res.user, true) });
    }

    if (path === "/api/auth/login" && req.method === "POST") {
      const b = await readJson(req);
      const row = await env.DB.prepare(`SELECT * FROM users WHERE username = ?`).bind(String(b.username ?? "").toLowerCase().trim()).first();
      if (!row || !verifyPassword(row as unknown as User, String(b.password ?? ""))) {
        return json({ error: "Pseudo ou mot de passe incorrect." }, 401);
      }
      const session = await createSession(env, (row as unknown as User).id);
      return json({ token: session, user: publicUser(row as unknown as User, true) });
    }

    if (path === "/api/me" && req.method === "GET") {
      const user = await getUserBySession(env, token);
      if (!user) return json({ error: "Non autorisé." }, 401);
      return json({ user: publicUser(user, true) });
    }

    if (path === "/api/ws" && req.method === "GET") {
      const id = env.CHAT_ROOM.idFromName("global");
      const stub = env.CHAT_ROOM.get(id);
      // Forward the original upgrade request so the DO can answer with a WebSocket;
      // only the path is rewritten to the DO's internal endpoint.
      const doUrl = new URL(req.url);
      doUrl.pathname = "/-/connect";
      doUrl.search = "";
      return stub.fetch(new Request(doUrl, req));
    }

    const user = await getUserBySession(env, token);
    if (!user) return json({ error: "Non autorisé." }, 401);

    if (path === "/api/users/search" && req.method === "GET") {
      const q = url.searchParams.get("q") || "";
      return json({ results: await searchUser(env, user.id, q) });
    }

    if (path === "/api/friends/request" && req.method === "POST") {
      const b = await readJson(req);
      const res = await sendFriendRequest(env, user.id, b.username);
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }

    if (path === "/api/friends/respond" && req.method === "POST") {
      const b = await readJson(req);
      const res = await respondFriendRequest(env, user.id, b.user_id, Boolean(b.accept));
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }

    if (path === "/api/friends" && req.method === "GET") {
      return json({ friends: await myFriends(env, user.id) });
    }

    if (path === "/api/friends/pending" && req.method === "GET") {
      return json({ pending: await pendingInvites(env, user.id) });
    }

    if (path === "/api/send" && req.method === "POST") {
      const b = await readJson(req);
      const res = await sendMessage(env, user.id, b);
      if (res.error) return json({ error: res.error }, 403);
      return json(res);
    }

    if (path === "/api/conversations" && req.method === "GET") {
      return json({ conversations: await myConversations(env, user.id) });
    }

    if (path === "/api/messages" && req.method === "GET") {
      const convId = url.searchParams.get("conv_id") || "";
      const beforeParam = url.searchParams.get("before");
      const since = Number(url.searchParams.get("since") || 0);
      const can = await canAccessConv(env, convId, user.id);
      if (!can) return json({ error: "Accès refusé." }, 403);
      if (since > 0) {
        const list = await fetchMessagesSince(env, convId, since);
        await markMessagesDelivered(env, convId, Date.now(), user.id);
        return json({ messages: list });
      }
      const list = await fetchMessages(env, convId, beforeParam ? Number(beforeParam) : null);
      await markMessagesDelivered(env, convId, Date.now(), user.id);
      return json({ messages: list.reverse() });
    }

    if (path === "/api/upload" && req.method === "POST") {
      const contentType = req.headers.get("content-type") || "application/octet-stream";
      const res = await uploadMedia(env, user.id, await req.arrayBuffer(), contentType, url.searchParams.get("filename") || undefined);
      if (res.error) return json({ error: res.error }, 400);
      // la collection de la référence médias dans le message : c'est l'app qui appelle /api/send juste après, avec media_key
      return json(res);
    }

    if (path === "/api/media" && req.method === "GET") {
      const key = url.searchParams.get("key") || "";
      const can = await canAccessMedia(env, key, user.id);
      if (!can) return json({ error: "Accès refusé." }, 403);
      const obj = await readMedia(env, key);
      if (!obj) return json({ error: "Média expiré ou introuvable." }, 410);
      const headers = new Headers(obj.httpMetadata?.contentType ? { "content-type": obj.httpMetadata.contentType } : {});
      headers.set("cache-control", "private, max-age=3600");
      headers.set("x-content-type-options", "nosniff");
      return new Response(obj.body, { headers });
    }

    if (path === "/api/groups" && req.method === "POST") {
      const b = await readJson(req);
      const res = await createGroup(env, user.id, b.name || "", Array.isArray(b.member_ids) ? b.member_ids : []);
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }

    if (path === "/api/groups" && req.method === "GET") {
      const convId = url.searchParams.get("conv_id") || "";
      const info = await groupInfo(env, user.id, convId);
      if (!info) return json({ error: "Groupe introuvable." }, 404);
      return json({ group: info });
    }

    if (path === "/api/groups/member" && req.method === "POST") {
      const b = await readJson(req);
      const res = await addGroupMember(env, String(b.group_id ?? ""), user.id, String(b.user_id ?? ""));
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }

    if (path === "/api/groups/member" && req.method === "DELETE") {
      const b = await readJson(req);
      const res = await removeGroupMember(env, String(b.group_id ?? ""), user.id, String(b.user_id ?? ""));
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }

    return json({ error: "Not found" }, 404);
  },

  async scheduled(_event: ScheduledEvent, env: Env, _ctx: ExecutionContext): Promise<void> {
    const deleted = await purgeExpired(env);
    console.log(`[cron] media purgés : ${deleted}`);
  },
};

export { ChatRoom };

