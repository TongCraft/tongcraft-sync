import { nodeCheck } from "./doctor.mjs";
import { checkClient } from "./check-client.mjs";
import { checkServices, testEvidence } from "./check-services.mjs";
import { checkGame } from "./check-game.mjs";
import { verifyVersions } from "./verify-versions.mjs";
import { ROOT, cli, createContext, finish, record, result, runCommand, successful } from "./checks/common.mjs";
import { resolve } from "node:path";

await cli(import.meta.url, "node scripts/check.mjs --profile quick|client|services|cloudflare|game|port|all [--version 0.1.0]", ["profile", "version"], async (opts) => {
  const profile = opts.profile ?? "client";
  if (!["quick", "client", "services", "cloudflare", "game", "port", "all"].includes(profile)) throw new Error(`Unknown profile: ${profile}`);
  const ctx = createContext(profile);
  try {
    if (["quick", "all"].includes(profile)) {
      const prerequisites = [nodeCheck(), ...verifyVersions()];
      record(ctx, prerequisites);
      if (successful(prerequisites)) {
        const tests = await runCommand(ctx, "script-tests", { command: process.execPath, args: ["--test", "--test-reporter=tap", resolve(ROOT, "scripts/checks.test.mjs"), resolve(ROOT, "scripts/release-policy.test.mjs")] });
        if (tests.status === "passed" && !testEvidence("node", tests.output)) {
          tests.status = "failed";
          tests.message = "No passing script tests were reported";
        }
        record(ctx, [tests]);
      } else record(ctx, [result("script-tests", "blocked", "Fix quick-check prerequisites first")]);
    }
    if (["client", "port", "all"].includes(profile)) await checkClient(ctx, opts);
    if (ctx.cancelled) return finish(ctx);
    if (["services", "cloudflare", "all"].includes(profile)) await checkServices(ctx, { target: profile === "cloudflare" ? "cloudflare" : "both" });
    if (ctx.cancelled) return finish(ctx);
    if (["game", "port", "all"].includes(profile)) {
      const buildFailed = ctx.results.some((entry) => ["client-build", "artifact"].includes(entry.id) && entry.status !== "passed");
      if (buildFailed) record(ctx, [result("game-tests", "blocked", "Client build/artifact check did not pass")]);
      else await checkGame(ctx);
    }
  } catch (error) { record(ctx, [result("check-runner", "failed", error.message)]); }
  return finish(ctx);
});
