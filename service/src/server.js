import http from "node:http";
import { randomBytes, randomUUID, createHash } from "node:crypto";
import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { WebSocketServer } from "ws";
import {
  check,
  HttpError,
  uuid,
  text,
  placement,
  validateSchematic,
} from "./validation.js";

const digest = (value) => createHash("sha256").update(value).digest("hex");
const secret = () => randomBytes(32).toString("base64url");

async function mojangVerify(name, serverId) {
  const url = new URL(
    "https://sessionserver.mojang.com/session/minecraft/hasJoined",
  );
  url.searchParams.set("username", name);
  url.searchParams.set("serverId", serverId);
  let res;
  try {
    res = await fetch(url, {
      signal: AbortSignal.timeout(10000),
      redirect: "error",
    });
  } catch {
    throw new HttpError(503, "Minecraft authentication service unavailable");
  }
  check(
    res.status === 200,
    res.status >= 500 ? 503 : 401,
    "Minecraft session verification failed",
  );
  return res.json();
}

export async function createService({
  dataDir,
  adminUuid,
  libraryUrl = "https://library.weiuou.top",
  verifySession = mojangVerify,
  clock = Date.now,
  maxUpload = 32 * 1024 * 1024,
}) {
  await mkdir(join(dataDir, "blobs"), { recursive: true });
  const db = new DatabaseSync(join(dataDir, "sync.sqlite"));
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS members(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,role TEXT NOT NULL,disabled INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS invites(id TEXT PRIMARY KEY,hash TEXT UNIQUE NOT NULL,note TEXT NOT NULL,bound_uuid TEXT,expires INTEGER NOT NULL,used_by TEXT,revoked INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY,uuid TEXT NOT NULL REFERENCES members(uuid),expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS library_tickets(hash TEXT PRIMARY KEY,uuid TEXT NOT NULL REFERENCES members(uuid),expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS blobs(hash TEXT PRIMARY KEY,size INTEGER NOT NULL,metadata TEXT NOT NULL,uploader TEXT NOT NULL REFERENCES members(uuid));
    CREATE TABLE IF NOT EXISTS blob_access(uuid TEXT NOT NULL REFERENCES members(uuid),hash TEXT NOT NULL REFERENCES blobs(hash),PRIMARY KEY(uuid,hash));
    CREATE TABLE IF NOT EXISTS placements(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES members(uuid),revision INTEGER NOT NULL,updated INTEGER NOT NULL,body TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS preferences(uuid TEXT NOT NULL REFERENCES members(uuid),placement_id TEXT NOT NULL REFERENCES placements(id) ON DELETE CASCADE,body TEXT NOT NULL,PRIMARY KEY(uuid,placement_id));`);
  const admin = uuid(adminUuid);
  const challenges = new Map(),
    limits = new Map();
  let activeUploads = 0;
  const wss = new WebSocketServer({ noServer: true, maxPayload: 4096 });
  const get = (sql, ...args) => db.prepare(sql).get(...args);
  const all = (sql, ...args) => db.prepare(sql).all(...args);
  const run = (sql, ...args) => db.prepare(sql).run(...args);
  function transaction(fn) {
    db.exec("BEGIN IMMEDIATE");
    try {
      const value = fn();
      db.exec("COMMIT");
      return value;
    } catch (e) {
      db.exec("ROLLBACK");
      throw e;
    }
  }
  function memberFor(req) {
    const token = req.headers.authorization?.match(
      /^Bearer ([A-Za-z0-9_-]{43})$/,
    )?.[1];
    check(token, 401, "Sign in required");
    const member = get(
      "SELECT m.*,s.expires FROM sessions s JOIN members m ON m.uuid=s.uuid WHERE s.hash=?",
      digest(token),
    );
    check(
      member && !member.disabled && member.expires > clock(),
      401,
      "Session expired or revoked",
    );
    return { ...member, sessionHash: digest(token) };
  }
  function requireAdmin(member) {
    check(member.role === "admin", 403, "Administrator permission required");
  }
  function rate(req, group, max) {
    // Only trust the socket address. A reverse proxy should enforce its own per-client limits.
    const key = `${group}:${req.socket.remoteAddress}`;
    let entry = limits.get(key);
    if (!entry || entry.until <= clock())
      limits.set(key, (entry = { until: clock() + 60000, count: 0 }));
    check(++entry.count <= max, 429, "Too many requests; retry in one minute");
  }
  async function body(req, max = 256 * 1024, binary = false) {
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      check(size <= max, 413, "Upload too large");
      chunks.push(chunk);
    }
    const bytes = Buffer.concat(chunks);
    if (binary) return bytes;
    try {
      const obj = JSON.parse(bytes.toString("utf8"));
      check(
        obj && !Array.isArray(obj) && typeof obj === "object",
        400,
        "JSON object required",
      );
      return obj;
    } catch (e) {
      if (e instanceof HttpError) throw e;
      throw new HttpError(400, "Invalid JSON");
    }
  }
  function notify(memberUuid = null) {
    for (const ws of wss.clients)
      if (ws.readyState === 1 && (!memberUuid || ws.memberUuid === memberUuid))
        ws.send(JSON.stringify({ type: "invalidate" }));
  }
  function closeSessions(memberUuid) {
    for (const ws of wss.clients)
      if (ws.memberUuid === memberUuid) ws.close(4001, "Access revoked");
  }
  function publicPlacement(row) {
    const owner = get("SELECT name FROM members WHERE uuid=?", row.owner);
    return {
      ...JSON.parse(row.body),
      id: row.id,
      owner: row.owner,
      ownerName: owner?.name,
      revision: row.revision,
      updated: row.updated,
    };
  }
  function editable(member, id) {
    const row = get("SELECT * FROM placements WHERE id=?", id);
    check(row, 404, "Placement not found");
    check(
      row.owner === member.uuid || member.role === "admin",
      403,
      "Only the owner or administrator can edit this placement",
    );
    return row;
  }
  function validatePlacement(input, member) {
    const data = placement(input);
    const blob = get("SELECT * FROM blobs WHERE hash=?", data.hash);
    check(blob, 400, "Upload schematic first");
    // A member can reference a schematic they uploaded or one already published in the shared room.
    check(
      blob.uploader === member.uuid ||
        get(
          "SELECT hash FROM blob_access WHERE uuid=? AND hash=?",
          member.uuid,
          data.hash,
        ) ||
        member.role === "admin" ||
        all("SELECT body FROM placements").some(
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
  async function handle(req, res) {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    const url = new URL(req.url, "http://localhost"),
      path = url.pathname;
    const respond = (status, data) => {
      res.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
      });
      res.end(JSON.stringify(data));
    };
    try {
      rate(req, "general", 300);
      if (req.method === "GET" && path === "/health")
        return respond(200, { ok: true, protocol: 1 });
      if (req.method === "POST" && path === "/library/redeem") {
        rate(req, "library", 60);
        const input = await body(req);
        check(
          typeof input.ticket === "string" && /^[A-Za-z0-9_-]{43}$/.test(input.ticket),
          401,
          "Invalid library ticket",
        );
        const profile = transaction(() => {
          const row = get(
            "SELECT t.hash,t.expires,m.uuid,m.name,m.role,m.disabled FROM library_tickets t JOIN members m ON m.uuid=t.uuid WHERE t.hash=?",
            digest(input.ticket),
          );
          check(row && row.expires > clock() && !row.disabled, 401, "Library ticket expired or used");
          run("DELETE FROM library_tickets WHERE hash=?", row.hash);
          return { uuid: row.uuid, name: row.name, role: row.role };
        });
        return respond(200, profile);
      }
      if (req.method === "POST" && path === "/auth/challenge") {
        rate(req, "auth", 20);
        const input = await body(req);
        const playerUuid = uuid(input.uuid),
          name = text(input.name, 16);
        check(/^[A-Za-z0-9_]{1,16}$/.test(name), 400, "Invalid Minecraft name");
        check(challenges.size < 10000, 503, "Too many pending authentications");
        const id = secret(),
          serverId = randomBytes(20).toString("hex");
        challenges.set(id, {
          uuid: playerUuid,
          name,
          serverId,
          expires: clock() + 120000,
          busy: false,
        });
        return respond(200, { challengeId: id, serverId, expiresIn: 120 });
      }
      if (req.method === "POST" && path === "/auth/complete") {
        rate(req, "auth", 20);
        const input = await body(req),
          c = challenges.get(input.challengeId);
        check(
          c && !c.busy && c.expires > clock(),
          401,
          "Challenge expired or already used",
        );
        c.busy = true;
        try {
          const profile = await verifySession(c.name, c.serverId);
          check(uuid(profile.id) === c.uuid, 401, "Minecraft profile mismatch");
          const name = text(profile.name, 16),
            token = secret(),
            expires = clock() + 12 * 60 * 60 * 1000;
          const member = transaction(() => {
            let existing = get("SELECT * FROM members WHERE uuid=?", c.uuid);
            check(!existing?.disabled, 403, "Membership disabled");
            if (!existing) {
              check(
                get("SELECT count(*) AS n FROM members").n < 2000,
                409,
                "Member quota reached",
              );
              let role = "member";
              if (c.uuid === admin) role = "admin";
              else {
                check(
                  typeof input.invite === "string" &&
                    input.invite.trim().length > 0,
                  403,
                  "Invitation required",
                );
                const invite = get(
                  "SELECT * FROM invites WHERE hash=?",
                  digest(input.invite.trim()),
                );
                check(
                  invite &&
                    !invite.revoked &&
                    !invite.used_by &&
                    invite.expires > clock(),
                  403,
                  "Invalid, expired, or used invitation",
                );
                check(
                  !invite.bound_uuid || invite.bound_uuid === c.uuid,
                  403,
                  "Invitation belongs to another Minecraft account",
                );
                run(
                  "UPDATE invites SET used_by=? WHERE id=?",
                  c.uuid,
                  invite.id,
                );
              }
              run(
                "INSERT INTO members(uuid,name,role) VALUES(?,?,?)",
                c.uuid,
                name,
                role,
              );
            } else run("UPDATE members SET name=? WHERE uuid=?", name, c.uuid);
            run(
              "INSERT INTO sessions(hash,uuid,expires) VALUES(?,?,?)",
              digest(token),
              c.uuid,
              expires,
            );
            return get(
              "SELECT uuid,name,role FROM members WHERE uuid=?",
              c.uuid,
            );
          });
          notify();
          return respond(200, { token, expires, member });
        } finally {
          challenges.delete(input.challengeId);
        }
      }
      const member = memberFor(req);
      if (req.method === "POST" && path === "/library/ticket") {
        rate(req, "library", 10);
        const url = new URL(libraryUrl);
        check(
          url.protocol === "https:" ||
            (url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname)),
          500,
          "Invalid library URL",
        );
        const ticket = secret();
        run(
          "INSERT INTO library_tickets(hash,uuid,expires) VALUES(?,?,?)",
          digest(ticket),
          member.uuid,
          clock() + 5 * 60 * 1000,
        );
        url.hash = `ticket=${ticket}`;
        return respond(200, { url: url.toString(), expiresIn: 300 });
      }
      if (req.method === "POST" && path === "/auth/logout") {
        run("DELETE FROM sessions WHERE hash=?", member.sessionHash);
        for (const ws of wss.clients)
          if (ws.sessionHash === member.sessionHash)
            ws.close(4001, "Signed out");
        return respond(200, { ok: true });
      }
      if (req.method === "GET" && path === "/state") {
        const prefs = Object.fromEntries(
          all(
            "SELECT placement_id,body FROM preferences WHERE uuid=?",
            member.uuid,
          ).map((p) => [p.placement_id, JSON.parse(p.body)]),
        );
        return respond(200, {
          protocol: 1,
          member: { uuid: member.uuid, name: member.name, role: member.role },
          placements: all("SELECT * FROM placements ORDER BY updated DESC").map(
            publicPlacement,
          ),
          preferences: prefs,
          ...(member.role === "admin"
            ? {
                members: all(
                  "SELECT uuid,name,role,disabled FROM members ORDER BY name",
                ),
                invites: all(
                  "SELECT id,note,bound_uuid,expires,used_by,revoked FROM invites ORDER BY expires DESC",
                ),
              }
            : {}),
        });
      }
      if (req.method === "POST" && path === "/invites") {
        requireAdmin(member);
        const input = await body(req),
          note = text(input.note),
          bound = input.boundUuid ? uuid(input.boundUuid) : null;
        const code = randomBytes(18).toString("base64url"),
          id = randomUUID(),
          expires = clock() + 48 * 60 * 60 * 1000;
        run(
          "DELETE FROM invites WHERE expires<?",
          clock() - 7 * 24 * 60 * 60 * 1000,
        );
        check(
          get("SELECT count(*) AS n FROM invites").n < 1000,
          409,
          "Invitation history quota reached",
        );
        run(
          "INSERT INTO invites(id,hash,note,bound_uuid,expires) VALUES(?,?,?,?,?)",
          id,
          digest(code),
          note,
          bound,
          expires,
        );
        notify();
        return respond(201, { id, code, expires });
      }
      const invitation = path.match(/^\/invites\/([a-f0-9-]{36})$/);
      if (req.method === "DELETE" && invitation) {
        requireAdmin(member);
        check(
          run("UPDATE invites SET revoked=1 WHERE id=?", invitation[1]).changes,
          404,
          "Invitation not found",
        );
        notify();
        return respond(200, { ok: true });
      }
      const memberPath = path.match(/^\/members\/([a-f0-9]{32})$/);
      if (req.method === "PATCH" && memberPath) {
        requireAdmin(member);
        const input = await body(req);
        check(
          typeof input.disabled === "boolean",
          400,
          "Disabled must be boolean",
        );
        check(
          memberPath[1] !== admin && memberPath[1] !== member.uuid,
          400,
          "Cannot disable the bootstrap administrator or yourself",
        );
        check(
          run(
            "UPDATE members SET disabled=? WHERE uuid=?",
            input.disabled ? 1 : 0,
            memberPath[1],
          ).changes,
          404,
          "Member not found",
        );
        if (input.disabled) {
          run("DELETE FROM sessions WHERE uuid=?", memberPath[1]);
          run("DELETE FROM library_tickets WHERE uuid=?", memberPath[1]);
          closeSessions(memberPath[1]);
        }
        notify();
        return respond(200, { ok: true });
      }
      if (req.method === "POST" && path === "/schematics") {
        rate(req, "upload", 10);
        check(activeUploads < 2, 503, "Upload queue busy; retry shortly");
        activeUploads++;
        try {
          const bytes = await body(req, maxUpload, true),
            hash = digest(bytes);
          if (!get("SELECT hash FROM blobs WHERE hash=?", hash)) {
            const count = get(
              "SELECT count(*) AS n FROM blobs WHERE uploader=?",
              member.uuid,
            ).n;
            check(count < 500, 409, "Schematic upload quota reached");
            const metadata = validateSchematic(bytes),
              tmp = join(dataDir, "blobs", `${hash}.${randomUUID()}.tmp`);
            await writeFile(tmp, bytes);
            await rename(tmp, join(dataDir, "blobs", `${hash}.litematic`));
            run(
              "INSERT OR IGNORE INTO blobs(hash,size,metadata,uploader) VALUES(?,?,?,?)",
              hash,
              bytes.length,
              JSON.stringify(metadata),
              member.uuid,
            );
          }
          run(
            "INSERT OR IGNORE INTO blob_access(uuid,hash) VALUES(?,?)",
            member.uuid,
            hash,
          );
          return respond(201, { hash });
        } finally {
          activeUploads--;
        }
      }
      const blobPath = path.match(/^\/schematics\/([a-f0-9]{64})$/);
      if (req.method === "GET" && blobPath) {
        const hash = blobPath[1],
          blob = get("SELECT * FROM blobs WHERE hash=?", hash);
        check(blob, 404, "Schematic not found");
        check(
          blob.uploader === member.uuid ||
            get(
              "SELECT hash FROM blob_access WHERE uuid=? AND hash=?",
              member.uuid,
              hash,
            ) ||
            member.role === "admin" ||
            all("SELECT body FROM placements").some(
              (p) => JSON.parse(p.body).hash === hash,
            ),
          403,
          "Schematic is not shared",
        );
        const bytes = await readFile(
          join(dataDir, "blobs", `${hash}.litematic`),
        );
        res.setHeader("Content-Type", "application/octet-stream");
        res.setHeader("ETag", `"${hash}"`);
        res.setHeader("Accept-Ranges", "bytes");
        if (req.headers.range) {
          const match = req.headers.range.match(/^bytes=(\d+)-(\d*)$/);
          check(match, 416, "Invalid range");
          const start = Number(match[1]),
            end = match[2] ? Number(match[2]) : bytes.length - 1;
          check(
            start <= end && end < bytes.length,
            416,
            "Range outside schematic",
          );
          res.writeHead(206, {
            "Content-Range": `bytes ${start}-${end}/${bytes.length}`,
            "Content-Length": end - start + 1,
          });
          return res.end(bytes.subarray(start, end + 1));
        }
        res.writeHead(200, { "Content-Length": bytes.length });
        return res.end(bytes);
      }
      if (req.method === "POST" && path === "/placements") {
        const input = await body(req),
          data = validatePlacement(input, member);
        check(
          get("SELECT count(*) AS n FROM placements").n < 5000,
          409,
          "Shared placement quota reached",
        );
        check(
          get(
            "SELECT coalesce(sum(length(CAST(body AS BLOB))),0) AS n FROM placements",
          ).n +
            Buffer.byteLength(JSON.stringify(data)) <
            4 * 1024 * 1024,
          409,
          "Shared placement metadata quota reached",
        );
        const id = randomUUID();
        run(
          "INSERT INTO placements(id,owner,revision,updated,body) VALUES(?,?,1,?,?)",
          id,
          member.uuid,
          clock(),
          JSON.stringify(data),
        );
        notify();
        return respond(
          201,
          publicPlacement(get("SELECT * FROM placements WHERE id=?", id)),
        );
      }
      const placementPath = path.match(/^\/placements\/([a-f0-9-]{36})$/);
      if (placementPath && ["PUT", "DELETE"].includes(req.method)) {
        const row = editable(member, placementPath[1]),
          input = await body(req);
        check(
          input.revision === row.revision,
          409,
          "Placement changed; refresh before editing",
        );
        if (req.method === "DELETE")
          run("DELETE FROM placements WHERE id=?", row.id);
        else {
          const data = validatePlacement(input, member);
          check(
            data.name === JSON.parse(row.body).name,
            400,
            "Use a personal alias to rename a placement",
          );
          check(
            get(
              "SELECT coalesce(sum(length(CAST(body AS BLOB))),0) AS n FROM placements",
            ).n -
              Buffer.byteLength(row.body) +
              Buffer.byteLength(JSON.stringify(data)) <
              4 * 1024 * 1024,
            409,
            "Shared placement metadata quota reached",
          );
          run(
            "UPDATE placements SET body=?,revision=revision+1,updated=? WHERE id=?",
            JSON.stringify(data),
            clock(),
            row.id,
          );
        }
        notify();
        return respond(
          200,
          req.method === "DELETE"
            ? { ok: true }
            : publicPlacement(
                get("SELECT * FROM placements WHERE id=?", row.id),
              ),
        );
      }
      const prefPath = path.match(/^\/preferences\/([a-f0-9-]{36})$/);
      if (req.method === "PUT" && prefPath) {
        check(
          get("SELECT id FROM placements WHERE id=?", prefPath[1]),
          404,
          "Placement not found",
        );
        const input = await body(req);
        check(typeof input.hidden === "boolean", 400, "Hidden must be boolean");
        const pref = {
          hidden: input.hidden,
          alias: input.alias ? text(input.alias) : "",
        };
        run(
          "INSERT INTO preferences(uuid,placement_id,body) VALUES(?,?,?) ON CONFLICT(uuid,placement_id) DO UPDATE SET body=excluded.body",
          member.uuid,
          prefPath[1],
          JSON.stringify(pref),
        );
        notify(member.uuid);
        return respond(200, pref);
      }
      throw new HttpError(404, "Endpoint not found");
    } catch (error) {
      if (res.headersSent) return res.destroy();
      if (!(error instanceof HttpError))
        console.error("Request failed:", error.message);
      respond(error.status || 500, {
        error:
          error instanceof HttpError ? error.message : "Internal server error",
      });
    }
  }
  const server = http.createServer(handle);
  server.requestTimeout = 30000;
  server.headersTimeout = 15000;
  server.on("upgrade", (req, socket, head) => {
    try {
      check(req.url === "/events", 404, "Unknown WebSocket endpoint");
      rate(req, "ws", 20);
      const member = memberFor(req);
      check(
        [...wss.clients].filter((ws) => ws.memberUuid === member.uuid).length <
          5,
        429,
        "Too many active devices",
      );
      wss.handleUpgrade(req, socket, head, (ws) => {
        ws.memberUuid = member.uuid;
        ws.sessionHash = member.sessionHash;
        ws.expires = member.expires;
        ws.alive = true;
        ws.on("pong", () => (ws.alive = true));
        ws.on("error", () => {});
        ws.send(JSON.stringify({ type: "invalidate" }));
      });
    } catch (e) {
      socket.end(
        `HTTP/1.1 ${e.status || 401} Unauthorized\r\nConnection: close\r\n\r\n`,
      );
    }
  });
  const interval = setInterval(() => {
    for (const [id, c] of challenges)
      if (c.expires <= clock()) challenges.delete(id);
    for (const [id, l] of limits) if (l.until <= clock()) limits.delete(id);
    run("DELETE FROM sessions WHERE expires<=?", clock());
    run("DELETE FROM library_tickets WHERE expires<=?", clock());
    for (const ws of wss.clients) {
      if (ws.expires <= clock()) ws.close(4001, "Session expired");
      else if (!ws.alive) ws.terminate();
      else {
        ws.alive = false;
        ws.ping();
      }
    }
  }, 30000);
  interval.unref();
  let closing;
  return {
    server,
    close() {
      if (closing) return closing;
      closing = (async () => {
        clearInterval(interval);
        for (const ws of wss.clients) ws.terminate();
        await new Promise((resolve) => wss.close(resolve));
        await new Promise((resolve) => server.close(resolve));
        db.close();
      })();
      return closing;
    },
  };
}
