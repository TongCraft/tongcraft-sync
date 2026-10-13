import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { ROOT, cli, createContext, finish, readJson, record, result, stripComments, versionAtLeast } from "./checks/common.mjs";

function capture(source, expression, label) {
  const matches = [...source.matchAll(new RegExp(expression.source, "g"))];
  if (matches.length !== 1) throw new Error(`Cannot read a unique ${label} from client/build.gradle; update the checker when changing build syntax`);
  return matches[0][1];
}

// Deliberately support this repository's literal Gradle declarations. Unknown syntax fails closed.
export function buildVersions(source) {
  source = stripComments(source);
  const dependency = (coordinate, label) => capture(source, new RegExp(`${coordinate.replaceAll(".", "\\.")}:([^'"\\s]+)['"]`), label);
  return {
    minecraft: dependency("com.mojang:minecraft", "Minecraft"),
    loader: dependency("net.fabricmc:fabric-loader", "Fabric Loader"),
    api: dependency("net.fabricmc.fabric-api:fabric-api", "Fabric API"),
    litematica: capture(source, /fi\.dy\.masa\.litematica:litematica-fabric-([^:'"\s]+):([^'"\s]+)['"]/, "Litematica artifact"),
    litematicaVersion: capture(source, /fi\.dy\.masa\.litematica:litematica-fabric-[^:'"\s]+:([^'"\s]+)['"]/, "Litematica version"),
    malilib: capture(source, /fi\.dy\.masa\.malilib:malilib-fabric-([^:'"\s]+):([^'"\s]+)['"]/, "MaLiLib artifact"),
    malilibVersion: capture(source, /fi\.dy\.masa\.malilib:malilib-fabric-[^:'"\s]+:([^'"\s]+)['"]/, "MaLiLib version"),
    java: capture(source, /options\.release\s*=\s*(\d+)/, "Java release target"),
    archive: capture(source, /archivesName\s*=\s*['"]([^'"]+)['"]/, "archive name"),
    defaultModVersion: capture(source, /gradleProperty\(['"]modVersion['"]\)\.getOrElse\(['"]([^'"]+)['"]\)/, "default mod version"),
  };
}

export function versionErrors(build, mod) {
  const errors = [];
  const expect = (condition, message) => { if (!condition) errors.push(message); };
  expect(mod.id === "tongcraft_sync" && mod.environment === "client", "Mod id/environment changed unexpectedly");
  expect(mod.version === "${version}", "Source mod version must use ${version}");
  expect(mod.depends?.minecraft === build.minecraft, "Minecraft dependency differs from the build target");
  expect(build.litematica === build.minecraft, "Litematica artifact targets a different Minecraft version");
  expect(build.malilib === build.minecraft, "MaLiLib artifact targets a different Minecraft version");
  expect(build.api.endsWith(`+${build.minecraft}`), "Fabric API targets a different Minecraft version");
  expect(build.archive === `tongcraft-sync-${build.minecraft}`, "Archive name differs from the Minecraft target");
  for (const [key, actual] of [["fabricloader", build.loader], ["litematica", build.litematicaVersion], ["malilib", build.malilibVersion], ["java", build.java]]) {
    const requirement = mod.depends?.[key];
    expect(typeof requirement === "string" && /^>=\d+(?:\.\d+)*$/.test(requirement) && versionAtLeast(actual, requirement.slice(2)), `${key}: compiled version does not meet the declared minimum`);
  }
  expect(mod.depends?.["fabric-api"] !== undefined, "Fabric API runtime dependency is missing");
  expect(mod.entrypoints?.client?.includes("org.tongcraft.sync.TongCraftClient"), "Client entrypoint is missing");
  return errors;
}

export function verifyVersions() {
  const build = buildVersions(readFileSync(resolve(ROOT, "client/build.gradle"), "utf8"));
  const errors = versionErrors(build, readJson("client/src/main/resources/fabric.mod.json"));
  const workflow = readFileSync(resolve(ROOT, ".github/workflows/ci.yml"), "utf8");
  const advertised = [...workflow.matchAll(/Minecraft(?: Java)? ([\d.]+)/g)].map((m) => m[1]);
  if (advertised.some((v) => v !== build.minecraft)) errors.push("Release text advertises a different Minecraft version");
  return [result("versions", errors.length ? "failed" : "passed", errors.length ? errors.join("; ") : `Minecraft ${build.minecraft}, Java ${build.java}, archive ${build.archive}`, { build })];
}

await cli(import.meta.url, "node scripts/verify-versions.mjs", [], async () => {
  const ctx = createContext("versions");
  record(ctx, verifyVersions());
  return finish(ctx);
});
