import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { deflateRawSync } from "node:zlib";
import { ROOT, options, parseJsonc, readConfig, readJson, runCommand, successful, versionAtLeast } from "./checks/common.mjs";
import { bucketListed, validateCloudflareConfig } from "./doctor.mjs";
import { buildVersions, versionErrors } from "./verify-versions.mjs";
import { artifactErrors, jarEntries } from "./verify-artifact.mjs";
import { gameEvidence } from "./check-game.mjs";
import { testEvidence } from "./check-services.mjs";

const build = buildVersions(readFileSync(resolve(ROOT, "client/build.gradle"), "utf8"));
const sourceMod = readJson("client/src/main/resources/fabric.mod.json");

test("JSONC preserves URLs, comment-like strings and commas inside strings", () => {
  assert.deepEqual(parseJsonc(`{
    // comment
    "url": "https://library.invalid/a//b", /* block */
    "text": "comma,} and /* literal */ and \\\"quote\\\"",
    "list": [1, 2,],
  }`), { url: "https://library.invalid/a//b", text: 'comma,} and /* literal */ and "quote"', list: [1, 2] });
  assert.throws(() => parseJsonc('{"a":1, /* unfinished'), /Unterminated/);
  assert.throws(() => parseJsonc('{"a":1 /* missing comma */ "b":2}'));
});

test("CLI rejects unknown, duplicate and missing options", () => {
  assert.deepEqual(options(["--profile", "client"], ["profile"]), { profile: "client" });
  assert.throws(() => options(["--profile"], ["profile"]), /Missing/);
  assert.throws(() => options(["--profile", "client", "--profile", "all"], ["profile"]), /Duplicate/);
  assert.throws(() => options(["--skip-tests"], []), /Unknown/);
});

test("version comparison handles minor and patch requirements without string ordering", () => {
  assert.equal(versionAtLeast("24.13.0", "24.13.0"), true);
  assert.equal(versionAtLeast("24.9.0", "24.13.0"), false);
  assert.equal(versionAtLeast("25.0.0", "24.13.0"), true);
  assert.equal(versionAtLeast("unknown", "25"), false);
});

test("version checkpoint rejects mismatched Minecraft artifacts, minima and filenames", () => {
  assert.deepEqual(versionErrors(build, sourceMod), []);
  assert.match(versionErrors({ ...build, litematica: "wrong" }, sourceMod).join("\n"), /Litematica artifact/);
  assert.match(versionErrors({ ...build, api: "0.1+wrong" }, sourceMod).join("\n"), /Fabric API targets/);
  assert.match(versionErrors({ ...build, archive: "tongcraft-sync-wrong" }, sourceMod).join("\n"), /Archive name/);
  const mod = structuredClone(sourceMod);
  mod.depends.minecraft = "wrong";
  mod.depends.java = ">=99";
  assert.match(versionErrors(build, mod).join("\n"), /Minecraft dependency/);
  assert.match(versionErrors(build, mod).join("\n"), /java:/);
  assert.throws(() => buildVersions("dependencies {}"), /Cannot read/);
  const source = readFileSync(resolve(ROOT, "client/build.gradle"), "utf8");
  assert.deepEqual(buildVersions(`// minecraft 'com.mojang:minecraft:wrong'\n${source}`), build);
  assert.throws(() => buildVersions(`${source}\nminecraft 'com.mojang:minecraft:wrong'`), /unique/);
});

test("local Cloudflare preflight rejects remote bindings and deployed account configuration", () => {
  const config = readConfig();
  assert.deepEqual(validateCloudflareConfig(config), []);
  for (const change of [
    { routes: [{ pattern: "sync.example.com", custom_domain: true }] },
    { workers_dev: true },
    { account_id: "a".repeat(32) },
    { r2_buckets: [{ binding: "FILES", bucket_name: "test", remote: true }] },
    { vars: { ...config.vars, LIBRARY_URL: "https://library.example.com" } },
  ]) assert.ok(validateCloudflareConfig({ ...config, ...change }).length > 0);
  const deployment = { ...config, workers_dev: true, account_id: "a".repeat(32), vars: { ADMIN_UUID: "b".repeat(32), LIBRARY_URL: "https://library.example.com" } };
  assert.deepEqual(validateCloudflareConfig(deployment, { local: false }), []);
  assert.ok(validateCloudflareConfig(readConfig("cloudflare/wrangler.deploy.example.jsonc"), { local: false }).length > 0);
});

test("deployment bucket preflight matches exact names rather than substrings", () => {
  assert.equal(bucketListed("Listing buckets...\nname: my-sync\ncreation_date: today", "my-sync"), true);
  assert.equal(bucketListed("name: my-sync-backup", "my-sync"), false);
  assert.equal(bucketListed("Error: bucket my-sync is inaccessible", "my-sync"), false);
});

function validEntries() {
  const entries = new Map();
  const add = (name, bytes = Buffer.from("fixture")) => entries.set(name, () => bytes);
  add("fabric.mod.json", Buffer.from(JSON.stringify({ ...sourceMod, version: build.defaultModVersion })));
  for (const name of ["LICENSE", "assets/tongcraft_sync/lang/zh_cn.json", "assets/tongcraft_sync/lang/en_us.json"]) add(name);
  const bytecode = Buffer.alloc(8);
  bytecode.writeUInt32BE(0xcafebabe);
  bytecode.writeUInt16BE(Number(build.java) + 44, 6);
  for (const name of Object.values(sourceMod.entrypoints).flat()) add(`${name.replaceAll(".", "/")}.class`, bytecode);
  return entries;
}

test("artifact checkpoint rejects stale metadata, absent entrypoints and test class leakage", () => {
  const entries = validEntries();
  assert.deepEqual(artifactErrors(entries, build, sourceMod, build.defaultModVersion), []);
  assert.match(artifactErrors(entries, build, sourceMod, "9.9.9").join("\n"), /Expected mod version/);
  entries.delete("org/tongcraft/sync/TongCraftClient.class");
  assert.match(artifactErrors(entries, build, sourceMod, build.defaultModVersion).join("\n"), /Entrypoint class missing/);
  entries.set("org/tongcraft/sync/SyncGameTest.class", () => Buffer.from("bad class"));
  const errors = artifactErrors(entries, build, sourceMod, build.defaultModVersion).join("\n");
  assert.match(errors, /Test content leaked/);
  assert.match(errors, /Invalid class/);
});

function zip(name, value) {
  const filename = Buffer.from(name), compressed = deflateRawSync(value);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50);
  local.writeUInt16LE(8, 8);
  local.writeUInt32LE(compressed.length, 18);
  local.writeUInt32LE(value.length, 22);
  local.writeUInt16LE(filename.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50);
  central.writeUInt16LE(8, 10);
  central.writeUInt32LE(compressed.length, 20);
  central.writeUInt32LE(value.length, 24);
  central.writeUInt16LE(filename.length, 28);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length + filename.length, 12);
  end.writeUInt32LE(local.length + filename.length + compressed.length, 16);
  return Buffer.concat([local, filename, compressed, central, filename, end]);
}

test("JAR reader handles compressed entries and rejects truncation without extraction", () => {
  const bytes = zip("fabric.mod.json", Buffer.from('{"version":"0.1.0"}'));
  assert.equal(jarEntries(bytes).get("fabric.mod.json")().toString(), '{"version":"0.1.0"}');
  assert.throws(() => jarEntries(bytes.subarray(0, bytes.length - 3)), /directory is missing/);
  const bad = Buffer.from(bytes);
  bad.writeUInt32LE(0xffffffff, bad.length - 6);
  assert.throws(() => jarEntries(bad), /ZIP64/);
});

test("zero tests, all-skipped tests and stale game markers cannot pass", () => {
  assert.equal(testEvidence("node", "# tests 0\n# pass 0"), false);
  assert.equal(testEvidence("node", "# tests 1\n# pass 0\n# skipped 1"), false);
  assert.equal(testEvidence("node", "# tests 8\n# pass 8"), true);
  assert.equal(testEvidence("vitest", "Tests 1 skipped (1)"), false);
  assert.equal(testEvidence("vitest", "Tests 1 passed (1)"), true);
  assert.equal(gameEvidence("TONGCRAFT_SYNC_GAME_TEST_PASS old-run", "new-run"), false);
  assert.equal(gameEvidence("TONGCRAFT_SYNC_GAME_TEST_PASS new-run", "new-run"), true);
  assert.equal(gameEvidence("[13:00:00] [Render thread/INFO] (Minecraft) [STDOUT]: TONGCRAFT_SYNC_GAME_TEST_PASS new-run", "new-run"), true);
  assert.equal(gameEvidence("[13:00:00] [Render thread/INFO] (Minecraft) [STDOUT]: TONGCRAFT_SYNC_GAME_TEST_PASS old-run", "new-run"), false);
  assert.equal(successful([]), false);
  assert.equal(successful([{ status: "passed" }, { status: "blocked" }]), false);
  assert.equal(successful([{ status: "passed" }, { status: "skipped" }]), false);
});

test("child failures, spawn errors and timeout cannot be reported as passed", async () => {
  const dir = mkdtempSync(join(tmpdir(), "tongcraft-check-tests-"));
  try {
    const failure = await runCommand({ dir }, "failure", { command: process.execPath, args: ["-e", "process.exit(7)"] });
    assert.equal(failure.status, "failed");
    assert.equal(failure.code, 7);
    const missing = await runCommand({ dir }, "missing", { command: join(dir, "missing-command"), args: [] });
    assert.equal(missing.status, "failed");
    const timeout = await runCommand({ dir }, "timeout", { command: process.execPath, args: ["-e", "setInterval(() => {}, 1000)"] }, 100);
    assert.equal(timeout.status, "failed");
    assert.equal(timeout.message, "Timed out");
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
