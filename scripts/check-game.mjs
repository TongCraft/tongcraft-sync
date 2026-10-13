import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { doctor } from "./doctor.mjs";
import { verifyVersions } from "./verify-versions.mjs";
import { ROOT, cli, createContext, finish, gradleCommand, record, result, runCommand, selectJava, successful } from "./checks/common.mjs";

export function gameEvidence(output, runId) {
  const marker = `TONGCRAFT_SYNC_GAME_TEST_PASS ${runId}`;
  return output.replace(/\x1b\[[0-9;]*m/g, "").split(/\r?\n/)
    .some((line) => line.trim() === marker || line.trim().endsWith(`[STDOUT]: ${marker}`));
}

export async function checkGame(ctx) {
  const prerequisites = [...doctor("client"), ...verifyVersions()];
  if (process.platform === "linux" && !process.env.DISPLAY && !process.env.WAYLAND_DISPLAY)
    prerequisites.push(result("game-display", "blocked", "Client game tests need a working display; run under xvfb-run when appropriate"));
  record(ctx, prerequisites);
  if (!successful(prerequisites)) {
    record(ctx, [result("game-tests", "blocked", "Fix game prerequisites before starting Minecraft")]);
    return;
  }
  const runId = basename(ctx.dir);
  const started = Date.now();
  const spec = gradleCommand(["--no-daemon", ":client:runClientGameTest", "--rerun-tasks"], selectJava());
  spec.env.TONGCRAFT_CHECK_RUN = runId;
  const tests = await runCommand(ctx, "game-tests", spec, 20 * 60 * 1000);
  if (tests.status === "passed" && !gameEvidence(tests.output, runId)) {
    tests.status = "failed";
    tests.message = "Minecraft exited without this run's completed Sync test marker";
  }
  const source = resolve(ROOT, "client/build/run/clientGameTest/screenshots");
  const screenshots = [];
  if (existsSync(source)) {
    const destination = join(ctx.dir, "screenshots");
    mkdirSync(destination, { recursive: true });
    for (const name of readdirSync(source)) {
      const path = join(source, name), stat = statSync(path);
      if (stat.isFile() && name.endsWith(".png") && stat.mtimeMs >= started) {
        copyFileSync(path, join(destination, name));
        screenshots.push(join(destination, name));
      }
    }
  }
  tests.screenshots = screenshots;
  record(ctx, [tests, result("game-limits", "not-required", "Existing suite covers UI, single-block placement, preferences, printing, HTTP download and late-response disconnect. Multi-region geometry and two online accounts still require further validation.")]);
}

await cli(import.meta.url, "node scripts/check-game.mjs", [], async () => {
  const ctx = createContext("game");
  try { await checkGame(ctx); }
  catch (error) { record(ctx, [result("game", "failed", error.message)]); }
  return finish(ctx);
});
