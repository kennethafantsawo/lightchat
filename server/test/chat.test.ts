import { SELF } from "cloudflare:test";
import { describe, it, expect } from "vitest";

async function api(method: string, path: string, body?: any, token?: string, contentType?: string) {
  const res = await SELF.fetch("https://lightchat.workers.dev" + path, {
    method,
    headers: { ...(contentType ? { "content-type": contentType } : {}), ...(token ? { authorization: "Bearer " + token } : {}) },
    body,
  });
  return { status: res.status, json: (await res.json().catch(() => null)) as any };
}

describe("media", () => {
  it("upload et téléchargement", async () => {
    const a = await api("POST", "/api/auth/register", JSON.stringify({ username: "med", password: "123456", first_name: "M", last_name: "M", age: 22, gender: "other" }), undefined, "application/json");
    const tA = a.json.token;
    const up = await api("POST", "/api/upload?filename=f.txt", "hello", tA, "text/plain");
    expect(up.json.key).toBeTruthy();
    const get = await SELF.fetch("https://lightchat.workers.dev/api/media?key=" + up.json.key, { headers: { authorization: "Bearer " + tA } });
    expect(get.status).toBe(200);
    expect(await get.text()).toBe("hello");
  });
});

async function register(username: string) {
  const res = await api("POST", "/api/auth/register", JSON.stringify({ username, password: "123456", first_name: "F", last_name: "N", age: 22, gender: "other" }), undefined, "application/json");
  if (res.status !== 200) throw new Error("register failed: " + JSON.stringify(res.json));
  return res.json as { token: string; user: any };
}

async function makeFriends(usernameA: string, usernameB: string) {
  const a = await register(usernameA);
  const b = await register(usernameB);
  await api("POST", "/api/friends/request", JSON.stringify({ username: usernameB }), a.token, "application/json");
  const pend = await api("GET", "/api/friends/pending", undefined, b.token);
  const requesterId = pend.json.pending[0].requester as string;
  const acc = await api("POST", "/api/friends/respond", JSON.stringify({ user_id: requesterId, accept: true }), b.token, "application/json");
  return { tokenA: a.token, tokenB: b.token, convId: acc.json.conv_id as string };
}

describe("messages regressions", () => {
  it("le statut delivered n'est marqué que chez les destinataires", async () => {
    const { tokenA, tokenB, convId } = await makeFriends("dst_usr_a", "dst_usr_b");
    const sent = await api("POST", "/api/send", JSON.stringify({ conv_id: convId, body: "hi" }), tokenA, "application/json");
    expect(sent.status).toBe(200);
    expect(sent.json.message.status).toBe("sent");
    const msgId = sent.json.message.id as string;

    const readAs = async (token: string) => {
      const res = await api("GET", "/api/messages?conv_id=" + convId, undefined, token);
      return res.json.messages.find((m: any) => m.id === msgId).status as string;
    };

    expect((await readAs(tokenA))).toBe("sent");
    expect(await readAs(tokenA)).toBe("sent");

    expect(await readAs(tokenB)).toBe("sent");
    expect(await readAs(tokenB)).toBe("delivered");
  });

  it("refuse l'envoi à un non-membre de la conversation", async () => {
    const { convId } = await makeFriends("snd_usr_a", "snd_usr_b");
    const c = await register("snd_usr_c");
    const res = await api("POST", "/api/send", JSON.stringify({ conv_id: convId, body: "x" }), c.token, "application/json");
    expect(res.status).toBe(403);
    expect(res.json.error).toBe("Conversation inaccessible.");
  });

  it("refuse la lecture des messages à un non-membre", async () => {
    const { convId } = await makeFriends("rdm_usr_a", "rdm_usr_b");
    const c = await register("rdm_usr_c");
    const res = await api("GET", "/api/messages?conv_id=" + convId, undefined, c.token);
    expect(res.status).toBe(403);
    expect(res.json.error).toBe("Accès refusé.");
  });
});

describe("media regressions", () => {
  it("le téléchargement d'un média exige l'appartenance à la conversation", async () => {
    const { tokenA, tokenB, convId } = await makeFriends("mdc_usr_a", "mdc_usr_b");
    const up = await api("POST", "/api/upload?filename=s.txt", "secret", tokenA, "text/plain");
    const key = up.json.key as string;
    const sent = await api("POST", "/api/send", JSON.stringify({ conv_id: convId, type: "photo", media_key: key, mime: "text/plain" }), tokenA, "application/json");
    expect(sent.status).toBe(200);

    const asB = await SELF.fetch("https://lightchat.workers.dev/api/media?key=" + key, { headers: { authorization: "Bearer " + tokenB } });
    expect(asB.status).toBe(200);
    expect(await asB.text()).toBe("secret");

    const c = await register("mdc_usr_c");
    const asC = await SELF.fetch("https://lightchat.workers.dev/api/media?key=" + key, { headers: { authorization: "Bearer " + c.token } });
    expect(asC.status).toBe(403);
    expect((await asC.json() as any).error).toBe("Accès refusé.");
  });
});