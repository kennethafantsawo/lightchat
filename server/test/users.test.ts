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

describe("friends & messages", () => {
  it("flux complet ami → message", async () => {
    const a = await api("POST", "/api/auth/register", { username: "alice", password: "123456", first_name: "A", last_name: "L", age: 22, gender: "other" });
    const b = await api("POST", "/api/auth/register", { username: "bob", password: "123456", first_name: "B", last_name: "B", age: 22, gender: "other" });
    const tA = a.json.token as string;
    const tB = b.json.token as string;

    const req = await api("POST", "/api/friends/request", { username: "bob" }, tA);
    expect(req.json.ok).toBe(true);
    const pend = await api("GET", "/api/friends/pending", undefined, tB);
    expect(pend.status).toBe(200);
    expect(pend.json.pending.length).toBeGreaterThan(0);

    const requesterId = pend.json.pending[0].requester;
    const acc = await api("POST", "/api/friends/respond", { user_id: requesterId, accept: true }, tB);
    expect(acc.json.ok).toBe(true);

    const convs = await api("GET", "/api/conversations", undefined, tA);
    expect(convs.json.conversations.length).toBeGreaterThan(0);
    const convId = convs.json.conversations[0].conv_id;

    const send = await api("POST", "/api/send", { conv_id: convId, body: "Bonjour Bob !" }, tA);
    expect(send.status).toBe(200);
    expect(send.json.message.body).toBe("Bonjour Bob !");

    const msgs = await api("GET", "/api/messages?conv_id=" + convId, undefined, tB);
    expect(msgs.status).toBe(200);
    expect(msgs.json.messages.length).toBeGreaterThan(0);
  });
});