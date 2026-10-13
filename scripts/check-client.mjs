import { doctor } from "./doctor.mjs";
import { verifyArtifact } from "./verify-artifact.mjs";
import { verifyVersions } from "./verify-versions.mjs";
import { cli, createContext, finish, gradleCommand, record, result, runCommand, selectJava, successful } from "./checks/common.mjs";

export async function checkClient(ctx, { version = "0.1.0" } = {}) {
  if (!/^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$/.test(version)) throw new Error("Invalid --version");
  const prerequisites = [...doctor("client"), ...verifyVersions()];
  record(ctx, prerequisites);
  if (!successful(prerequisites)) {
    record(ctx, [result("client-build", "blocked", "Fix client prerequisites before building"), result("artifact", "blocked", "No current build was verified")]);
    return false;
  }
  const build = await runCommand(ctx, "client-build", gradleCommand(["--no-daemon", ":client:build", `-PmodVersion=${version}`], selectJava()), 20 * 60 * 1000);
  record(ctx, [build]);
  if (build.status !== "passed") {
    record(ctx, [result("artifact", "blocked", "Build failed; old artifacts are not accepted")]);
    return false;
  }
  const artifacts = verifyArtifact({ version });
  record(ctx, artifacts);
  return successful(artifacts);
}

await cli(import.meta.url, "node scripts/check-client.mjs [--version 0.1.0]", ["version"], async (opts) => {
  const ctx = createContext("client");
  try { await checkClient(ctx, opts); }
  catch (error) { record(ctx, [result("client", "failed", error.message)]); }
  return finish(ctx);
});
