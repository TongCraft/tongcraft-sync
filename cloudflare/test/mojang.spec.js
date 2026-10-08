import { expect, test } from "vitest";
import { requestSession } from "../src/mojang.js";

const serverId = "a".repeat(40);
function socketResponse(response) {
  let request = "", closed = false;
  const open = (address, options) => {
    expect(address).toEqual({ hostname: "sessionserver.mojang.com", port: 443 });
    expect(options).toEqual({ secureTransport: "on" });
    return {
      opened: Promise.resolve(), closed: Promise.resolve(),
      readable: new ReadableStream({ start(controller) {
        const bytes = new TextEncoder().encode(response);
        for (let i = 0; i < bytes.length; i += 7) controller.enqueue(bytes.slice(i, i + 7));
        controller.close();
      } }),
      writable: new WritableStream({ write(bytes) { request += new TextDecoder().decode(bytes); } }),
      async close() { closed = true; },
    };
  };
  return { open, request: () => request, closed: () => closed };
}

test("TLS session query accepts a verified profile and sends no credentials", async () => {
  const body = JSON.stringify({ id: "288772a1e4a54741ac4caa7b4ceceddd", name: "Wei_uou" });
  const socket = socketResponse(`HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n${body}`);
  const response = await requestSession("Wei_uou", serverId, socket.open);
  expect(await response.json()).toEqual(JSON.parse(body));
  expect(socket.request()).toContain(`GET /session/minecraft/hasJoined?username=Wei_uou&serverId=${serverId} HTTP/1.1`);
  expect(socket.request()).toContain("Accept-Encoding: identity\r\n");
  expect(socket.request()).not.toMatch(/Authorization|accessToken/i);
  expect(socket.closed()).toBe(true);
});

test("missing Mojang session remains unauthenticated", async () => {
  const socket = socketResponse("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n");
  const response = await requestSession("Wei_uou", serverId, socket.open);
  expect(response.status).toBe(204);
  expect(await response.text()).toBe("");
});

test("fragmented chunked JSON is decoded", async () => {
  const socket = socketResponse('HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{"id\r\n6;ext=x\r\n":"a"}\r\n0\r\n\r\n');
  const response = await requestSession("Wei_uou", serverId, socket.open);
  expect(await response.json()).toEqual({ id: "a" });
});

test.each([
  ["HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n{}", "Incomplete Mojang HTTP body"],
  ["HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\n{}\r\n", "Incomplete Mojang chunk body"],
  ["HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nxx\r\n", "Invalid Mojang chunk size"],
  ["HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n{}", "Unexpected Mojang content encoding"],
  ["HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(64 * 1024), "Mojang response too large"],
])("rejects malformed or oversized response %#", async (response, message) => {
  const socket = socketResponse(response);
  await expect(requestSession("Wei_uou", serverId, socket.open)).rejects.toThrow(message);
  expect(socket.closed()).toBe(true);
});

test("TLS failures close the socket and fail authentication", async () => {
  let closed = false;
  const open = () => ({ opened: Promise.reject(new Error("TLS failed")), closed: Promise.resolve(), async close() { closed = true; } });
  await expect(requestSession("Wei_uou", serverId, open)).rejects.toThrow("TLS failed");
  expect(closed).toBe(true);
});

test("invalid query cannot inject HTTP headers or change the destination", async () => {
  let opened = false;
  const open = () => { opened = true; };
  await expect(requestSession("Wei_uou\r\nHost: evil", serverId, open)).rejects.toThrow("Invalid Mojang session query");
  await expect(requestSession("Wei_uou", "../other", open)).rejects.toThrow("Invalid Mojang session query");
  expect(opened).toBe(false);
});
