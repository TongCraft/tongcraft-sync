import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import { once } from "node:events";
import WebSocket from "ws";
import { createService } from "../src/server.js";
import { validateSchematic } from "../src/validation.js";

const admin = "00000000000040008000000000000001",
  alice = "00000000000040008000000000000002",
  bob = "00000000000040008000000000000003";
function nbt() {
  const str = (s) => {
    const b = Buffer.from(s);
    const len = Buffer.alloc(2);
    len.writeUInt16BE(b.length);
    return Buffer.concat([len, b]);
  };
  const tag = (t, k, b) => Buffer.concat([Buffer.from([t]), str(k), b]);
  const int = (n) => {
    const b = Buffer.alloc(4);
    b.writeInt32BE(n);
    return b;
  };
  const compound = (tags) => Buffer.concat([...tags, Buffer.from([0])]);
  const vec = (key, n) =>
    tag(10, key, compound(["x", "y", "z"].map((k) => tag(3, k, int(n)))));
  const region = compound([
    vec("Size", 1),
    vec("Position", 0),
    tag(
      12,
      "BlockStates",
      Buffer.concat([int(1), Buffer.from([0, 0, 0, 0, 0, 0, 0, 1])]),
    ),
    tag(
      9,
      "BlockStatePalette",
      Buffer.concat([
        Buffer.from([10]),
        int(2),
        compound([tag(8, "Name", str("minecraft:air"))]),
        compound([tag(8, "Name", str("minecraft:stone"))]),
      ]),
    ),
  ]);
  return gzipSync(
    tag(
      10,
      "",
      compound([
        tag(3, "Version", int(7)),
        tag(10, "Regions", compound([tag(10, "Main", region)])),
      ]),
    ),
  );
}
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), "tongcraft-test-"));
  let now = Date.now();
  const sessions = new Map();
  let service = await createService({
    dataDir: dir,
    adminUuid: admin,
    clock: () => now,
    verifySession: async (name, id) => sessions.get(id) || { id: bob, name },
  });
  await new Promise((resolve) =>
    service.server.listen(0, "127.0.0.1", resolve),
  );
  const base = `http://127.0.0.1:${service.server.address().port}`;
  t.after(async () => {
    await service.close();
    await rm(dir, { recursive: true, force: true });
  });
  async function req(path, { method = "GET", token, data, raw } = {}) {
    const res = await fetch(base + path, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: raw ?? (data ? JSON.stringify(data) : undefined),
    });
    return { status: res.status, data: await res.json() };
  }
  async function login(id, name, invite) {
    const challenge = await req("/auth/challenge", {
      method: "POST",
      data: { uuid: id, name },
    });
    assert.equal(challenge.status, 200);
    sessions.set(challenge.data.serverId, { id, name });
    return req("/auth/complete", {
      method: "POST",
      data: { challengeId: challenge.data.challengeId, invite },
    });
  }
  async function invite(token, boundUuid) {
    return req("/invites", {
      method: "POST",
      token,
      data: { note: "Test invitation", boundUuid },
    });
  }
  return {
    req,
    login,
    invite,
    base,
    sessions,
    advance: (n) => (now += n),
    dir,
    stop: () => service.close(),
  };
}
const data = (hash) => ({
  name: "Farm",
  hash,
  dimension: "minecraft:overworld",
  origin: [10, 64, 20],
  rotation: "CLOCKWISE_90",
  mirror: "NONE",
  placements: [
    {
      name: "Main",
      placement: {
        pos: [0, 0, 0],
        rotation: "NONE",
        mirror: "NONE",
        enabled: true,
      },
    },
  ],
});

test("online proof must match the claimed UUID; challenges cannot be replayed", async (t) => {
  const f = await fixture(t);
  const c = (
    await f.req("/auth/challenge", {
      method: "POST",
      data: { uuid: admin, name: "Owner" },
    })
  ).data;
  const result = await f.req("/auth/complete", {
    method: "POST",
    data: { challengeId: c.challengeId },
  });
  assert.equal(result.status, 401);
  f.sessions.set(c.serverId, { id: admin, name: "Owner" });
  assert.equal(
    (
      await f.req("/auth/complete", {
        method: "POST",
        data: { challengeId: c.challengeId },
      })
    ).status,
    401,
  );
  assert.equal((await f.req("/state")).status, 401);
});

test("bound invites, expiration, revocation, and concurrent single-use redemption", async (t) => {
  const f = await fixture(t),
    owner = (await f.login(admin, "Owner")).data.token;
  const bound = (await f.invite(owner, alice)).data;
  assert.equal((await f.login(bob, "Bob", bound.code)).status, 403);
  assert.equal((await f.login(alice, "Alice", bound.code)).status, 200);
  assert.equal((await f.login(bob, "Bob", bound.code)).status, 403);
  const revoked = (await f.invite(owner)).data;
  await f.req(`/invites/${revoked.id}`, { method: "DELETE", token: owner });
  assert.equal((await f.login(bob, "Bob", revoked.code)).status, 403);
  const shared = (await f.invite(owner)).data;
  const ids = [bob, "00000000000040008000000000000004"];
  const results = await Promise.all(
    ids.map((id, i) => f.login(id, `Player${i}`, shared.code)),
  );
  assert.deepEqual(results.map((r) => r.status).sort(), [200, 403]);
  const exp = (await f.invite(owner)).data;
  f.advance(49 * 60 * 60 * 1000);
  assert.equal(
    (await f.login("00000000000040008000000000000005", "Late", exp.code))
      .status,
    403,
  );
});

test("library login tickets are member scoped, one use, and expire", async (t) => {
  const f = await fixture(t);
  const owner = (await f.login(admin, "Owner")).data.token;
  assert.equal((await f.req("/library/ticket", { method: "POST" })).status, 401);
  const invite = (await f.invite(owner)).data.code;
  const aliceToken = (await f.login(alice, "Alice", invite)).data.token;
  const issued = await f.req("/library/ticket", { method: "POST", token: aliceToken });
  assert.equal(issued.status, 200);
  const url = new URL(issued.data.url);
  assert.equal(url.origin, "https://library.weiuou.top");
  const ticket = new URLSearchParams(url.hash.slice(1)).get("ticket");
  assert.match(ticket, /^[A-Za-z0-9_-]{43}$/);
  const redeemed = await f.req("/library/redeem", { method: "POST", data: { ticket } });
  assert.deepEqual(redeemed.data, { uuid: alice, name: "Alice", role: "member" });
  assert.equal((await f.req("/library/redeem", { method: "POST", data: { ticket } })).status, 401);
  const expiring = (await f.req("/library/ticket", { method: "POST", token: aliceToken })).data.url;
  f.advance(301_000);
  assert.equal((await f.req("/library/redeem", {
    method: "POST", data: { ticket: new URLSearchParams(new URL(expiring).hash.slice(1)).get("ticket") },
  })).status, 401);
  const revocable = (await f.req("/library/ticket", { method: "POST", token: aliceToken })).data.url;
  assert.equal((await f.req(`/members/${alice}`, { method: "PATCH", token: owner, data: { disabled: true } })).status, 200);
  assert.equal((await f.req("/library/redeem", {
    method: "POST", data: { ticket: new URLSearchParams(new URL(revocable).hash.slice(1)).get("ticket") },
  })).status, 401);
});

test("shared placements, private aliases, owner authorization, optimistic conflicts and cached download", async (t) => {
  const f = await fixture(t),
    owner = (await f.login(admin, "Owner")).data.token;
  const a = (await f.login(alice, "Alice", (await f.invite(owner)).data.code))
    .data.token;
  const b = (await f.login(bob, "Bob", (await f.invite(owner)).data.code)).data
    .token;
  const upload = await f.req("/schematics", {
    method: "POST",
    token: a,
    raw: nbt(),
  });
  assert.equal(upload.status, 201);
  const created = await f.req("/placements", {
    method: "POST",
    token: a,
    data: data(upload.data.hash),
  });
  assert.equal(created.status, 201);
  const p = created.data;
  assert.equal(
    (
      await f.req(`/placements/${p.id}`, {
        method: "PUT",
        token: b,
        data: { ...p, name: "Hijacked" },
      })
    ).status,
    403,
  );
  assert.equal(
    (
      await f.req(`/preferences/${p.id}`, {
        method: "PUT",
        token: b,
        data: { hidden: true, alias: "My farm" },
      })
    ).status,
    200,
  );
  const bs = (await f.req("/state", { token: b })).data,
    as = (await f.req("/state", { token: a })).data;
  assert.equal(bs.preferences[p.id].alias, "My farm");
  assert.equal(as.preferences[p.id], undefined);
  assert.equal(bs.placements[0].name, "Farm");
  assert.equal(
    (
      await f.req(`/placements/${p.id}`, {
        method: "PUT",
        token: a,
        data: { ...p, origin: [30, 70, 20] },
      })
    ).status,
    200,
  );
  assert.equal(
    (await f.req(`/placements/${p.id}`, { method: "PUT", token: a, data: p }))
      .status,
    409,
  );
  const res = await fetch(`${f.base}/schematics/${upload.data.hash}`, {
    headers: { Authorization: `Bearer ${b}`, Range: "bytes=0-9" },
  });
  assert.equal(res.status, 206);
  assert.equal((await res.arrayBuffer()).byteLength, 10);
  assert.equal(
    (
      await f.req("/placements", {
        method: "POST",
        token: b,
        data: { ...data(upload.data.hash), origin: [100, 64, 100] },
      })
    ).status,
    201,
  );
});

test("websocket authorization, update notifications, and immediate membership revocation", async (t) => {
  const f = await fixture(t),
    owner = (await f.login(admin, "Owner")).data.token;
  const a = (await f.login(alice, "Alice", (await f.invite(owner)).data.code))
    .data.token;
  const ws = new WebSocket(f.base.replace("http", "ws") + "/events", {
    headers: { Authorization: `Bearer ${a}` },
  });
  const initial = once(ws, "message");
  await once(ws, "open");
  assert.equal(JSON.parse((await initial)[0]).type, "invalidate");
  const closed = once(ws, "close");
  assert.equal(
    (
      await f.req(`/members/${alice}`, {
        method: "PATCH",
        token: owner,
        data: { disabled: true },
      })
    ).status,
    200,
  );
  assert.equal((await closed)[0], 4001);
  assert.equal((await f.req("/state", { token: a })).status, 401);
  assert.equal((await f.login(alice, "Alice")).status, 403);
  await f.req(`/members/${alice}`, {
    method: "PATCH",
    token: owner,
    data: { disabled: false },
  });
  assert.equal((await f.login(alice, "Alice")).status, 200);
});

test("valid bounded NBT is accepted and malformed schematics are rejected", () => {
  assert.deepEqual(validateSchematic(nbt()), { regions: ["Main"], volume: 1 });
  assert.throws(() => validateSchematic(Buffer.from("not gzip")), /gzip/);
  assert.throws(
    () => validateSchematic(gzipSync(Buffer.from([10, 0, 0, 99, 0, 0, 0]))),
    /Invalid NBT/,
  );
});

test("deduplicated uploads grant both uploaders access without exposing unpublished blobs", async (t) => {
  const f = await fixture(t),
    owner = (await f.login(admin, "Owner")).data.token;
  const a = (await f.login(alice, "Alice", (await f.invite(owner)).data.code))
    .data.token;
  const b = (await f.login(bob, "Bob", (await f.invite(owner)).data.code)).data
    .token;
  const hash = (
    await f.req("/schematics", { method: "POST", token: a, raw: nbt() })
  ).data.hash;
  assert.equal(
    (await f.req("/placements", { method: "POST", token: b, data: data(hash) }))
      .status,
    403,
  );
  assert.equal(
    (
      await fetch(`${f.base}/schematics/${hash}`, {
        headers: { Authorization: `Bearer ${b}` },
      })
    ).status,
    403,
  );
  assert.equal(
    (await f.req("/schematics", { method: "POST", token: b, raw: nbt() })).data
      .hash,
    hash,
  );
  const p = (
    await f.req("/placements", { method: "POST", token: b, data: data(hash) })
  ).data;
  assert.equal(
    (
      await f.req(`/placements/${p.id}`, {
        method: "PUT",
        token: b,
        data: { ...p, name: "Global rename" },
      })
    ).status,
    400,
  );
  assert.equal(
    (
      await f.req(`/placements/${p.id}`, {
        method: "DELETE",
        token: a,
        data: { revision: 1 },
      })
    ).status,
    403,
  );
  assert.equal(
    (
      await f.req(`/placements/${p.id}`, {
        method: "DELETE",
        token: owner,
        data: { revision: 1 },
      })
    ).status,
    200,
  );
  assert.equal((await f.req("/state", { token: b })).data.placements.length, 0);
});

test("SQLite state and personal preferences survive service restarts", async (t) => {
  const f = await fixture(t),
    owner = (await f.login(admin, "Owner")).data.token;
  const hash = (
    await f.req("/schematics", { method: "POST", token: owner, raw: nbt() })
  ).data.hash;
  const p = (
    await f.req("/placements", {
      method: "POST",
      token: owner,
      data: data(hash),
    })
  ).data;
  await f.req(`/preferences/${p.id}`, {
    method: "PUT",
    token: owner,
    data: { hidden: true, alias: "Persistent" },
  });
  await f.stop();
  // Close the service and database, then start again from disk with no in-memory state.
  const second = await createService({ dataDir: f.dir, adminUuid: admin });
  await new Promise((resolve) => second.server.listen(0, "127.0.0.1", resolve));
  try {
    const res = await fetch(
      `http://127.0.0.1:${second.server.address().port}/state`,
      { headers: { Authorization: `Bearer ${owner}` } },
    );
    const s = await res.json();
    assert.equal(s.preferences[p.id].alias, "Persistent");
    assert.equal(s.placements[0].hash, hash);
  } finally {
    await second.close();
  }
});
