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