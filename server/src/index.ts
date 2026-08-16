import { createUser, createSession, getUserBySession, publicUser, verifyPassword } from "./auth";
import { searchUser, sendFriendRequest, respondFriendRequest, dmId, myFriends, pendingInvites } from "./friends";
import { ChatRoom } from "./ChatRoom";
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

    return json({ error: "Not found" }, 404);
  },
};

export { ChatRoom };

