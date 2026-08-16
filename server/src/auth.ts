import { createHash, randomUUID, randomBytes } from "node:crypto";
import type { Env } from "./index";
import type { PublicUser, User } from "./types";

function toHex(bytes: Uint8Array): string {
  const out: string[] = [];
  for (const b of bytes) out.push(b.toString(16).padStart(2, "0"));
  return out.join("");
}

export function hashPassword(password: string, salt: string): string {
  return createHash("sha256").update(salt + ":" + password).digest("hex");
}

export function newSalt(): string {
  return toHex(randomBytes(16));
}

export function makeId(): string {
  return randomUUID();
}

export function publicUser(u: User, self = false): PublicUser {
  const base: PublicUser = {
    id: u.id,
    username: u.username,
    first_name: u.first_name,
    last_name: u.last_name,
    avatar_url: u.avatar_url,
    color: u.color,
    created_at: u.created_at,
  };
  if (self) {
    base.email = u.email ?? undefined;
    base.phone = u.phone ?? undefined;
    base.age = u.age;
    base.gender = u.gender;
    base.is_self = true;
  }
  return base;
}

export async function createUser(env: Env, input: {
  username: string; password: string; first_name: string; last_name: string;
  age: number; gender: string; email?: string; phone?: string;
}): Promise<{ error?: string; user?: User }> {
  const username = String(input.username ?? "").trim().toLowerCase();
  if (!/^[a-z0-9._]{3,20}$/.test(username)) {
    return { error: "Pseudo invalide : 3-20 caractères (lettres, chiffres, point, _ sans majuscules)." };
  }
  const password = String(input.password ?? "");
  if (password.length < 6) return { error: "Mot de passe trop court (6+)." };
  const age = Number(input.age);
  if (!Number.isInteger(age) || age < 13 || age > 120) return { error: "Âge invalide." };
  const gender = String(input.gender ?? "");
  if (!["male", "female", "other"].includes(gender)) return { error: "Sexe invalide." };
  const existing = await env.DB.prepare(`SELECT id FROM users WHERE username = ?`).bind(username).first();
  if (existing) return { error: "Ce pseudo est déjà pris." };
  const salt = newSalt();
  const id = makeId();
  const created_at = Date.now();
  const user: User = {
    id, username, password_hash: hashPassword(password, salt) + ":" + salt,
    first_name: input.first_name, last_name: input.last_name,
    age, gender,
    email: input.email ?? null, phone: input.phone ?? null,
    avatar_url: null, color: "indigo", created_at,
  };
  try {
    await env.DB.prepare(
      `INSERT INTO users (id, username, password_hash, first_name, last_name, age, gender, email, phone, avatar_url, color, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(user.id, user.username, user.password_hash, user.first_name, user.last_name,
      user.age, user.gender, user.email, user.phone, user.avatar_url, user.color, user.created_at).run();
  } catch {
    return { error: "Ce pseudo est déjà pris." };
  }
  return { user };
}

export function verifyPassword(user: User, password: string): boolean {
  const [hash, salt] = user.password_hash.split(":");
  return hashPassword(password, salt) === hash;
}

export async function createSession(env: Env, userId: string): Promise<string> {
  const token = toHex(randomBytes(32));
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