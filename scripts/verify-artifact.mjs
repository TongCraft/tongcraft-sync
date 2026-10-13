import { readFileSync, statSync } from "node:fs";
import { basename, resolve } from "node:path";
import { inflateRawSync } from "node:zlib";
import { isDeepStrictEqual } from "node:util";
import { ROOT, cli, createContext, finish, readJson, record, result } from "./checks/common.mjs";
import { buildVersions, versionErrors } from "./verify-versions.mjs";

// Read JAR entries in memory without extracting paths into the user's filesystem.
export function jarEntries(bytes) {
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50 && i + 22 + bytes.readUInt16LE(i + 20) === bytes.length) { end = i; break; }
  }
  if (end < 0) throw new Error("Invalid JAR: ZIP directory is missing");
  const count = bytes.readUInt16LE(end + 10), offset = bytes.readUInt32LE(end + 16);
  if (count === 0xffff || offset === 0xffffffff || bytes.readUInt16LE(end + 4) !== 0 || bytes.readUInt16LE(end + 6) !== 0)
    throw new Error("ZIP64 and split archives are not supported by this checker");
  const entries = new Map();
  let cursor = offset;
  for (let i = 0; i < count; i++) {
    if (cursor + 46 > end || bytes.readUInt32LE(cursor) !== 0x02014b50) throw new Error("Invalid JAR central directory");
    const flags = bytes.readUInt16LE(cursor + 8), method = bytes.readUInt16LE(cursor + 10);
    const compressedSize = bytes.readUInt32LE(cursor + 20), size = bytes.readUInt32LE(cursor + 24);
    const nameLength = bytes.readUInt16LE(cursor + 28), extraLength = bytes.readUInt16LE(cursor + 30), commentLength = bytes.readUInt16LE(cursor + 32);
    const start = bytes.readUInt32LE(cursor + 42);
    const next = cursor + 46 + nameLength + extraLength + commentLength;
    if (next > end) throw new Error("Truncated JAR central directory");
    const name = bytes.toString("utf8", cursor + 46, cursor + 46 + nameLength);
    if (entries.has(name) || flags & 1) throw new Error(`Duplicate or encrypted JAR entry: ${name}`);
    if (start + 30 > offset || bytes.readUInt32LE(start) !== 0x04034b50) throw new Error(`Invalid JAR entry: ${name}`);
    const dataStart = start + 30 + bytes.readUInt16LE(start + 26) + bytes.readUInt16LE(start + 28);
    if (dataStart + compressedSize > offset) throw new Error(`Truncated JAR entry: ${name}`);
    entries.set(name, () => {
      if (size > 1024 * 1024) throw new Error(`JAR entry exceeds checker limit: ${name}`);
      const compressed = bytes.subarray(dataStart, dataStart + compressedSize);
      const value = method === 0 ? compressed : method === 8 ? inflateRawSync(compressed, { maxOutputLength: 1024 * 1024 }) : null;
      if (!value || value.length !== size) throw new Error(`Unsupported or corrupt JAR entry: ${name}`);
      return value;
    });
    cursor = next;
  }
  return entries;
}

export function artifactErrors(entries, build, sourceMod, expectedVersion) {
  const errors = [];
  const metadata = entries.get("fabric.mod.json");
  if (!metadata) return ["fabric.mod.json is missing"];
  const mod = JSON.parse(metadata().toString("utf8"));
  errors.push(...versionErrors(build, { ...mod, version: "${version}" }));
  if (mod.version !== expectedVersion || mod.version.includes("${")) errors.push(`Expected mod version ${expectedVersion}, got ${mod.version}`);
  for (const key of ["depends", "entrypoints"]) {
    if (!isDeepStrictEqual(mod[key], sourceMod[key])) errors.push(`Packaged ${key} differs from source metadata`);
  }
  for (const name of ["LICENSE", "assets/tongcraft_sync/lang/zh_cn.json", "assets/tongcraft_sync/lang/en_us.json"]) {
    if (!entries.has(name)) errors.push(`Missing resource: ${name}`);
  }
  for (const entry of Object.values(mod.entrypoints ?? {}).flat()) {
    if (typeof entry !== "string" || !entries.has(`${entry.replaceAll(".", "/")}.class`)) errors.push(`Entrypoint class missing: ${JSON.stringify(entry)}`);
  }
  if (mod.entrypoints?.["fabric-client-gametest"]) errors.push("Game test entrypoint leaked into production metadata");
  for (const [name, read] of entries) {
    if (/SyncGameTest(?:\$[^/]*)?\.class$|^bridge-test\/|^screenshots\//.test(name) || /(?:^|\/)tongcraft_sync_tests(?:\/|\.)/.test(name)) errors.push(`Test content leaked into JAR: ${name}`);
    if (name.endsWith(".class")) {
      const bytes = read();
      if (bytes.length < 8 || bytes.readUInt32BE(0) !== 0xcafebabe) errors.push(`Invalid class: ${name}`);
      else if (bytes.readUInt16BE(6) > Number(build.java) + 44) errors.push(`Class requires Java newer than ${build.java}: ${name}`);
    }
  }
  return errors;
}

export function verifyArtifact({ file, version } = {}) {
  const build = buildVersions(readFileSync(resolve(ROOT, "client/build.gradle"), "utf8"));
  const expectedVersion = version ?? build.defaultModVersion;
  if (!/^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$/.test(expectedVersion)) throw new Error("Invalid --version");
  const expectedName = `${build.archive}-${expectedVersion}.jar`;
  const path = resolve(ROOT, file ?? `client/build/libs/${expectedName}`);
  const errors = [];
  if (basename(path) !== expectedName) errors.push(`Expected artifact filename ${expectedName}; sources JARs are not installable mods`);
  if (statSync(path).size > 64 * 1024 * 1024) throw new Error("JAR exceeds checker limit of 64 MiB");
  const bytes = readFileSync(path);
  errors.push(...artifactErrors(jarEntries(bytes), build, readJson("client/src/main/resources/fabric.mod.json"), expectedVersion));
  return [result("artifact", errors.length ? "failed" : "passed", errors.length ? errors.join("; ") : `Verified ${path}`, { artifact: path })];
}

await cli(import.meta.url, "node scripts/verify-artifact.mjs [--file path] [--version 0.1.0]", ["file", "version"], async (opts) => {
  const ctx = createContext("artifact");
  try { record(ctx, verifyArtifact(opts)); }
  catch (error) { record(ctx, [result("artifact", "failed", error.message)]); }
  return finish(ctx);
});
