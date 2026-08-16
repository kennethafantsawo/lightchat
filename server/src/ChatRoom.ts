import { getUserBySession } from "./auth";

export class ChatRoom {
  state: DurableObjectState;
  env: Env;
  sockets: Map<string, WebSocket> = new Map(); // userId -> socket la plus récente

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
    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    let data: any;
    try { data = JSON.parse(String(message)); } catch { return; }
    if (data.type === "hello") {
      const user = await getUserBySession(this.env, data.token);
      if (!user) { ws.close(4001, "unauthorized"); return; }
      this.sockets.set(user.id, ws);
      ws.send(JSON.stringify({ type: "ready", userId: user.id }));
    }
  }

  async webSocketClose(ws: WebSocket) {
    // Retire uniquement la socket fermée (pas les autres appareils du même utilisateur)
    for (const [uid, s] of this.sockets) {
      if (s === ws) this.sockets.delete(uid);
    }
  }
}

type Env = {
  DB: D1Database;
  MEDIA: R2Bucket;
  CHAT_ROOM: DurableObjectNamespace;
};