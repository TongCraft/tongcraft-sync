import { resolve } from "node:path";
import { doctor } from "./doctor.mjs";
import { ROOT, cli, createContext, finish, localConfig, packageCommand, record, result, runCommand, successful } from "./checks/common.mjs";

export function testEvidence(kind, output) {
  if (kind === "node") return Number(output.match(/^# tests (\d+)/m)?.[1]) > 0 && Number(output.match(/^# pass (\d+)/m)?.[1]) > 0;
  // Vitest's summary is emitted only after collection and execution; zero/all-skipped tests fail.
  return /Tests\s+\d+ passed/.test(output.replace(/\x1b\[[0-9;]*m/g, ""));
}

export async function checkServices(ctx, { target = "both" } = {}) {
  if (!["node", "cloudflare", "both"].includes(target)) throw new Error("--target must be node, cloudflare or both");
  const selected = doctor(target === "cloudflare" ? "cloudflare" : target === "node" ? "node" : "services");
  record(ctx, selected);
  if (!successful(selected)) {
    record(ctx, [result("service-tests", "blocked", "Fix service prerequisites before running tests")]);
    return;
  }
  const env = { ...process.env, FORCE_COLOR: "0", WRANGLER_SEND_METRICS: "false", CI: "true" };
  // Local checks never need account credentials, even if they are present in the parent shell.
  for (const key of ["CLOUDFLARE_API_TOKEN", "CLOUDFLARE_API_KEY", "CLOUDFLARE_EMAIL", "CLOUDFLARE_ACCOUNT_ID"]) delete env[key];
  if (target !== "cloudflare") {
    const tests = await runCommand(ctx, "node-tests", { command: process.execPath, args: ["--test", "--test-reporter=tap"], cwd: resolve(ROOT, "service"), env });
    if (tests.status === "passed" && !testEvidence("node", tests.output)) {
      tests.status = "failed";
      tests.message = "No passing Node tests were reported";
    }
    record(ctx, [tests]);
  }
  if (ctx.cancelled) return;
  if (target !== "node") {
    const tests = await runCommand(ctx, "cloudflare-tests", { ...packageCommand("cloudflare", "vitest", ["run"]), env });
    if (tests.status === "passed" && !testEvidence("vitest", tests.output)) {
      tests.status = "failed";
      tests.message = "No passing Vitest tests were reported";
    }
    record(ctx, [tests]);
    record(ctx, [await runCommand(ctx, "cloudflare-bundle", {
      ...packageCommand("cloudflare", "wrangler", ["deploy", "--dry-run", "--config", resolve(ROOT, localConfig), "--outdir", resolve(ctx.dir, "worker")]), env,
    })]);
  }
}

await cli(import.meta.url, "node scripts/check-services.mjs [--target node|cloudflare|both]", ["target"], async (opts) => {
  const ctx = createContext("services");
  try { await checkServices(ctx, opts); }
  catch (error) { record(ctx, [result("services", "failed", error.message)]); }
  return finish(ctx);
});
