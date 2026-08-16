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

describe("groups regressions", () => {
  it("protège l'auteur et le retrait des membres par un non-auteur", async () => {
    const a = await api("POST", "/api/auth/register", { username: "grp_usr_a", password: "123456", first_name: "A", last_name: "G", age: 22, gender: "other" });
    const b = await api("POST", "/api/auth/register", { username: "grp_usr_b", password: "123456", first_name: "B", last_name: "G", age: 22, gender: "other" });
    const c = await api("POST", "/api/auth/register", { username: "grp_usr_c", password: "123456", first_name: "C", last_name: "G", age: 22, gender: "other" });
    const tA = a.json.token as string;
    const tB = b.json.token as string;
    const idA = a.json.user.id as string;
    const idB = b.json.user.id as string;
    const idC = c.json.user.id as string;

    const created = await api("POST", "/api/groups", { name: "Squad", member_ids: [idB] }, tA);
    expect(created.status).toBe(200);
    expect(String(created.json.conv_id)).toMatch(/^group:/);
    const groupId = created.json.group_id as string;
    const convId = created.json.conv_id as string;

    const selfRemoval = await api("DELETE", "/api/groups/member", { group_id: groupId, user_id: idA }, tA);
    expect(selfRemoval.status).toBe(400);
    expect(selfRemoval.json.error).toBe("Impossible de retirer l'auteur.");

    const addC = await api("POST", "/api/groups/member", { group_id: groupId, user_id: idC }, tA);
    expect(addC.status).toBe(200);

    const bRemovesC = await api("DELETE", "/api/groups/member", { group_id: groupId, user_id: idC }, tB);
    expect(bRemovesC.status).toBe(400);
    expect(bRemovesC.json.error).toBe("Non autorisé.");

    const aRemovesC = await api("DELETE", "/api/groups/member", { group_id: groupId, user_id: idC }, tA);
    expect(aRemovesC.status).toBe(200);

    const cRead = await api("GET", "/api/messages?conv_id=" + convId, undefined, c.json.token as string);
    expect(cRead.status).toBe(403);
    expect(cRead.json.error).toBe("Accès refusé.");
  });
});