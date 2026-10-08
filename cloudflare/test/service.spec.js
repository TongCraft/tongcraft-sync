import { env, exports } from "cloudflare:workers";
import { evictDurableObject, runInDurableObject } from "cloudflare:test";
import { Buffer } from "node:buffer";
import { createHash } from "node:crypto";
import { gzipSync } from "node:zlib";
import { expect, test } from "vitest";

const admin = "288772a1e4a54741ac4caa7b4ceceddd";
const alice = "00000000000040008000000000000002";
const bob = "00000000000040008000000000000003";
const base = "https://sync.weiuou.top";
const hash = (value) => createHash("sha256").update(value).digest("hex");

async function request(
  path,
  { method = "GET", token, data, bytes, headers = {} } = {},
) {
  const response = await exports.default.fetch(
    new Request(base + path, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: bytes ?? (data ? JSON.stringify(data) : undefined),
    }),
  );
  return { status: response.status, data: await response.json() };
}
function nbt() {
  const str = (s) => {
    const b = Buffer.from(s),
      len = Buffer.alloc(2);
    len.writeUInt16BE(b.length);
    return Buffer.concat([len, b]);
  };
  const tag = (type, key, value) =>
    Buffer.concat([Buffer.from([type]), str(key), value]);
  const int = (value) => {
    const b = Buffer.alloc(4);
    b.writeInt32BE(value);
    return b;
  };
  const compound = (parts) => Buffer.concat([...parts, Buffer.from([0])]);
  const vec = (key, value) =>
    tag(
      10,
      key,
      compound(["x", "y", "z"].map((part) => tag(3, part, int(value)))),
    );
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
const placement = (schematicHash) => ({
  name: "Farm",
  hash: schematicHash,
  dimension: "minecraft:overworld",
  origin: [10, 64, 20],
  rotation: "NONE",
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

test("Cloudflare Sync preserves invite, ticket, schematic, placement, and revocation behavior", async () => {
  expect((await request("/health")).data).toEqual({ ok: true, protocol: 1 });
  expect((await request("/state")).status).toBe(401);
  const challenge = await request("/auth/challenge", {
    method: "POST",
    data: { uuid: admin, name: "Wei_uou" },
  });
  expect(challenge.status).toBe(200);
  const stub = env.ROOM.getByName("tongcraft");
  await runInDurableObject(stub, async (room) => {
    room.verifySession = async () => ({ id: bob, name: "Bob" });
    const response = await room.fetch(
      new Request(`${base}/auth/complete`, {
        method: "POST",
        headers: { "X-Sync-Client-IP": "test" },
        body: JSON.stringify({ challengeId: challenge.data.challengeId }),
      }),
    );
    expect(response.status).toBe(401);
    await response.text();
  });
  expect(
    (
      await request("/auth/complete", {
        method: "POST",
        data: { challengeId: challenge.data.challengeId },
      })
    ).status,
  ).toBe(401);

  // Seed a verified session inside the test object; production still requires Mojang hasJoined.
  const ownerToken = "o".repeat(43),
    aliceToken = "a".repeat(43),
    bobToken = "b".repeat(43);
  await runInDurableObject(stub, (room) => {
    for (const [uuid, name, role, token] of [
      [admin, "Wei_uou", "admin", ownerToken],
      [alice, "Alice", "member", aliceToken],
      [bob, "Bob", "member", bobToken],
    ]) {
      room.run(
        "INSERT INTO members(uuid,name,role) VALUES(?,?,?)",
        uuid,
        name,
        role,
      );
      room.run(
        "INSERT INTO sessions(hash,uuid,expires) VALUES(?,?,?)",
        hash(token),
        uuid,
        Date.now() + 60_000,
      );
    }
  });
  const invite = await request("/invites", {
    method: "POST",
    token: ownerToken,
    data: { note: "Welcome", boundUuid: alice },
  });
  expect(invite.status).toBe(201);
  expect(invite.data.code).toMatch(/^[A-Za-z0-9_-]{24}$/);
  expect(
    (await request("/state", { token: aliceToken })).data.member.role,
  ).toBe("member");

  const upgrade = await exports.default.fetch(
    new Request(`${base}/events`, {
      headers: { Authorization: `Bearer ${aliceToken}`, Upgrade: "websocket" },
    }),
  );
  expect(upgrade.status).toBe(101);
  const client = upgrade.webSocket;
  const initial = new Promise((resolve) =>
    client.addEventListener(
      "message",
      (event) => resolve(JSON.parse(event.data)),
      { once: true },
    ),
  );
  client.accept();
  expect(await initial).toEqual({ type: "invalidate" });
  await evictDurableObject(stub, { webSockets: "hibernate" });

  const ticket = await request("/library/ticket", {
    method: "POST",
    token: aliceToken,
  });
  expect(ticket.status).toBe(200);
  const url = new URL(ticket.data.url);
  expect(url.origin).toBe("https://library.weiuou.top");
  const code = new URLSearchParams(url.hash.slice(1)).get("ticket");
  expect(
    (
      await request("/library/redeem", {
        method: "POST",
        data: { ticket: code },
      })
    ).data,
  ).toEqual({ uuid: alice, name: "Alice", role: "member" });
  expect(
    (
      await request("/library/redeem", {
        method: "POST",
        data: { ticket: code },
      })
    ).status,
  ).toBe(401);

  const bytes = nbt(),
    schematicHash = hash(bytes);
  const upload = await request("/schematics", {
    method: "POST",
    token: aliceToken,
    bytes,
  });
  expect(upload).toEqual({ status: 201, data: { hash: schematicHash } });
  expect((await env.FILES.get(`sync/${schematicHash}.litematic`)).size).toBe(
    bytes.length,
  );
  const uploadAgain = await request("/schematics", {
    method: "POST",
    token: bobToken,
    bytes,
  });
  expect(uploadAgain.status).toBe(201);
  const published = await request("/placements", {
    method: "POST",
    token: aliceToken,
    data: placement(schematicHash),
  });
  expect(published.status).toBe(201);
  expect(
    (
      await request("/placements", {
        method: "POST",
        token: bobToken,
        data: placement(schematicHash),
      })
    ).status,
  ).toBe(201);
  const range = await exports.default.fetch(
    new Request(`${base}/schematics/${schematicHash}`, {
      headers: { Authorization: `Bearer ${bobToken}`, Range: "bytes=0-9" },
    }),
  );
  expect(range.status).toBe(206);
  expect((await range.arrayBuffer()).byteLength).toBe(10);
  const pref = await request(`/preferences/${published.data.id}`, {
    method: "PUT",
    token: bobToken,
    data: { hidden: true, alias: "My farm" },
  });
  expect(pref.status).toBe(200);
  expect(
    (await request("/state", { token: bobToken })).data.preferences[
      published.data.id
    ].alias,
  ).toBe("My farm");

  const outstanding = await request("/library/ticket", {
    method: "POST",
    token: aliceToken,
  });
  const closed = new Promise((resolve) =>
    client.addEventListener("close", (event) => resolve(event.code), {
      once: true,
    }),
  );
  expect(
    (
      await request(`/members/${alice}`, {
        method: "PATCH",
        token: ownerToken,
        data: { disabled: true },
      })
    ).status,
  ).toBe(200);
  expect(await closed).toBe(4001);
  expect((await request("/state", { token: aliceToken })).status).toBe(401);
  expect(
    (
      await request("/library/redeem", {
        method: "POST",
        data: {
          ticket: new URLSearchParams(
            new URL(outstanding.data.url).hash.slice(1),
          ).get("ticket"),
        },
      })
    ).status,
  ).toBe(401);
  await runInDurableObject(stub, (room) => {
    expect(room.usage("r2:bytes")).toBe(bytes.length);
  });
});
