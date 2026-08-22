import { getUserBySession } from "./auth";

export class ChatRoom {
  state: DurableObjectState;
  env: Env;
  sockets: Map<string, WebSocket> = new Map(); // userId -> socket la plus récente
  conns: Map<string, number> = new Map();      // userId -> nombre de sockets ouvertes

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    // Note: stub.fetch URLs keep their path as-is, so /-/connect and /-/push arrive
    // with the /-/ prefix intact (both in local dev and on the Cloudflare runtime).
    if (url.pathname === "/connect" || url.pathname === "/-/connect") {
      const pairs = new WebSocketPair();
      const server = pairs[1];
      this.state.acceptWebSocket(server);
      return new Response(null, { status: 101, webSocket: pairs[0] });
    }
    if ((url.pathname === "/push" || url.pathname === "/-/push") && req.method === "POST") {
      // body JSON: { userIds: string[], payload: unknown }
      const data: any = await req.json();
      const userIds = new Set(data.userIds ?? []);
      for (const [uid, ws] of this.sockets) {
        if (userIds.has(uid) && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(data.payload));
        }
      }
      return new Response("ok");
    }
    if (url.pathname === "/online" || url.pathname === "/-/online") {
      let ids: string[] = [];
      try { ids = ((await req.json()) as any)?.userIds ?? []; } catch {}
      const online: string[] = [];
      for (const id of ids) if (this.conns.has(id)) online.push(id);
      return new Response(JSON.stringify({ online }), {
        headers: { "content-type": "application/json" },
      });
    }
    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    let data: any;
    try { data = JSON.parse(String(message)); } catch { return; }
    if (data.type === "hello") {
      const user = await getUserBySession(this.env, data.token);
      if (!user) { ws.close(4001, "unauthorized"); return; }
      const had = (this.conns.get(user.id) || 0) > 0;
      this.sockets.set(user.id, ws);
      this.conns.set(user.id, (this.conns.get(user.id) || 0) + 1);
      ws.send(JSON.stringify({ type: "ready", userId: user.id }));
      if (!had) await this.broadcastPresence(user.id, true);
    }
  }

  async webSocketClose(ws: WebSocket) {
    // Retire uniquement la socket fermée (pas les autres appareils du même utilisateur)
    let closedUid: string | null = null;
    for (const [uid, s] of this.sockets) {
      if (s === ws) { this.sockets.delete(uid); closedUid = uid; }
    }
    if (closedUid) {
      const left = (this.conns.get(closedUid) || 1) - 1;
      if (left <= 0) {
        this.conns.delete(closedUid);
        await this.broadcastPresence(closedUid, false);
      } else {
        this.conns.set(closedUid, left);
      }
    }
  }

  private async broadcastPresence(userId: string, online: boolean) {
    try {
      const rows = await this.env.DB.prepare(
        `SELECT CASE WHEN user_id = ? THEN friend_id ELSE user_id END AS fid
           FROM friendships WHERE (user_id = ? OR friend_id = ?) AND status = 'accepted'`
      ).bind(userId, userId, userId).all();
      const ids: string[] = (rows.results as any[]).map((r) => r.fid);
      let sendOnline = online;
      try {
        const pr = await this.env.DB.prepare(`SELECT privacy_settings FROM users WHERE id = ?`).bind(userId).first();
        const settings: any = JSON.parse((pr as any)?.privacy_settings || "{}");
        if (settings?.hide_online) sendOnline = false;
      } catch {}
      const globalId = this.env.CHAT_ROOM.idFromName("global");
      const stub = this.env.CHAT_ROOM.get(globalId);
      await stub.fetch("https://lightchat/-/push", {
        method: "POST",
        body: JSON.stringify({ userIds: ids, payload: { type: "presence", user_id: userId, online: sendOnline } }),
      }).catch(() => {});
    } catch {}
  }
}

type Env = {
  DB: D1Database;
  MEDIA: R2Bucket;
  CHAT_ROOM: DurableObjectNamespace;
};