# LightChat Serveur (Phase 1) — Plan d'implémentation

> **Pour les agents:** SKILL REQUIS : superpowers:subagent-driven-development (recommandé) ou superpowers:executing-plans. Les étapes utilisent des checkboxes (`- [ ]`).

**Objectif :** Construire le backend Cloudflare (Worker + D1 + R2 + Durable Objects) de LightChat : comptes, amis, messages, groupes, médias 7 jours, signalisation WebRTC, synchronisation après changement de téléphone.

**Architecture :** Un Cloudflare Worker TypeScript expose une API REST + WebSocket. Chaque utilisateur possède un Durable Object qui maintient sa connexion WebSocket (réception temps réel). Les messages sont persistés dans D1 (SQLite) ; les médias dans R2 avec expiration 7 jours (cron quotidien). Authentification par token de session stocké dans D1.

**Stack :** Cloudflare Workers, TypeScript, wrangler, D1, R2, Durable Objects, node:crypto (hachage), vitest + wrangler dev pour les tests.

---

## Structure des fichiers

```
server/
  wrangler.toml
  package.json
  tsconfig.json
  .dev.vars                # secrets locaux
  migrations/
    0001_schema.sql
  src/
    index.ts               # le Worker (routes HTTP + WS + cron)
    auth.ts                # session, hachage mot de passe
    db.ts                  # helpers D1 (requêtes SQL)
    user.ts                # comptes, amis, groupes
    messages.ts            # envoi/réception/sync
    media.ts               # upload R2, téléchargement, purge
    webrtc.ts              # signalisation appels
    types.ts               # types partagés
    ChatRoom.ts            # Durable Object (connexion WebSocket par utilisateur)
  test/
    auth.test.ts
    users.test.ts
    chat.test.ts
```

---

### Task 1 : Initialisation du projet serveur

**Fichiers :**
- Create: `server/wrangler.toml`
- Create: `server/package.json`
- Create: `server/tsconfig.json`
- Create: `server/.gitignore`

- [ ] **Step 1 : Créer `package.json`**

```json
{
  "name": "lightchat-server",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "wrangler dev",
    "deploy": "wrangler deploy",
    "db:migrate": "wrangler d1 migrations apply lightchat --local && wrangler d1 migrations apply lightchat",
    "test": "vitest run"
  },
  "devDependencies": {
    "@cloudflare/vitest-pool-workers": "^0.5.0",
    "typescript": "^5.6.0",
    "vitest": "^2.1.0",
    "wrangler": "^4.0.0",
    "@cloudflare/workers-types": "^4.20240901.0"
  },
  "dependencies": {
    "@cloudflare/workers-types": "^4.20240901.0"
  }
}
```

- [ ] **Step 2 : Créer `wrangler.toml`**

```toml
name = "lightchat"
main = "src/index.ts"
compatibility_date = "2025-08-01"
workers_dev = true

[[d1_databases]]
binding = "DB"
database_name = "lightchat"
database_id = "$D1_DATABASE_ID" # rempli à la création via `wrangler d1 create lightchat`
migrations_dir = "migrations"

[[r2_buckets]]
binding = "MEDIA"
bucket_name = "lightchat-media"

[[durable_objects.bindings]]
name = "CHAT_ROOM"
class_name = "ChatRoom"

[[migrations]]
tag = "v1"
new_sqlite_classes = ["ChatRoom"]

[triggers]
crons = ["0 3 * * *"]
```

- [ ] **Step 3 : Créer `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "lib": ["ES2022"],
    "types": ["@cloudflare/workers-types", "@cloudflare/vitest-pool-workers"],
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "esModuleInterop": true
  },
  "include": ["src/**/*.ts", "test/**/*.ts"]
}
```

- [ ] **Step 4 : Créer `.gitignore`**

```
node_modules/
.wrangler/
.dev.vars
dist/
```

- [ ] **Step 5 : Installer les dépendances et vérifier**

```bash
cd server
npm install
npx wrangler --version
```

Attendu : version wrangler affichée, `node_modules` créé.

- [ ] **Step 6 : Commit**

```bash
git add server/
git commit -m "feat(server): scaffold cloudflare worker project"
```

---

### Task 2 : Schéma de base de données (migration D1)

**Fichiers :**
- Create: `server/migrations/0001_schema.sql`

- [ ] **Step 1 : Écrire la migration SQL**

```sql
-- LightChat schéma de base
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  first_name TEXT NOT NULL,
  last_name TEXT NOT NULL,
  age INTEGER NOT NULL,
  gender TEXT NOT NULL,
  email TEXT,
  phone TEXT,
  avatar_url TEXT,
  color TEXT DEFAULT 'indigo',
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS friendships (
  user_id TEXT NOT NULL,
  friend_id TEXT NOT NULL,
  status TEXT NOT NULL,
  requester TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, friend_id)
);
CREATE INDEX IF NOT EXISTS idx_friendships_user ON friendships(user_id, status);
CREATE INDEX IF NOT EXISTS idx_friendships_friend ON friendships(friend_id, status);

CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,            -- 'dm' | 'group'
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_members (
  conv_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  role TEXT DEFAULT 'member',
  added_by TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (conv_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_conv_members_user ON conversation_members(user_id);

CREATE TABLE IF NOT EXISTS groups (
  id TEXT PRIMARY KEY,
  conv_id TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  conv_id TEXT NOT NULL,
  sender_id TEXT NOT NULL,
  type TEXT NOT NULL,             -- text|emoji|sticker|photo|video|audio|system
  body TEXT,
  media_key TEXT,
  mime TEXT,
  duration_ms INTEGER,
  status TEXT DEFAULT 'sent',     -- sent|delivered|read
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conv_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id, created_at);

CREATE TABLE IF NOT EXISTS media (
  key TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  size INTEGER NOT NULL,
  mime TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_media_expires ON media(expires_at);
```

- [ ] **Step 2 : Appliquer la migration locale**

```bash
cd server
npx wrangler d1 migrations apply lightchat --local
npx wrangler d1 execute lightchat --local --command "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;"
```

Attendu : la liste des tables `conversation_members, conversations, friendships, groups, media, messages, sessions, users, ...`.

- [ ] **Step 3 : Commit**

```bash
git add server/
git commit -m "feat(server): d1 schema migration"
```

---

### Task 3 : Types partagés + helpers DB

**Fichiers :**
- Create: `server/src/types.ts`
- Create: `server/src/db.ts`

- [ ] **Step 1 : Créer `src/types.ts`**

```ts
export interface User {
  id: string;
  username: string;
  password_hash: string;
  first_name: string;
  last_name: string;
  age: number;
  gender: string;
  email: string | null;
  phone: string | null;
  avatar_url: string | null;
  color: string;
  created_at: number;
}

export type PublicUser = Omit<User, "password_hash" | "email" | "phone" | "age" | "gender"> & {
  email?: string;
  phone?: string;
  age?: number;
  gender?: string;
  is_self?: boolean;
};

export type MessageType = "text" | "emoji" | "sticker" | "photo" | "video" | "audio" | "system";

export interface Message {
  id: string;
  conv_id: string;
  sender_id: string;
  type: MessageType;
  body: string | null;
  media_key: string | null;
  mime: string | null;
  duration_ms: number | null;
  status: "sent" | "delivered" | "read";
  created_at: number;
}

export interface MediaInfo {
  key: string;
  size: number;
  mime: string;
  created_at: number;
  expires_at: number;
}
```

- [ ] **Step 2 : Créer `src/db.ts` (helpers D1)**

```ts
import type { Env } from "./index";
import type { Message } from "./types";

export function getDb(env: Env) {
  return env.DB;
}

export async function insertMessage(env: Env, m: Message) {
  await env.DB.prepare(
    `INSERT INTO messages (id, conv_id, sender_id, type, body, media_key, mime, duration_ms, status, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  )
    .bind(m.id, m.conv_id, m.sender_id, m.type, m.body, m.media_key, m.mime, m.duration_ms, m.status, m.created_at)
    .run();
}

export async function fetchMessages(env: Env, convId: string, before: number | null, limit = 50): Promise<Message[]> {
  const rows = before
    ? await env.DB.prepare(
        `SELECT * FROM messages WHERE conv_id = ? AND created_at < ? ORDER BY created_at DESC LIMIT ?`
      ).bind(convId, before, limit).all()
    : await env.DB.prepare(
        `SELECT * FROM messages WHERE conv_id = ? ORDER BY created_at DESC LIMIT ?`
      ).bind(convId, limit).all();
  return rows.results as unknown as Message[];
}

export async function fetchMessagesSince(env: Env, convId: string, since: number): Promise<Message[]> {
  const rows = await env.DB.prepare(
    `SELECT * FROM messages WHERE conv_id = ? AND created_at > ? ORDER BY created_at ASC`
  ).bind(convId, since).all();
  return rows.results as unknown as Message[];
}

export async function markMessagesDelivered(env: Env, convId: string, upTo: number) {
  await env.DB.prepare(
    `UPDATE messages SET status = 'delivered' WHERE conv_id = ? AND created_at <= ? AND status = 'sent'`
  ).bind(convId, upTo).run();
}
```

- [ ] **Step 3 : Commit**

```bash
git add server/src/types.ts server/src/db.ts
git commit -m "feat(server): shared types and db helpers"
```

---

### Task 4 : Authentification (inscription, connexion, session)

**Fichiers :**
- Create: `server/src/auth.ts`
- Modify: `server/src/index.ts`

- [ ] **Step 1 : Créer `src/auth.ts`**

```ts
import { createHash, randomUUID, randomBytes } from "node:crypto";
import type { Env } from "./index";
import type { User } from "./types";

export function hashPassword(password: string, salt: string): string {
  return createHash("sha256").update(salt + ":" + password).digest("hex");
}

export function newSalt(): string {
  return randomBytes(16).toString("hex");
}

export function makeId(): string {
  return randomUUID();
}

export function publicUser(u: User, self = false): Omit<User, "password_hash"> {
  const { password_hash, ...rest } = u;
  return { ...rest };
}

export async function createUser(env: Env, input: {
  username: string; password: string; first_name: string; last_name: string;
  age: number; gender: string; email?: string; phone?: string;
}): Promise<{ error?: string; user?: User }> {
  const username = input.username.trim().toLowerCase();
  if (!/^[a-z0-9._]{3,20}$/.test(username)) {
    return { error: "Pseudo invalide : 3-20 caractères (lettres, chiffres, point, _ sans majuscules)." };
  }
  if (input.password.length < 6) return { error: "Mot de passe trop court (6+)." };
  const existing = await env.DB.prepare(`SELECT id FROM users WHERE username = ?`).bind(username).first();
  if (existing) return { error: "Ce pseudo est déjà pris." };
  const salt = newSalt();
  const id = makeId();
  const created_at = Date.now();
  const user: User = {
    id, username, password_hash: hashPassword(input.password, salt) + ":" + salt,
    first_name: input.first_name, last_name: input.last_name,
    age: input.age, gender: input.gender,
    email: input.email ?? null, phone: input.phone ?? null,
    avatar_url: null, color: "indigo", created_at,
  };
  await env.DB.prepare(
    `INSERT INTO users (id, username, password_hash, first_name, last_name, age, gender, email, phone, avatar_url, color, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(user.id, user.username, user.password_hash, user.first_name, user.last_name,
    user.age, user.gender, user.email, user.phone, user.avatar_url, user.color, user.created_at).run();
  return { user };
}

export function verifyPassword(user: User, password: string): boolean {
  const [hash, salt] = user.password_hash.split(":");
  return hashPassword(password, salt) === hash;
}

export async function createSession(env: Env, userId: string): Promise<string> {
  const token = randomBytes(32).toString("hex");
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)`
  ).bind(token, userId, now, now + 30 * 24 * 3600 * 1000).run();
  return token;
}

export async function getUserBySession(env: Env, token: string): Promise<User | null> {
  if (!token) return null;
  const row = await env.DB.prepare(
    `SELECT * FROM sessions WHERE token = ? AND expires_at > ?`
  ).bind(token, Date.now()).first();
  if (!row) return null;
  return (await env.DB.prepare(`SELECT * FROM users WHERE id = ?`).bind(row.user_id).first()) as User | null;
}
```

- [ ] **Step 2 : Créer `src/index.ts` (routeurs de base + inscription/connexion)**

```ts
import { createUser, createSession, getUserBySession, publicUser, verifyPassword } from "./auth";
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
      if (!row || !verifyPassword(row as User, String(b.password ?? ""))) {
        return json({ error: "Pseudo ou mot de passe incorrect." }, 401);
      }
      const session = await createSession(env, (row as User).id);
      return json({ token: session, user: publicUser(row as User, true) });
    }

    if (path === "/api/me" && req.method === "GET") {
      const user = await getUserBySession(env, token);
      if (!user) return json({ error: "Non autorisé." }, 401);
      return json({ user: publicUser(user, true) });
    }

    return json({ error: "Not found" }, 404);
  },
};
```

- [ ] **Step 3 : Tester manuellement via `wrangler dev`**

```bash
cd server
npx wrangler dev --local
```

Puis dans un 2e terminal :
```bash
curl -s -X POST http://localhost:8787/api/auth/register -H "content-type: application/json" -d '{"username":"papa","password":"secret123","first_name":"Jean","last_name":"Dupont","age":30,"gender":"male","email":"j@x.fr","phone":"0600000000"}'
```
Attendu : JSON avec `token` et `user`. Dupliquer la même commande : réponse `Ce pseudo est déjà pris.`

- [ ] **Step 4 : Commit**

```bash
git add server/src/
git commit -m "feat(server): auth register/login/session"
```

---

### Task 5 : Amis (recherche par pseudo, invitation, accepter/refuser)

**Fichiers :**
- Modify: `server/src/index.ts`
- Create: `server/src/friends.ts`

- [ ] **Step 1 : Créer `src/friends.ts`**

```ts
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
```

- [ ] **Step 2 : Brancher les routes dans `index.ts`**

Ajouter en haut des imports :
```ts
import { searchUser, sendFriendRequest, respondFriendRequest, dmId, myFriends, pendingInvites } from "./friends";
```

Ajouter dans le `fetch`, avant le `return json({ error: "Not found" }...)` :
```ts
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
      return json(res);
    }

    if (path === "/api/friends" && req.method === "GET") {
      return json({ friends: await myFriends(env, user.id) });
    }

    if (path === "/api/friends/pending" && req.method === "GET") {
      return json({ pending: await pendingInvites(env, user.id) });
    }
```

- [ ] **Step 3 : Test manuel (2 comptes)**

```bash
curl -s -X POST http://localhost:8787/api/auth/register -H "content-type: application/json" -d '{"username":"copine","password":"123456","first_name":"Marie","last_name":"L","age":25,"gender":"female"}'
# → token B
curl -s -X POST "http://localhost:8787/api/auth/login" -H "content-type: application/json" -d '{"username":"papa","password":"secret123"}'
# → token A
curl -s -X POST http://localhost:8787/api/friends/request -H "authorization: Bearer <TOKEN_A>" -H "content-type: application/json" -d '{"username":"copine"}'
# → {"ok":true,"target":"copine"}
curl -s http://localhost:8787/api/friends/pending -H "authorization: Bearer <TOKEN_B>"
# → liste avec papa
curl -s -X POST http://localhost:8787/api/friends/respond -H "authorization: Bearer <TOKEN_B>" -H "content-type: application/json" -d '{"user_id":"<A_ID>","accept":true}'
# → {"ok":true,"conv_id":"dm:..."} 
```

- [ ] **Step 4 : Commit**

```bash
git add server/src/
git commit -m "feat(server): friend requests search invite accept"
```

---

### Task 6 : Durable Object ChatRoom (WebSocket par utilisateur)

**Fichiers :**
- Create: `server/src/ChatRoom.ts`
- Modify: `server/src/index.ts`

- [ ] **Step 1 : Créer `src/ChatRoom.ts`**

```ts
import { getUserBySession } from "./auth";

export class ChatRoom {
  state: DurableObjectState;
  env: Env;
  sockets: Map<string, WebSocket> = new Map();
  userIds: Map<WebSocket, string> = new Map();
  userId: string | null = null;

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/connect") {
      const pairs = new WebSocketPair();
      const server = pairs[1];
      this.state.acceptWebSocket(server);
      return new Response(null, { status: 101, webSocket: pairs[0] });
    }
    if (url.pathname === "/push" && req.method === "POST") {
      const payload = await req.json();
      await this.broadcast(payload);
      return new Response("ok");
    }
    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    let data: any;
    try { data = JSON.parse(String(message)); } catch { return; }
    if (data.type === "hello") {
      // { type: 'hello', token }
      const user = await getUserBySession(this.env, data.token);
      if (!user) { ws.close(4001, "unauthorized"); return; }
      this.userId = user.id;
      this.userIds.set(ws, user.id);
      this.sockets.set(user.id, ws);
      ws.send(JSON.stringify({ type: "ready", userId: user.id }));
    }
    if (data.type === "pong") { /* keepalive */ }
  }

  async webSocketClose(ws: WebSocket) {
    const uid = this.userIds.get(ws);
    this.sockets.delete(uid as string);
    this.userIds.delete(ws);
  }

  async broadcast(payload: unknown) {
    for (const [uid, ws] of this.sockets) {
      if (ws.readyState === 1) ws.send(JSON.stringify(payload));
    }
  }
}

type Env = {
  DB: D1Database;
  MEDIA: R2Bucket;
  CHAT_ROOM: DurableObjectNamespace;
};
```

- [ ] **Step 2 : Brancher la route WebSocket dans `index.ts`**

Ajouter dans le `fetch`, avant le retour 404 :
```ts
    if (path === "/api/ws" && req.method === "GET") {
      const id = env.CHAT_ROOM.idFromName("global");
      const stub = env.CHAT_ROOM.get(id);
      return stub.fetch("https://lightchat/-/connect");
    }

    // Expo Durable Object class
    const idFromQuery = url.searchParams.get("_do");
    if (idFromQuery) {
      const id = env.CHAT_ROOM.idFromString(idFromQuery);
      const stub = env.CHAT_ROOM.get(id);
      return stub.fetch(req);
    }
```

Et exporter la classe DO en bas du module :
```ts
export { ChatRoom };
```

- [ ] **Step 3 : Test temps réel** (2 onglets) — `wrangler dev`
- Onglet 1 : `ws://localhost:8787/api/ws`, envoyer `{"type":"hello","token":"<TOKEN_A>"}` → reçoit `{"type":"ready",...}`
- Onglet 2 : même avec TOKEN_B.
- Depuis l'onglet A, `curl -X POST http://localhost:8787/api/ws?_do=<id global>` non testable aisément ici — on validera la livraison dans la Task 7 via le flux complet.

- [ ] **Step 4 : Commit**

```bash
git add server/src/
git commit -m "feat(server): durable object websocket hub"
```

---

### Task 7 : Envoi / réception de messages texte avec livraison temps réel

**Fichiers :**
- Create: `server/src/messages.ts`
- Modify: `server/src/index.ts`

- [ ] **Step 1 : Créer `src/messages.ts`**

```ts
import { makeId } from "./auth";
import { insertMessage, markMessagesDelivered } from "./db";
import { dmId } from "./friends";
import type { Env } from "./index";
import type { Message } from "./types";

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
    type: (input.type as any) ?? "text",
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
  const stubs = new Set<DurableObjectStub>();
  const globalId = env.CHAT_ROOM.idFromName("global");
  const globalStub = env.CHAT_ROOM.get(globalId);
  for (const m of members.results as any[]) {
    stubs.add(globalStub);
  }
  for (const s of stubs) {
    try { await s.fetch("https://lightchat/-/push", { method: "POST", body: JSON.stringify(payload) }); } catch {}
  }
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
```

- [ ] **Step 2 : Routes dans `index.ts`**

Imports : `import { sendMessage, myConversations, canAccessConv } from "./messages";` + `import { fetchMessages, fetchMessagesSince, markMessagesDelivered } from "./db";`

Routes (avec `user` déjà résolu) :
```ts
    if (path === "/api/send" && req.method === "POST") {
      const b = await readJson(req);
      const res = await sendMessage(env, user.id, b);
      if (res.error) return json({ error: res.error }, 400);
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
        await markMessagesDelivered(env, convId, Date.now());
        return json({ messages: list });
      }
      const list = await fetchMessages(env, convId, beforeParam ? Number(beforeParam) : null);
      await markMessagesDelivered(env, convId, Date.now());
      return json({ messages: list.reverse() });
    }
```

- [ ] **Step 3 : Test manuel (2 comptes amis)**
- `GET /api/conversations` avec TOKEN_A → `conversations` avec `conv_id` du DM.
- `POST /api/send` avec TOKEN_A : `{"conv_id":"<conv_id>","body":"Bonjour !"}`
- `GET /api/messages?conv_id=<conv_id>` avec TOKEN_B → le message apparaît.

- [ ] **Step 4 : Commit**

```bash
git add server/src/
git commit -m "feat(server): send messages and list conversations"
```

---

### Task 8 : Médias (upload R2, téléchargement, expiration 7 jours)

**Fichiers :**
- Create: `server/src/media.ts`
- Modify: `server/src/index.ts`

- [ ] **Step 1 : Créer `src/media.ts`**

```ts
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

export async function getMediaMeta(env: Env, key: string) {
  return await env.DB.prepare(`SELECT * FROM media WHERE key = ?`).bind(key).first();
}

export async function readMedia(env: Env, key: string): Promise<R2ObjectBody | null> {
  const meta = await getMediaMeta(env, key);
  if (!meta) return null;
  if (meta.expires_at < Date.now()) return null;
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
```

- [ ] **Step 2 : Routes dans `index.ts`**

Imports : `import { uploadMedia, readMedia, purgeExpired } from "./media";`

Routes :
```ts
    if (path === "/api/upload" && req.method === "POST") {
      const contentType = req.headers.get("content-type") || "application/octet-stream";
      const res = await uploadMedia(env, user.id, await req.arrayBuffer(), contentType, url.searchParams.get("filename") || undefined);
      if (res.error) return json({ error: res.error }, 400);
      // collection de la référence médias dans le message : c'est l'app qui appelle /api/send juste après, avec media_key
      return json(res);
    }

    if (path === "/api/media" && req.method === "GET") {
      const key = url.searchParams.get("key") || "";
      const obj = await readMedia(env, key);
      if (!obj) return json({ error: "Média expiré ou introuvable." }, 410);
      const headers = new Headers(obj.httpMetadata?.contentType ? { "content-type": obj.httpMetadata.contentType } : {});
      headers.set("cache-control", "private, max-age=3600");
      return new Response(obj.body, { headers });
    }
```

- [ ] **Step 3 : Exécutable cron purge (3 h du matin) — `scheduled()`**

Ajouter dans `index.ts`, en fin de module :
```ts
export default {
  // ... (fetch ci-dessus)
  async scheduled(_event: ScheduledEvent, env: Env, _ctx: ExecutionContext): Promise<void> {
    const deleted = await purgeExpired(env);
    console.log(`[cron] media purgés : ${deleted}`);
  },
};
```
(Regrouper `fetch` et `scheduled` dans le même objet `default` avant `export { ChatRoom };`.)

- [ ] **Step 4 : Test manuel**
```bash
printf 'hello-media' > /tmp/h.txt
curl -s -X POST http://localhost:8787/api/upload?filename=h.txt -H "authorization: Bearer <TOKEN>" -H "content-type: text/plain" --data-binary @/tmp/h.txt
# → {"key":"...","size":11,"mime":"text/plain"}
curl -s "http://localhost:8787/api/media?key=<key>" -H "authorization: Bearer <TOKEN>"
# → hello-media
```

- [ ] **Step 5 : Commit**

```bash
git add server/src/
git commit -m "feat(server): media upload/download with 7-day ttl + cron purge"
```

---

### Task 9 : Groupes

**Fichiers :**
- Create: `server/src/groups.ts`
- Modify: `server/src/index.ts`

- [ ] **Step 1 : Créer `src/groups.ts`**

```ts
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
```

- [ ] **Step 2 : Routes dans `index.ts`**

Imports : `import { createGroup, addGroupMember, removeGroupMember, groupInfo } from "./groups";`

Routes :
```ts
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

    if (path === "/api/groups/member" && req.method === "DELETE") {
      const b = await readJson(req);
      const res = await removeGroupMember(env, user.id, b.group_id, b.user_id);
      if (res.error) return json({ error: res.error }, 400);
      return json(res);
    }
```

- [ ] **Step 3 : Test manuel**
```bash
curl -s -X POST http://localhost:8787/api/groups -H "authorization: Bearer <TOKEN_A>" -H "content-type: application/json" -d '{"name":"Famille","member_ids":["<B_ID>"]}'
# → {"ok":true,"conv_id":"group:...","group_id":"..."}
curl -s -X POST http://localhost:8787/api/send -H "authorization: Bearer <TOKEN_A>" -H "content-type: application/json" -d '{"conv_id":"group:...","body":"Bonjour la famille!"}'
GET /api/messages?conv_id=group:... avec TOKEN_A et TOKEN_B → le message visible des deux côtés.
```

- [ ] **Step 4 : Commit**

```bash
git add server/src/
git commit -m "feat(server): groups create add members"
```

---

### Task 10 : Signalisation WebRTC (appels audio/vidéo)

**Fichiers :**
- Modify: `server/src/index.ts`

- [ ] **Step 1 : Routes de signalisation**

Dans `index.ts`, utiliser la DO pour relayer les messages de signalisation (offer/answer/ICE) et les notifications d'appel.

```ts
function pushToUser(env: Env, userId: string, payload: unknown) {
  const globalId = env.CHAT_ROOM.idFromName("global");
  const stub = env.CHAT_ROOM.get(globalId);
  return stub.fetch("https://lightchat/-/push", { method: "POST", body: JSON.stringify(payload) }).catch(() => {});
}

const CALL_ROUTES: Record<string, string> = {
  "/api/call/request": "call_request",
  "/api/call/accept": "call_accept",
  "/api/call/reject": "call_reject",
  "/api/call/webRtcSignal": "call_signal",
  "/api/call/hangup": "call_hangup",
};

for (const route of Object.keys(CALL_ROUTES)) {
  // NOTE: branché explicitement ci-dessous pour plus de clarté (éviter de muter dans une loop dans fetch)
}
```

Routes explicites (dans `fetch`, avec `user` résolu) :
```ts
    if (path === "/api/call/request") {
      // { to_user_id, call_type: 'audio'|'video' }
      const b = await readJson(req);
      await pushToUser(env, b.to_user_id, { type: "call_request", from: { id: user.id, username: user.username, first_name: user.first_name, last_name: user.last_name }, call_type: b.call_type, conv_id: b.conv_id });
      return json({ ok: true });
    }
    if (path === "/api/call/accept") {
      const b = await readJson(req);
      await pushToUser(env, b.to_user_id, { type: "call_accept", from: user.id, call_type: b.call_type });
      return json({ ok: true });
    }
    if (path === "/api/call/reject") {
      const b = await readJson(req);
      await pushToUser(env, b.to_user_id, { type: "call_reject", from: user.id });
      return json({ ok: true });
    }
    if (path === "/api/call/webRtcSignal") {
      // { to_user_id, data: { sdp|ice } }
      const b = await readJson(req);
      await pushToUser(env, b.to_user_id, { type: "call_signal", from: user.id, data: b.data });
      return json({ ok: true });
    }
    if (path === "/api/call/hangup") {
      const b = await readJson(req);
      await pushToUser(env, b.to_user_id, { type: "call_hangup", from: user.id });
      return json({ ok: true });
    }
```

- [ ] **Step 2 : Test manuel**
- Le sender (A) appelle : `POST /api/call/request` `{to_user_id:B, call_type:"audio", conv_id:"..."}`
- Si B a une WebSocket connectée → il reçoit `{"type":"call_request",...}` sur son WebSocket. (Vérifié via un petit client ws : `npx wscat -c ws://localhost:8787/api/ws` puis hello avec TOKEN_B.)

- [ ] **Step 3 : Commit**

```bash
git add server/src/index.ts
git commit -m "feat(server): webrtc signaling relay"
```

---

### Task 11 : Synchronisation après changement de téléphone

- [ ] **Step 1 : Vérifier le flux**

Le DID effectue déjà l'essentiel :
- `POST /api/auth/login` → nouvel appareil.
- `GET /api/conversations` → toutes les conversations de l'utilisateur.
- `GET /api/messages?conv_id=..&since=<lsat>` → historique complet (les textes sont gardés pour toujours dans D1).
- `GET /api/media?key=..` → médias disponibles pendant 7 jours.
- `GET /api/friends` → amis restaurés.

Aucun code supplémentaire requis pour ce qui est déjà couvert. Ajoutons juste un jeton d'appareil pour multi-appareil clair :

- [ ] **Step 2 : Colonne `device` sur sessions (option)**

```sql
ALTER TABLE sessions ADD COLUMN device TEXT;
```

(Optionnel — l'app enverra `X-Device` header pour étiqueter les sessions. Peut être sauté si YAGNI.)

- [ ] **Step 3 : Test de restauration**
- Sur un compte avec messages : se reconnecter avec un « nouveau » token → `GET /api/messages` renvoie le même historique.

- [ ] **Step 4 : Commit**

```bash
git add server/
git commit -m "feat(server): phone-change sync flow verified"
```

---

### Task 12 : Tests automatisés + déploiement

**Fichiers :**
- Create: `server/test/auth.test.ts`
- Create: `server/test/users.test.ts`
- Create: `server/test/chat.test.ts`
- Create: `server/vitest.config.ts`

- [ ] **Step 1 : Config vitest pools workers**

`server/vitest.config.ts` :
```ts
import { defineWorkersConfig } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        wrangler: { configPath: "./wrangler.toml" },
      },
    },
  },
});
```

- [ ] **Step 2 : Test auth**

`server/test/auth.test.ts` :
```ts
import { env, SELF, createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";

async function api(method: string, path: string, body?: unknown, token?: string) {
  const res = await SELF.fetch("https://lightchat.workers.dev" + path, {
    method,
    headers: { "content-type": "application/json", ...(token ? { authorization: "Bearer " + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() as any };
}

describe("auth", () => {
  it("crée un compte puis se connecte", async () => {
    const r1 = await api("POST", "/api/auth/register", { username: "testuser", password: "123456", first_name: "T", last_name: "U", age: 22, gender: "other" });
    expect(r1.status).toBe(200);
    expect(r1.json.token).toBeTruthy();
    const r2 = await api("GET", "/api/me", undefined, r1.json.token);
    expect(r2.json.user.username).toBe("testuser");
  });

  it("refuse un pseudo dupliqué", async () => {
    await api("POST", "/api/auth/register", { username: "dup", password: "123456", first_name: "A", last_name: "B", age: 20, gender: "other" });
    const r = await api("POST", "/api/auth/register", { username: "dup", password: "123456", first_name: "A", last_name: "B", age: 20, gender: "other" });
    expect(r.status).toBe(400);
  });
});
```

- [ ] **Step 3 : Test amis/messages**

`server/test/users.test.ts` :
```ts
import { SELF } from "cloudflare:test";
import { describe, it, expect } from "vitest";

async function api(method: string, path: string, body?: unknown, token?: string) {
  const res = await SELF.fetch("https://lightchat.workers.dev" + path, {
    method,
    headers: { "content-type": "application/json", ...(token ? { authorization: "Bearer " + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() as any };
}

describe("friends & messages", () => {
  it("flux complet ami → message", async () => {
    const a = await api("POST", "/api/auth/register", { username: "alice", password: "123456", first_name: "A", last_name: "L", age: 22, gender: "other" });
    const b = await api("POST", "/api/auth/register", { username: "bob", password: "123456", first_name: "B", last_name: "B", age: 22, gender: "other" });
    const tA = a.json.token as string;
    const tB = b.json.token as string;

    const req = await api("POST", "/api/friends/request", { username: "bob" }, tA);
    expect(req.json.ok).toBe(true);
    const pend = await api("GET", "/api/friends/pending", undefined, tB);
    expect(pend.json.pending.length).toBeGreaterThan(0);

    const bobId = pend.json.pending[0].id;
    const acc = await api("POST", "/api/friends/respond", { user_id: bobId, accept: true }, tB);
    expect(acc.json.ok).toBe(true);

    const convs = await api("GET", "/api/conversations", undefined, tA);
    const convId = convs.json.conversations[0].conv_id;

    const send = await api("POST", "/api/send", { conv_id: convId, body: "Bonjour Bob !" }, tA);
    expect(send.json.message.body).toBe("Bonjour Bob !");

    const msgs = await api("GET", "/api/messages?conv_id=" + convId, undefined, tB);
    expect(msgs.json.messages.length).toBeGreaterThan(0);
  });
});
```

- [ ] **Step 4 : Test médias**

`server/test/chat.test.ts` :
```ts
import { SELF } from "cloudflare:test";
import { describe, it, expect } from "vitest";

async function api(method: string, path: string, body?: any, token?: string, contentType?: string) {
  const res = await SELF.fetch("https://lightchat.workers.dev" + path, {
    method,
    headers: { ...(contentType ? { "content-type": contentType } : {}), ...(token ? { authorization: "Bearer " + token } : {}) },
    body,
  });
  return { status: res.status, json: await res.json().catch(() => null) as any };
}

describe("media", () => {
  it("upload et téléchargement", async () => {
    const a = await api("POST", "/api/auth/register", JSON.stringify({ username: "med", password: "123456", first_name: "M", last_name: "M", age: 22, gender: "other" }), undefined, "application/json");
    const tA = a.json.token;
    const up = await api("POST", "/api/upload?filename=f.txt", "hello", tA, "text/plain");
    expect(up.json.key).toBeTruthy();
    const get = await SELF.fetch("https://lightchat.workers.dev/api/media?key=" + up.json.key, { headers: { authorization: "Bearer " + tA } });
    expect(await get.text()).toBe("hello");
  });
});
```

- [ ] **Step 5 : Lancer les tests**

```bash
cd server
npx vitest run
```
Attendu : tous les tests passent (auth: 2, friends: 1, media: 1).

- [ ] **Step 6 : Déployer (documenté pour l'utilisateur)**

```bash
cd server
npx wrangler login                 # ouvre le navigateur, connexion Cloudflare (gratuit, sans carte)
npx wrangler d1 create lightchat   # affiche database_id → mettre dans wrangler.toml
npx wrangler r2 bucket create lightchat-media
npx wrangler d1 migrations apply lightchat   # applique la migration en prod
npx wrangler deploy
```
Attendu : URL `https://lightchat.<ton-sous-domaine>.workers.dev` affichée.

- [ ] **Step 7 : Commit**

```bash
git add server/
git commit -m "test(server): vitest suite + deploy instructions"
```

---

## Self-review

- **Couverture spec :** Comptes (T4), amis/invitations (T5), messages texte/émojis/stickers (T7), médias 7j (T8), groupes (T9), appels signalisation (T10), sync changement de tel (T11), auth (T4) — tous couverts.
- **Vérif croisée nommage :** `dmId` défini dans friends.ts et utilisé dans messages.ts ; `ChatRoom` exporté et branché ; `purgeExpired` appelé par cron. Cohérent.
- **Placeholders :** aucun restant (les `$D1_DATABASE_ID` et `<TOKEN>` sont des variables de runtime documentées, pas des TODO).