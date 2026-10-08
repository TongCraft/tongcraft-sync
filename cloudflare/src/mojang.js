import { connect } from "cloudflare:sockets";
import { Buffer } from "node:buffer";

const host = "sessionserver.mojang.com";
const maxResponseBytes = 64 * 1024;

function parseResponse(bytes) {
  const boundary = bytes.indexOf("\r\n\r\n");
  if (boundary < 0) throw new Error("Incomplete Mojang HTTP headers");
  const lines = bytes.subarray(0, boundary).toString("ascii").split("\r\n");
  const status = /^HTTP\/1\.[01] (\d{3})(?: .*)?$/.exec(lines.shift());
  if (!status) throw new Error("Invalid Mojang HTTP status");
  const headers = new Headers();
  for (const line of lines) {
    const colon = line.indexOf(":");
    if (colon < 1) throw new Error("Invalid Mojang HTTP header");
    headers.append(line.slice(0, colon), line.slice(colon + 1).trim());
  }
  let body = bytes.subarray(boundary + 4);
  const transfer = headers.get("transfer-encoding");
  if (transfer) {
    if (transfer.toLowerCase() !== "chunked")
      throw new Error("Unsupported Mojang transfer encoding");
    const chunks = [];
    let offset = 0;
    for (;;) {
      const end = body.indexOf("\r\n", offset);
      if (end < 0) throw new Error("Incomplete Mojang chunk header");
      const sizeText = body.subarray(offset, end).toString("ascii");
      if (!/^[\da-f]+(?:;[^\r\n]*)?$/i.test(sizeText))
        throw new Error("Invalid Mojang chunk size");
      const size = Number.parseInt(sizeText, 16);
      offset = end + 2;
      if (size === 0) {
        if (body.indexOf("\r\n", offset) < 0)
          throw new Error("Incomplete Mojang chunk terminator");
        break;
      }
      if (size > body.length - offset - 2 ||
          body.subarray(offset + size, offset + size + 2).toString() !== "\r\n")
        throw new Error("Incomplete Mojang chunk body");
      chunks.push(body.subarray(offset, offset + size));
      offset += size + 2;
    }
    body = Buffer.concat(chunks);
    headers.delete("transfer-encoding");
  } else if (headers.has("content-length")) {
    const length = headers.get("content-length");
    if (!/^\d+$/.test(length) || Number(length) !== body.length)
      throw new Error("Incomplete Mojang HTTP body");
  }
  if (headers.has("content-encoding") &&
      headers.get("content-encoding").toLowerCase() !== "identity")
    throw new Error("Unexpected Mojang content encoding");
  headers.delete("content-length");
  return new Response(Number(status[1]) === 204 ? null : body, {
    status: Number(status[1]), headers,
  });
}

// Mojang rejects Workers fetch() egress with an HTML 403. A TLS socket uses
// Cloudflare's TCP egress and still verifies the official session server.
export async function requestSession(name, serverId, open = connect) {
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || !/^[a-f0-9]{40}$/.test(serverId))
    throw new Error("Invalid Mojang session query");
  const query = new URLSearchParams({ username: name, serverId });
  const socket = open({ hostname: host, port: 443 }, { secureTransport: "on" });
  socket.closed.catch(() => {});
  let timer;
  try {
    return await Promise.race([
      (async () => {
        await socket.opened;
        const writer = socket.writable.getWriter();
        try {
          await writer.write(new TextEncoder().encode(
            `GET /session/minecraft/hasJoined?${query} HTTP/1.1\r\n` +
            `Host: ${host}\r\nUser-Agent: TongCraft-Sync\r\n` +
            "Accept: application/json\r\nAccept-Encoding: identity\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n",
          ));
        } finally { writer.releaseLock(); }
        const reader = socket.readable.getReader();
        const chunks = [];
        let size = 0;
        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            size += value.byteLength;
            if (size > maxResponseBytes) throw new Error("Mojang response too large");
            chunks.push(Buffer.from(value));
          }
        } finally { reader.releaseLock(); }
        return parseResponse(Buffer.concat(chunks));
      })(),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error("Mojang session request timed out")), 10000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
    await socket.close().catch(() => {});
  }
}
