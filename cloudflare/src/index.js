import { DurableObject } from "cloudflare:workers";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { Buffer } from "node:buffer";
import {
  check,
  HttpError,
  placement,
  text,
  uuid,
  validateSchematic,
} from "../../service/src/validation.js";

const digest = (value) => createHash("sha256").update(value).digest("hex");
const secret = () => randomBytes(32).toString("base64url");
const json = (status, data) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
const fail = (error) => {
  if (!(error instanceof HttpError))
    console.error("Sync request failed", error);
  return json(error.status || 500, {
    error: error instanceof HttpError ? error.message : "Internal server error",
  });
};

export default {
  async fetch(request, env) {
    try {
      const headers = new Headers(request.headers);
      headers.set(
        "X-Sync-Client-IP",
        request.headers.get("CF-Connecting-IP") || "unknown",
      );
      const forwarded = new Request(request, { headers });
      return await env.ROOM.getByName("tongcraft").fetch(forwarded);
    } catch (error) {
      return fail(error);
    }
  },
};

export class SyncRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS members(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,role TEXT NOT NULL,disabled INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS invites(id TEXT PRIMARY KEY,hash TEXT UNIQUE NOT NULL,note TEXT NOT NULL,bound_uuid TEXT,expires INTEGER NOT NULL,used_by TEXT,revoked INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY,uuid TEXT NOT NULL REFERENCES members(uuid),expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS library_tickets(hash TEXT PRIMARY KEY,uuid TEXT NOT NULL REFERENCES members(uuid),expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS challenges(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,name TEXT NOT NULL,server_id TEXT NOT NULL,expires INTEGER NOT NULL,busy INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS blobs(hash TEXT PRIMARY KEY,size INTEGER NOT NULL,metadata TEXT NOT NULL,uploader TEXT NOT NULL REFERENCES members(uuid),ready INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS blob_access(uuid TEXT NOT NULL REFERENCES members(uuid),hash TEXT NOT NULL REFERENCES blobs(hash),PRIMARY KEY(uuid,hash));
      CREATE TABLE IF NOT EXISTS placements(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES members(uuid),revision INTEGER NOT NULL,updated INTEGER NOT NULL,body TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS preferences(uuid TEXT NOT NULL REFERENCES members(uuid),placement_id TEXT NOT NULL REFERENCES placements(id) ON DELETE CASCADE,body TEXT NOT NULL,PRIMARY KEY(uuid,placement_id));
      CREATE TABLE IF NOT EXISTS limits(key TEXT PRIMARY KEY,until INTEGER NOT NULL,count INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS usage(key TEXT PRIMARY KEY,value INTEGER NOT NULL);
    `);
    this.sql.exec("PRAGMA foreign_keys=ON");
  }

  get(query, ...args) {
    return this.sql.exec(query, ...args).toArray()[0];
  }
  all(query, ...args) {
    return this.sql.exec(query, ...args).toArray();
  }
  run(query, ...args) {
    return this.sql.exec(query, ...args).rowsWritten;
  }
  transaction(fn) {
    return this.ctx.storage.transactionSync(fn);
  }
  memberFor(request) {
    const token = request.headers
      .get("Authorization")
      ?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    check(token, 401, "Sign in required");
    const hash = digest(token);
    const member = this.get(
      "SELECT m.*,s.expires FROM sessions s JOIN members m ON m.uuid=s.uuid WHERE s.hash=?",
      hash,
    );
    check(
      member && !member.disabled && member.expires > Date.now(),
      401,
      "Session expired or revoked",
    );
    return { ...member, sessionHash: hash };
  }
  rate(request, group, max) {
    const key = `${group}:${request.headers.get("X-Sync-Client-IP")}`;
    const now = Date.now();
    if (!this.nextSweep || this.nextSweep <= now) {
      this.run("DELETE FROM limits WHERE until<=?", now);
      this.run("DELETE FROM sessions WHERE expires<=?", now);
      this.run("DELETE FROM library_tickets WHERE expires<=?", now);
      this.run("DELETE FROM challenges WHERE expires<=?", now);
      this.nextSweep = now + 5 * 60 * 1000;
    }
    const entry = this.get("SELECT until,count FROM limits WHERE key=?", key);
    if (!entry || entry.until <= now)
      this.run(
        "INSERT INTO limits(key,until,count) VALUES(?,?,1) ON CONFLICT(key) DO UPDATE SET until=excluded.until,count=1",
        key,
        now + 60000,
      );
    else {
      check(entry.count < max, 429, "Too many requests; retry in one minute");
      this.run("UPDATE limits SET count=count+1 WHERE key=?", key);
    }
  }
  async body(request, max = 256 * 1024, binary = false) {
    const len = Number(request.headers.get("Content-Length"));
    check(!Number.isFinite(len) || len <= max, 413, "Upload too large");
    // The upload limit is small enough to buffer once, but enforce it even for chunked bodies.
    let bytes;
    try {
      bytes = Buffer.from(await request.arrayBuffer());
    } catch {
      throw new HttpError(400, "Invalid request body");
    }
    check(bytes.length <= max, 413, "Upload too large");
    if (binary) return bytes;
    let obj;
    try {
      obj = JSON.parse(bytes.toString("utf8"));
    } catch {
      throw new HttpError(400, "Invalid JSON");
    }
    check(
      obj && !Array.isArray(obj) && typeof obj === "object",
      400,
      "JSON object required",
    );
    return obj;
  }
  notify(memberUuid = null) {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      const session = ws.deserializeAttachment();
      if (session && (!memberUuid || session.uuid === memberUuid)) {
        if (session.expires <= Date.now()) ws.close(4001, "Session expired");
        else ws.send(JSON.stringify({ type: "invalidate" }));
      }
    }
  }
  closeSessions(memberUuid, sessionHash = null) {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      const session = ws.deserializeAttachment();
      if (
        session?.uuid === memberUuid &&
        (!sessionHash || session.hash === sessionHash)
      )
        ws.close(4001, "Access revoked");
    }
  }
  async scheduleExpiry() {
    const expiries = this.ctx
      .getWebSockets()
      .map((ws) => ws.deserializeAttachment()?.expires)
      .filter(Boolean);
    if (expiries.length) await this.ctx.storage.setAlarm(Math.min(...expiries));
  }
  async alarm() {
    const now = Date.now();
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.deserializeAttachment()?.expires <= now)
        ws.close(4001, "Session expired");
    }
    this.run("DELETE FROM sessions WHERE expires<=?", now);
    this.run("DELETE FROM library_tickets WHERE expires<=?", now);
    this.run("DELETE FROM challenges WHERE expires<=?", now);
    this.run("DELETE FROM limits WHERE until<=?", now);
    await this.scheduleExpiry();
  }
  webSocketMessage(ws) {
    if (ws.deserializeAttachment()?.expires <= Date.now())
      ws.close(4001, "Session expired");
  }
  webSocketClose() {}
  webSocketError() {}
  publicPlacement(row) {
    const owner = this.get("SELECT name FROM members WHERE uuid=?", row.owner);
    return {
      ...JSON.parse(row.body),
      id: row.id,
      owner: row.owner,
      ownerName: owner?.name,
      revision: row.revision,
      updated: row.updated,
    };
  }
  editable(member, id) {
    const row = this.get("SELECT * FROM placements WHERE id=?", id);
    check(row, 404, "Placement not found");
    check(
      row.owner === member.uuid || member.role === "admin",
      403,
      "Only the owner or administrator can edit this placement",
    );
    return row;
  }
  validatePlacement(input, member) {
    const data = placement(input);
    const blob = this.get(
      "SELECT * FROM blobs WHERE hash=? AND ready=1",
      data.hash,
    );
    check(blob, 400, "Upload schematic first");
    check(
      blob.uploader === member.uuid ||
        this.get(
          "SELECT hash FROM blob_access WHERE uuid=? AND hash=?",
          member.uuid,
          data.hash,
        ) ||
        member.role === "admin" ||
        this.all("SELECT body FROM placements").some(
          (p) => JSON.parse(p.body).hash === data.hash,
        ),
      403,
      "Schematic is not shared",
    );
    const regions = JSON.parse(blob.metadata).regions;
    check(
      data.placements.every((r) => regions.includes(r.name)),
      400,
      "Unknown schematic subregion",
    );
    for (const region of data.placements) region.placement.name = region.name;
    return data;
  }
  usage(key) {
    return this.get("SELECT value FROM usage WHERE key=?", key)?.value || 0;
  }
  increment(key, amount = 1) {
    this.run(
      "INSERT INTO usage(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=value+excluded.value",
      key,
      amount,
    );
  }
  reserveR2(kind, bytes = 0) {
    const day = new Date().toISOString().slice(0, 10);
    const key = `r2:${kind}:${day}`;
    const limit = kind === "put" ? 500 : 10000;
    check(
      this.usage(key) < limit,
      503,
      "Daily schematic storage limit reached",
    );
    if (bytes)
      check(
        this.usage("r2:bytes") + bytes <= 4_000_000_000,
        507,
        "Schematic storage limit reached",
      );
    this.increment(key);
    if (bytes) this.increment("r2:bytes", bytes);
  }
  async verifySession(name, serverId) {
    const url = new URL(
      "https://sessionserver.mojang.com/session/minecraft/hasJoined",
    );
    url.searchParams.set("username", name);
    url.searchParams.set("serverId", serverId);
    let response;
    try {
      response = await fetch(url, {
        signal: AbortSignal.timeout(10000),
        // Workers reject redirect:"error"; manual keeps redirects from reaching another host.
        redirect: "manual",
      });
    } catch (error) {
      console.error("Mojang session fetch failed", error?.name, error?.message);
      throw new HttpError(503, "Minecraft authentication service unavailable");
    }
    check(
      response.status === 200,
      response.status >= 500 ? 503 : 401,
      "Minecraft session verification failed",
    );
    return response.json();
  }
  async fetch(request) {
    try {
      return await this.handle(request);
    } catch (error) {
      return fail(error);
    }
  }
  async handle(request) {
    const path = new URL(request.url).pathname;
    const method = request.method;
    this.rate(request, "general", 300);
    if (method === "GET" && path === "/health")
      return json(200, { ok: true, protocol: 1 });
    if (method === "GET" && path === "/events") {
      check(
        request.headers.get("Upgrade")?.toLowerCase() === "websocket",
        426,
        "WebSocket required",
      );
      this.rate(request, "ws", 20);
      const member = this.memberFor(request);
      check(
        this.ctx
          .getWebSockets()
          .filter((ws) => ws.deserializeAttachment()?.uuid === member.uuid)
          .length < 5,
        429,
        "Too many active devices",
      );
      const [client, server] = Object.values(new WebSocketPair());
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment({
        uuid: member.uuid,
        hash: member.sessionHash,
        expires: member.expires,
      });
      server.send(JSON.stringify({ type: "invalidate" }));
      await this.scheduleExpiry();
      return new Response(null, { status: 101, webSocket: client });
    }
    if (method === "POST" && path === "/library/redeem") {
      this.rate(request, "library", 60);
      const input = await this.body(request);
      check(
        typeof input.ticket === "string" &&
          /^[A-Za-z0-9_-]{43}$/.test(input.ticket),
        401,
        "Invalid library ticket",
      );
      const profile = this.transaction(() => {
        const row = this.get(
          "SELECT t.hash,t.expires,m.uuid,m.name,m.role,m.disabled FROM library_tickets t JOIN members m ON m.uuid=t.uuid WHERE t.hash=?",
          digest(input.ticket),
        );
        check(
          row && row.expires > Date.now() && !row.disabled,
          401,
          "Library ticket expired or used",
        );
        this.run("DELETE FROM library_tickets WHERE hash=?", row.hash);
        return { uuid: row.uuid, name: row.name, role: row.role };
      });
      return json(200, profile);
    }
    if (method === "POST" && path === "/auth/challenge") {
      this.rate(request, "auth", 20);
      const input = await this.body(request);
      const playerUuid = uuid(input.uuid),
        name = text(input.name, 16);
      check(/^[A-Za-z0-9_]{1,16}$/.test(name), 400, "Invalid Minecraft name");
      this.run("DELETE FROM challenges WHERE expires<=?", Date.now());
      check(
        this.get("SELECT count(*) AS n FROM challenges").n < 10000,
        503,
        "Too many pending authentications",
      );
      const id = secret(),
        serverId = randomBytes(20).toString("hex");
      this.run(
        "INSERT INTO challenges(id,uuid,name,server_id,expires) VALUES(?,?,?,?,?)",
        id,
        playerUuid,
        name,
        serverId,
        Date.now() + 120000,
      );
      return json(200, { challengeId: id, serverId, expiresIn: 120 });
    }
    if (method === "POST" && path === "/auth/complete") {
      this.rate(request, "auth", 20);
      const input = await this.body(request);
      const c = this.get(
        "SELECT * FROM challenges WHERE id=?",
        input.challengeId,
      );
      check(
        c && !c.busy && c.expires > Date.now(),
        401,
        "Challenge expired or already used",
      );
      this.run("UPDATE challenges SET busy=1 WHERE id=?", c.id);
      try {
        const profile = await this.verifySession(c.name, c.server_id);
        check(uuid(profile.id) === c.uuid, 401, "Minecraft profile mismatch");
        const name = text(profile.name, 16),
          token = secret(),
          expires = Date.now() + 12 * 60 * 60 * 1000;
        const member = this.transaction(() => {
          let existing = this.get("SELECT * FROM members WHERE uuid=?", c.uuid);
          check(!existing?.disabled, 403, "Membership disabled");
          if (!existing) {
            check(
              this.get("SELECT count(*) AS n FROM members").n < 2000,
              409,
              "Member quota reached",
            );
            let role = "member";
            if (c.uuid === uuid(this.env.ADMIN_UUID)) role = "admin";
            else {
              check(
                typeof input.invite === "string" && input.invite.trim(),
                403,
                "Invitation required",
              );
              const invite = this.get(
                "SELECT * FROM invites WHERE hash=?",
                digest(input.invite.trim()),
              );
              check(
                invite &&
                  !invite.revoked &&
                  !invite.used_by &&
                  invite.expires > Date.now(),
                403,
                "Invalid, expired, or used invitation",
              );
              check(
                !invite.bound_uuid || invite.bound_uuid === c.uuid,
                403,
                "Invitation belongs to another Minecraft account",
              );
              this.run(
                "UPDATE invites SET used_by=? WHERE id=?",
                c.uuid,
                invite.id,
              );
            }
            this.run(
              "INSERT INTO members(uuid,name,role) VALUES(?,?,?)",
              c.uuid,
              name,
              role,
            );
          } else
            this.run("UPDATE members SET name=? WHERE uuid=?", name, c.uuid);
          this.run(
            "INSERT INTO sessions(hash,uuid,expires) VALUES(?,?,?)",
            digest(token),
            c.uuid,
            expires,
          );
          return this.get(
            "SELECT uuid,name,role FROM members WHERE uuid=?",
            c.uuid,
          );
        });
        this.notify();
        return json(200, { token, expires, member });
      } finally {
        this.run("DELETE FROM challenges WHERE id=?", c.id);
      }
    }
    const member = this.memberFor(request);
    if (method === "POST" && path === "/library/ticket") {
      this.rate(request, "library", 10);
      const url = new URL(this.env.LIBRARY_URL);
      check(url.protocol === "https:", 500, "Invalid library URL");
      const ticket = secret();
      this.run(
        "INSERT INTO library_tickets(hash,uuid,expires) VALUES(?,?,?)",
        digest(ticket),
        member.uuid,
        Date.now() + 300000,
      );
      url.hash = `ticket=${ticket}`;
      return json(200, { url: url.toString(), expiresIn: 300 });
    }
    if (method === "POST" && path === "/auth/logout") {
      this.run("DELETE FROM sessions WHERE hash=?", member.sessionHash);
      this.closeSessions(member.uuid, member.sessionHash);
      return json(200, { ok: true });
    }
    if (method === "GET" && path === "/state") {
      const prefs = Object.fromEntries(
        this.all(
          "SELECT placement_id,body FROM preferences WHERE uuid=?",
          member.uuid,
        ).map((p) => [p.placement_id, JSON.parse(p.body)]),
      );
      return json(200, {
        protocol: 1,
        member: { uuid: member.uuid, name: member.name, role: member.role },
        placements: this.all(
          "SELECT * FROM placements ORDER BY updated DESC",
        ).map((p) => this.publicPlacement(p)),
        preferences: prefs,
        ...(member.role === "admin"
          ? {
              members: this.all(
                "SELECT uuid,name,role,disabled FROM members ORDER BY name",
              ),
              invites: this.all(
                "SELECT id,note,bound_uuid,expires,used_by,revoked FROM invites ORDER BY expires DESC",
              ),
            }
          : {}),
      });
    }
    if (method === "POST" && path === "/invites") {
      check(member.role === "admin", 403, "Administrator permission required");
      const input = await this.body(request),
        note = text(input.note),
        bound = input.boundUuid ? uuid(input.boundUuid) : null;
      const code = randomBytes(18).toString("base64url"),
        id = randomUUID(),
        expires = Date.now() + 48 * 60 * 60 * 1000;
      this.run(
        "DELETE FROM invites WHERE expires<?",
        Date.now() - 7 * 24 * 60 * 60 * 1000,
      );
      check(
        this.get("SELECT count(*) AS n FROM invites").n < 1000,
        409,
        "Invitation history quota reached",
      );
      this.run(
        "INSERT INTO invites(id,hash,note,bound_uuid,expires) VALUES(?,?,?,?,?)",
        id,
        digest(code),
        note,
        bound,
        expires,
      );
      this.notify();
      return json(201, { id, code, expires });
    }
    const invitation = path.match(/^\/invites\/([a-f0-9-]{36})$/);
    if (method === "DELETE" && invitation) {
      check(member.role === "admin", 403, "Administrator permission required");
      check(
        this.run("UPDATE invites SET revoked=1 WHERE id=?", invitation[1]),
        404,
        "Invitation not found",
      );
      this.notify();
      return json(200, { ok: true });
    }
    const memberPath = path.match(/^\/members\/([a-f0-9]{32})$/);
    if (method === "PATCH" && memberPath) {
      check(member.role === "admin", 403, "Administrator permission required");
      const input = await this.body(request);
      check(
        typeof input.disabled === "boolean",
        400,
        "Disabled must be boolean",
      );
      check(
        memberPath[1] !== uuid(this.env.ADMIN_UUID) &&
          memberPath[1] !== member.uuid,
        400,
        "Cannot disable the bootstrap administrator or yourself",
      );
      check(
        this.run(
          "UPDATE members SET disabled=? WHERE uuid=?",
          input.disabled ? 1 : 0,
          memberPath[1],
        ),
        404,
        "Member not found",
      );
      if (input.disabled) {
        this.run("DELETE FROM sessions WHERE uuid=?", memberPath[1]);
        this.run("DELETE FROM library_tickets WHERE uuid=?", memberPath[1]);
        this.closeSessions(memberPath[1]);
      }
      this.notify();
      return json(200, { ok: true });
    }
    if (method === "POST" && path === "/schematics") {
      this.rate(request, "upload", 10);
      const bytes = await this.body(request, 16 * 1024 * 1024, true),
        hash = digest(bytes);
      const existing = this.get("SELECT * FROM blobs WHERE hash=?", hash);
      if (!existing) {
        check(
          this.get(
            "SELECT count(*) AS n FROM blobs WHERE uploader=?",
            member.uuid,
          ).n < 500,
          409,
          "Schematic upload quota reached",
        );
        const metadata = validateSchematic(bytes, 64 * 1024 * 1024, 100_000);
        this.transaction(() => {
          this.reserveR2("put", bytes.length);
          this.run(
            "INSERT INTO blobs(hash,size,metadata,uploader,ready) VALUES(?,?,?,?,0)",
            hash,
            bytes.length,
            JSON.stringify(metadata),
            member.uuid,
          );
        });
        await this.env.FILES.put(`sync/${hash}.litematic`, bytes);
        this.run("UPDATE blobs SET ready=1 WHERE hash=?", hash);
      } else check(existing.ready, 503, "Schematic upload in progress");
      this.run(
        "INSERT OR IGNORE INTO blob_access(uuid,hash) VALUES(?,?)",
        member.uuid,
        hash,
      );
      return json(201, { hash });
    }
    const blobPath = path.match(/^\/schematics\/([a-f0-9]{64})$/);
    if (method === "GET" && blobPath) {
      const hash = blobPath[1],
        blob = this.get("SELECT * FROM blobs WHERE hash=? AND ready=1", hash);
      check(blob, 404, "Schematic not found");
      check(
        blob.uploader === member.uuid ||
          this.get(
            "SELECT hash FROM blob_access WHERE uuid=? AND hash=?",
            member.uuid,
            hash,
          ) ||
          member.role === "admin" ||
          this.all("SELECT body FROM placements").some(
            (p) => JSON.parse(p.body).hash === hash,
          ),
        403,
        "Schematic is not shared",
      );
      this.transaction(() => this.reserveR2("get"));
      const object = await this.env.FILES.get(`sync/${hash}.litematic`);
      check(object, 503, "Schematic storage unavailable");
      const bytes = Buffer.from(await object.arrayBuffer());
      const headers = {
        "Content-Type": "application/octet-stream",
        ETag: `"${hash}"`,
        "Accept-Ranges": "bytes",
        "Cache-Control": "private, no-store",
      };
      if (request.headers.get("Range")) {
        const match = request.headers.get("Range").match(/^bytes=(\d+)-(\d*)$/);
        check(match, 416, "Invalid range");
        const start = Number(match[1]),
          end = match[2] ? Number(match[2]) : bytes.length - 1;
        check(
          start <= end && end < bytes.length,
          416,
          "Range outside schematic",
        );
        headers["Content-Range"] = `bytes ${start}-${end}/${bytes.length}`;
        headers["Content-Length"] = String(end - start + 1);
        return new Response(bytes.subarray(start, end + 1), {
          status: 206,
          headers,
        });
      }
      headers["Content-Length"] = String(bytes.length);
      return new Response(bytes, { status: 200, headers });
    }
    if (method === "POST" && path === "/placements") {
      const input = await this.body(request),
        data = this.validatePlacement(input, member);
      check(
        this.get("SELECT count(*) AS n FROM placements").n < 5000,
        409,
        "Shared placement quota reached",
      );
      check(
        this.get(
          "SELECT coalesce(sum(length(CAST(body AS BLOB))),0) AS n FROM placements",
        ).n +
          Buffer.byteLength(JSON.stringify(data)) <
          4 * 1024 * 1024,
        409,
        "Shared placement metadata quota reached",
      );
      const id = randomUUID();
      this.run(
        "INSERT INTO placements(id,owner,revision,updated,body) VALUES(?,?,1,?,?)",
        id,
        member.uuid,
        Date.now(),
        JSON.stringify(data),
      );
      this.notify();
      return json(
        201,
        this.publicPlacement(
          this.get("SELECT * FROM placements WHERE id=?", id),
        ),
      );
    }
    const placementPath = path.match(/^\/placements\/([a-f0-9-]{36})$/);
    if (placementPath && ["PUT", "DELETE"].includes(method)) {
      const row = this.editable(member, placementPath[1]),
        input = await this.body(request);
      check(
        input.revision === row.revision,
        409,
        "Placement changed; refresh before editing",
      );
      if (method === "DELETE")
        this.run("DELETE FROM placements WHERE id=?", row.id);
      else {
        const data = this.validatePlacement(input, member);
        check(
          data.name === JSON.parse(row.body).name,
          400,
          "Use a personal alias to rename a placement",
        );
        check(
          this.get(
            "SELECT coalesce(sum(length(CAST(body AS BLOB))),0) AS n FROM placements",
          ).n -
            Buffer.byteLength(row.body) +
            Buffer.byteLength(JSON.stringify(data)) <
            4 * 1024 * 1024,
          409,
          "Shared placement metadata quota reached",
        );
        this.run(
          "UPDATE placements SET body=?,revision=revision+1,updated=? WHERE id=?",
          JSON.stringify(data),
          Date.now(),
          row.id,
        );
      }
      this.notify();
      return json(
        200,
        method === "DELETE"
          ? { ok: true }
          : this.publicPlacement(
              this.get("SELECT * FROM placements WHERE id=?", row.id),
            ),
      );
    }
    const prefPath = path.match(/^\/preferences\/([a-f0-9-]{36})$/);
    if (method === "PUT" && prefPath) {
      check(
        this.get("SELECT id FROM placements WHERE id=?", prefPath[1]),
        404,
        "Placement not found",
      );
      const input = await this.body(request);
      check(typeof input.hidden === "boolean", 400, "Hidden must be boolean");
      const pref = {
        hidden: input.hidden,
        alias: input.alias ? text(input.alias) : "",
      };
      this.run(
        "INSERT INTO preferences(uuid,placement_id,body) VALUES(?,?,?) ON CONFLICT(uuid,placement_id) DO UPDATE SET body=excluded.body",
        member.uuid,
        prefPath[1],
        JSON.stringify(pref),
      );
      this.notify(member.uuid);
      return json(200, pref);
    }
    throw new HttpError(404, "Endpoint not found");
  }
}
