import { SELF } from "cloudflare:test";
import { describe, it, expect } from "vitest";

async function api(method: string, path: string, body?: unknown, token?: string) {
  const res = await SELF.fetch("https://lightchat.workers.dev" + path, {
    method,
    headers: { "content-type": "application/json", ...(token ? { authorization: "Bearer " + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: (await res.json()) as any };
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