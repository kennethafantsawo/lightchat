import { createUser, createSession, getUserBySession, publicUser, verifyPassword, makeId } from "./auth";
import { searchUser, sendFriendRequest, respondFriendRequest, dmId, myFriends, pendingInvites } from "./friends";
import { ChatRoom } from "./ChatRoom";
import { fetchMessages, fetchMessagesSince, markMessagesDelivered, markMessagesRead, convMemberIds, getMessage } from "./db";
import { sendMessage, myConversations, canAccessConv, editMessage, deleteMessage, searchMessages, blockedSet } from "./messages";
import { uploadMedia, readMedia, canAccessMedia, purgeExpired } from "./media";
import { createGroup, addGroupMember, removeGroupMember, groupInfo } from "./groups";
import { togglePin, toggleReaction, reactionsForMessages, saveDraft, getDraft } from "./extras";
import type { User, Message } from "./types";

export interface Env {
  DB: D1Database;
  MEDIA: R2Bucket;
  CHAT_ROOM: DurableObjectNamespace;
  GIPHY_API_KEY?: string;
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

function pushToUser(env: Env, userId: string, payload: unknown) {
  const globalId = env.CHAT_ROOM.idFromName("global");
  const stub = env.CHAT_ROOM.get(globalId);
  return stub.fetch("https://lightchat/-/push", {
    method: "POST",
    body: JSON.stringify({ userIds: [userId], payload }),
  }).catch(() => {});
}

async function callAllowed(env: Env, fromId: string, toId: string): Promise<boolean> {
  if (fromId === toId) return false;
  const row = await env.DB.prepare(
    `SELECT * FROM friendships WHERE user_id = ? AND friend_id = ? AND status = 'accepted'`
  ).bind(fromId, toId).first();
  return Boolean(row);
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

    // F1 : heartbeat last_seen (throttle 30 s)
    const now = Date.now();
    if (!(user as any).last_seen || now - (user as any).last_seen > 30000) {
      await env.DB.prepare(`UPDATE users SET last_seen = ? WHERE id = ?`).bind(now, user.id).run();
    }

    if (path === "/api/privacy" && req.method === "GET") {
      const row = await env.DB.prepare(`SELECT privacy_settings FROM users WHERE id = ?`).bind(user.id).first();
      let settings: any = {};
      try { settings = JSON.parse((row as any)?.privacy_settings || "{}"); } catch {}
      return json({ settings });
    }

    if (path === "/api/privacy" && req.method === "PUT") {
      const b = await readJson(req);
      const allowed = ["hide_online", "hide_last_seen", "read_receipts", "ephemeral_default_ttl"];
      const clean: any = {};
      for (const k of allowed) if (k in (b || {})) clean[k] = (b as any)[k];
      await env.DB.prepare(`UPDATE users SET privacy_settings = ? WHERE id = ?`).bind(JSON.stringify(clean), user.id).run();
      return json({ ok: true, settings: clean });
    }

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

    if (path === "/api/block" && req.method === "POST") {
      const b = await readJson(req);
      if (!b?.user_id) return json({ error: "user_id manquant." }, 400);
      await env.DB.prepare(`INSERT OR IGNORE INTO user_blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)`)
        .bind(user.id, b.user_id, Date.now()).run();
      return json({ ok: true });
    }

    const blockDelMatch = path.match(/^\/api\/block\/([^/]+)$/);
    if (blockDelMatch && req.method === "DELETE") {
      const blockedId = decodeURIComponent(blockDelMatch[1]);
      await env.DB.prepare(`DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?`)
        .bind(user.id, blockedId).run();
      return json({ ok: true });
    }

    if (path === "/api/blocks" && req.method === "GET") {
      const rows = await env.DB.prepare(
        `SELECT ub.blocked_id, u.username, u.display_name, u.avatar_url
         FROM user_blocks ub JOIN users u ON u.id = ub.blocked_id
         WHERE ub.blocker_id = ? ORDER BY ub.created_at DESC`
      ).bind(user.id).all();
      return json({ blocks: rows.results });
    }

    if (path === "/api/report" && req.method === "POST") {
      const b = await readJson(req);
      if (!b?.message_id || !b?.conv_id || !b?.reason) return json({ error: "Champs requis manquants." }, 400);
      await env.DB.prepare(`INSERT INTO message_reports (id, reporter_id, message_id, conv_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)`)
        .bind(makeId(), user.id, b.message_id, b.conv_id, b.reason, Date.now()).run();
      return json({ ok: true });
    }

    if (path === "/api/messages" && req.method === "GET") {
      const convId = url.searchParams.get("conv_id") || "";
      const beforeParam = url.searchParams.get("before");
      const since = Number(url.searchParams.get("since") || 0);
      const can = await canAccessConv(env, convId, user.id);
      if (!can) return json({ error: "Accès refusé." }, 403);
      let list: Message[];
      if (since > 0) {
        list = await fetchMessagesSince(env, convId, since);
        await markMessagesDelivered(env, convId, Date.now(), user.id);
      } else {
        list = await fetchMessages(env, convId, beforeParam ? Number(beforeParam) : null);
        await markMessagesDelivered(env, convId, Date.now(), user.id);
        list = list.reverse();
      }
      const blocked = await blockedSet(env, user.id);
      if (blocked.size) {
        list = list.filter((m) => m.sender_id === user.id || m.type === "system" || !blocked.has(m.sender_id));
      }
      const ids = list.map((m) => m.id);
      const reactions = await reactionsForMessages(env, ids);
      const out = list.map((m) => ({ ...m, reactions: reactions.get(m.id) || [] }));
      return json({ messages: out });
    }

    if (path === "/api/messages/read" && req.method === "POST") {
      const b = await readJson(req);
      const convId = String(b?.conv_id ?? "");
      const can = await canAccessConv(env, convId, user.id);
      if (!can) return json({ error: "Accès refusé." }, 403);
      const upTo = Date.now();
      await markMessagesRead(env, convId, upTo, user.id);
      let readReceipts = true;
      try {
        const pr = await env.DB.prepare(`SELECT privacy_settings FROM users WHERE id = ?`).bind(user.id).first();
        readReceipts = JSON.parse((pr as any)?.privacy_settings || "{}")?.read_receipts !== false;
      } catch {}
      const ids = await convMemberIds(env, convId);
      for (const uid of ids) {
        if (uid !== user.id && readReceipts) {
          await pushToUser(env, uid, { type: "read", conv_id: convId, user_id: user.id, up_to: upTo });
        }
      }
      return json({ ok: true });
    }

    if (path === "/api/messages/edit" && req.method === "POST") {
      const b = await readJson(req);
      const res = await editMessage(env, user.id, String(b?.message_id ?? ""), String(b?.body ?? ""));
      if (res.error) return json({ error: res.error }, 400);
      return json({ ok: true, message: res.message });
    }

    if (path === "/api/messages/search" && req.method === "GET") {
      const q = url.searchParams.get("q") || "";
      if (q.trim().length < 2) return json({ error: "Requête trop courte." }, 400);
      const convId = url.searchParams.get("conv_id") || undefined;
      const limit = Math.min(50, Number(url.searchParams.get("limit") || 20));
      const results = await searchMessages(env, user.id, q, convId, limit);
      return json({ results });
    }

    if (path === "/api/messages/delete" && req.method === "POST") {
      const b = await readJson(req);
      const res = await deleteMessage(env, user.id, String(b?.message_id ?? ""));
      if (res.error) return json({ error: res.error }, 400);
      return json({ ok: true });
    }

    if (path === "/api/messages/pin" && req.method === "POST") {
      const b = await readJson(req);
      const convId = String(b?.conv_id ?? "");
      const can = await canAccessConv(env, convId, user.id);
      if (!can) return json({ error: "Accès refusé." }, 403);
      const res = await togglePin(env, convId, String(b?.message_id ?? ""), user.id, Boolean(b?.pinned));
      if (res.error) return json({ error: res.error }, 400);
      const ids = await convMemberIds(env, convId);
      for (const uid of ids) {
        if (uid !== user.id) {
          await pushToUser(env, uid, { type: "message_pin", conv_id: convId, message: res.message });
        }
      }
      return json({ ok: true, message: res.message });
    }

    if (path === "/api/messages/reaction" && req.method === "POST") {
      const b = await readJson(req);
      const res = await toggleReaction(env, String(b?.message_id ?? ""), user.id, String(b?.emoji ?? ""));
      if (res.error) return json({ error: res.error }, 400);
      const msg = await getMessage(env, String(b?.message_id ?? "")) as unknown as Message | null;
      if (msg) {
        const ids = await convMemberIds(env, msg.conv_id);
        for (const uid of ids) {
          if (uid !== user.id) {
            await pushToUser(env, uid, { type: "message_reaction", conv_id: msg.conv_id, message_id: msg.id, reactions: res.reactions });
          }
        }
      }
      return json({ ok: true, reactions: res.reactions });
    }

    if (path === "/api/drafts" && req.method === "POST") {
      const b = await readJson(req);
      const res = await saveDraft(env, user.id, String(b?.conv_id ?? ""), String(b?.body ?? ""));
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }

    if (path === "/api/drafts" && req.method === "GET") {
      const convId = url.searchParams.get("conv_id") || "";
      const res = await getDraft(env, user.id, convId);
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }

    if (path === "/api/typing" && req.method === "POST") {
      const b = await readJson(req);
      const convId = String(b?.conv_id ?? "");
      const can = await canAccessConv(env, convId, user.id);
      if (!can) return json({ error: "Accès refusé." }, 403);
      const ids = await convMemberIds(env, convId);
      for (const uid of ids) {
        if (uid !== user.id) {
          await pushToUser(env, uid, { type: "typing", conv_id: convId, user_id: user.id, typing: Boolean(b?.typing) });
        }
      }
      return json({ ok: true });
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

    if (path === "/api/call/request" && req.method === "POST") {
      const b = await readJson(req);
      const to = String(b?.to_user_id ?? "");
      if (!to) return json({ error: "Destinataire manquant." }, 400);
      if (!(await callAllowed(env, user.id, to))) return json({ error: "Non autorisé." }, 403);
      await pushToUser(env, to, {
        type: "call_request",
        from: { id: user.id, username: user.username, first_name: user.first_name, last_name: user.last_name },
        call_type: b.call_type,
        conv_id: b.conv_id,
      });
      return json({ ok: true });
    }

    if (path === "/api/call/accept" && req.method === "POST") {
      const b = await readJson(req);
      const to = String(b?.to_user_id ?? "");
      if (!to) return json({ error: "Destinataire manquant." }, 400);
      if (!(await callAllowed(env, user.id, to))) return json({ error: "Non autorisé." }, 403);
      await pushToUser(env, to, {
        type: "call_accept",
        from: { id: user.id, username: user.username, first_name: user.first_name, last_name: user.last_name },
        call_type: b.call_type,
      });
      return json({ ok: true });
    }

    if (path === "/api/call/reject" && req.method === "POST") {
      const b = await readJson(req);
      const to = String(b?.to_user_id ?? "");
      if (!to) return json({ error: "Destinataire manquant." }, 400);
      if (!(await callAllowed(env, user.id, to))) return json({ error: "Non autorisé." }, 403);
      await pushToUser(env, to, {
        type: "call_reject",
        from: { id: user.id, username: user.username, first_name: user.first_name, last_name: user.last_name },
      });
      return json({ ok: true });
    }

    if (path === "/api/call/webRtcSignal" && req.method === "POST") {
      const b = await readJson(req);
      const to = String(b?.to_user_id ?? "");
      if (!to) return json({ error: "Destinataire manquant." }, 400);
      if (!(await callAllowed(env, user.id, to))) return json({ error: "Non autorisé." }, 403);
      const raw = JSON.stringify(b.data ?? null);
      if (raw.length > 65536) return json({ error: "Données trop volumineuses." }, 400);
      await pushToUser(env, to, {
        type: "call_signal",
        from: { id: user.id, username: user.username, first_name: user.first_name, last_name: user.last_name },
        data: b.data,
      });
      return json({ ok: true });
    }

    if (path === "/api/call/hangup" && req.method === "POST") {
      const b = await readJson(req);
      const to = String(b?.to_user_id ?? "");
      if (!to) return json({ error: "Destinataire manquant." }, 400);
      if (!(await callAllowed(env, user.id, to))) return json({ error: "Non autorisé." }, 403);
      await pushToUser(env, to, {
        type: "call_hangup",
        from: { id: user.id, username: user.username, first_name: user.first_name, last_name: user.last_name },
      });
      return json({ ok: true });
    }

    if (path === "/api/users/lastseen" && req.method === "GET") {
      const uid = url.searchParams.get("user_id") || "";
      const row = await env.DB.prepare(`SELECT last_seen, privacy_settings FROM users WHERE id = ?`).bind(uid).first();
      let hideLastSeen = false;
      try { hideLastSeen = Boolean(JSON.parse((row as any)?.privacy_settings || "{}")?.hide_last_seen); } catch {}
      return json({ last_seen: hideLastSeen ? 0 : (row ? Number((row as any).last_seen) : 0) });
    }

    if (path === "/api/users/presence" && req.method === "GET") {
      const raw = url.searchParams.get("ids") || "";
      const ids = raw.split(",").map((s) => s.trim()).filter(Boolean).slice(0, 200);
      const onlineSet = new Set<string>();
      if (ids.length) {
        try {
          const gid = env.CHAT_ROOM.idFromName("global");
          const stub = env.CHAT_ROOM.get(gid);
          const resp = await stub.fetch("https://lightchat/-/online", {
            method: "POST",
            body: JSON.stringify({ userIds: ids }),
          });
          const j = (await resp.json()) as any;
          (j?.online ?? []).forEach((u: string) => onlineSet.add(u));
        } catch {}
      }
      const seen: Record<string, number> = {};
      const privacy: Record<string, any> = {};
      if (ids.length) {
        const rows = await env.DB.prepare(
          `SELECT id, last_seen, privacy_settings FROM users WHERE id IN (${ids.map(() => "?").join(",")})`
        ).bind(...ids).all();
        (rows.results as any[]).forEach((r) => {
          seen[r.id] = Number(r.last_seen);
          try { privacy[r.id] = JSON.parse(r.privacy_settings || "{}"); } catch { privacy[r.id] = {}; }
        });
      }
      const presence = ids.map((id) => {
        const p = privacy[id] || {};
        return {
          user_id: id,
          online: p.hide_online ? false : onlineSet.has(id),
          last_seen: p.hide_last_seen ? 0 : (seen[id] ?? 0),
        };
      });
      return json({ presence });
    }

    if (path === "/api/avatar" && req.method === "POST") {
      const ct = req.headers.get("content-type") || "";
      if (!ct.startsWith("image/")) return json({ error: "Format d'image attendu." }, 400);
      const buf = await req.arrayBuffer();
      if (buf.byteLength > 2 * 1024 * 1024) return json({ error: "Image trop lourde (2 Mo max)." }, 400);
      const key = "avatars/" + user.id;
      await env.MEDIA.put(key, buf, { httpMetadata: { contentType: ct }, customMetadata: { owner: user.id } });
      await env.DB.prepare(`UPDATE users SET avatar_url = ? WHERE id = ?`).bind(user.id, user.id).run();
      return json({ avatar_url: user.id });
    }

    if (path === "/api/avatar" && req.method === "GET") {
      const uid = url.searchParams.get("user_id") || user.id;
      const obj = await env.MEDIA.get("avatars/" + uid);
      if (!obj) return json({ error: "Avatar introuvable." }, 404);
      const headers = new Headers();
      if (obj.httpMetadata?.contentType) headers.set("content-type", obj.httpMetadata.contentType);
      headers.set("cache-control", "private, max-age=86400");
      return new Response(obj.body, { headers });
    }

    if (path === "/api/conversations/ephemeral" && req.method === "POST") {
      const b = await readJson(req);
      const convId = String(b?.conv_id ?? "");
      const can = await canAccessConv(env, convId, user.id);
      if (!can) return json({ error: "Accès refusé." }, 403);
      const ttl = Math.max(0, Math.min(604800, Number(b?.ttl ?? 0)));
      await env.DB.prepare(`UPDATE conversations SET ephemeral_ttl = ? WHERE id = ?`).bind(ttl, convId).run();
      const ids = await convMemberIds(env, convId);
      for (const uid of ids) {
        if (uid !== user.id) await pushToUser(env, uid, { type: "ephemeral", conv_id: convId, ttl });
      }
      return json({ ok: true, ttl });
    }

    if (path === "/api/stickers" && req.method === "GET") {
      const packs = await env.DB.prepare(`SELECT id, name, cover_url FROM sticker_packs ORDER BY name`).all();
      const items = await env.DB.prepare(`SELECT id, pack_id, emoji, image_url FROM sticker_pack_items`).all();
      const byPack = new Map();
      for (const it of (items.results as any[])) {
        if (!byPack.has(it.pack_id)) byPack.set(it.pack_id, []);
        byPack.get(it.pack_id).push(it);
      }
      const result = (packs.results as any[]).map((p) => ({ ...p, items: byPack.get(p.id) || [] }));
      return json({ packs: result });
    }

    if (path === "/api/gif/search" && req.method === "POST") {
      const b = await readJson(req);
      if (!env.GIPHY_API_KEY) return json({ gifs: [] });
      const q = (b.q || "").toString().trim();
      const limit = Math.min(Number(b.limit) || 24, 50);
      if (!q) return json({ gifs: [] });
      const giphyUrl = `https://api.giphy.com/v1/gifs/search?api_key=${encodeURIComponent(env.GIPHY_API_KEY)}&q=${encodeURIComponent(q)}&limit=${limit}&rating=pg-13`;
      try {
        const resp = await fetch(giphyUrl);
        const data = await resp.json() as any;
        const gifs = (data.data || []).map((g: any) => ({
          id: g.id,
          title: g.title,
          url: g.images?.original?.url,
          preview: g.images?.fixed_width?.url,
          width: Number(g.images?.original?.width) || 0,
          height: Number(g.images?.original?.height) || 0,
        }));
        return json({ gifs });
      } catch {
        return json({ gifs: [] });
      }
    }

    return json({ error: "Not found" }, 404);
  },

  async scheduled(_event: ScheduledEvent, env: Env, _ctx: ExecutionContext): Promise<void> {
    const deleted = await purgeExpired(env);
    console.log(`[cron] media purgés : ${deleted}`);
  },
};

export { ChatRoom };

