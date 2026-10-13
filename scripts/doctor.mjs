import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import {
  ROOT, cli, createContext, finish, localConfig, packageCommand, probe,
  readConfig, readJson, record, result, selectJava, versionAtLeast,
} from "./checks/common.mjs";

export function dependencyChecks(pkg) {
  const manifest = readJson(`${pkg}/package.json`);
  const lock = readJson(`${pkg}/package-lock.json`);
  const failures = [];
  for (const section of ["dependencies", "devDependencies"]) {
    for (const [name, wanted] of Object.entries(manifest[section] ?? {})) {
      const locked = lock.packages?.[`node_modules/${name}`]?.version;
      const declared = lock.packages?.[""]?.[section]?.[name];
      const installed = resolve(ROOT, pkg, "node_modules", name, "package.json");
      if (!locked || declared !== wanted) failures.push(`${name}: package.json and lockfile disagree`);
      else if (!existsSync(installed)) failures.push(`${name}: not installed`);
      else if (JSON.parse(readFileSync(installed, "utf8")).version !== locked)
        failures.push(`${name}: installed version differs from lockfile ${locked}`);
    }
  }
  return result(`${pkg}-dependencies`, failures.length ? "blocked" : "passed",
    failures.length ? `${failures.join("; ")}. Run npm --prefix ${pkg} ci --ignore-scripts.` : "Direct dependencies match the lockfile");
}

export function validateCloudflareConfig(config, { local = true } = {}) {
  const failures = [];
  if (!config.name || !config.main || !/^\d{4}-\d{2}-\d{2}$/.test(config.compatibility_date ?? ""))
    failures.push("name, main and compatibility_date are required");
  if (!/^[a-f0-9]{32}$/i.test((config.vars?.ADMIN_UUID ?? "").replaceAll("-", "")))
    failures.push("ADMIN_UUID must be a Minecraft UUID");
  try {
    const url = new URL(config.vars?.LIBRARY_URL);
    const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
    if (local ? !(loopback || url.protocol === "https:" && url.hostname.endsWith(".invalid")) : url.protocol !== "https:")
      failures.push(local ? "Local LIBRARY_URL must use a loopback host or an HTTPS .invalid test host" : "LIBRARY_URL must use HTTPS");
    if (!local && /YOUR_|REPLACE_|_/i.test(url.hostname)) failures.push("Replace the example LIBRARY_URL hostname");
  } catch { failures.push("LIBRARY_URL must be a URL"); }
  if (!config.durable_objects?.bindings?.some((b) => b.name === "ROOM" && b.class_name === "SyncRoom"))
    failures.push("ROOM must bind to SyncRoom");
  if (!config.migrations?.some((m) => m.new_sqlite_classes?.includes("SyncRoom")))
    failures.push("SyncRoom SQLite migration is missing");
  if (!config.r2_buckets?.some((b) => b.binding === "FILES" && b.bucket_name))
    failures.push("FILES R2 binding is missing");
  if (local) {
    if (config.routes?.length || config.workers_dev !== false)
      failures.push("Local configuration must have no routes and workers_dev=false");
    if (config.account_id || config.env && Object.keys(config.env).length)
      failures.push("Local configuration must not select an account or named environments");
    const bindings = [...(config.r2_buckets ?? []), ...(config.kv_namespaces ?? []), ...(config.d1_databases ?? []), ...(config.services ?? [])];
    if (bindings.some((b) => b.remote === true)) failures.push("Remote bindings are forbidden for local checks");
  } else {
    if (!/^[a-f0-9]{32}$/i.test(config.account_id ?? "")) failures.push("Set the deployment account_id explicitly");
    if (!(config.workers_dev === true || config.routes?.length)) failures.push("Enable workers_dev or configure a route");
    if (config.vars?.ADMIN_UUID === "00000000000040008000000000000001")
      failures.push("Replace the test administrator UUID");
  }
  return failures;
}

export function nodeCheck() {
  return result("node", versionAtLeast(process.versions.node, "24.13.0") ? "passed" : "blocked",
    `Node ${process.versions.node}; required >=24.13.0. Use a suitable Node executable to run these scripts.`);
}

export function bucketListed(output, name) {
  return output.replace(/\x1b\[[0-9;]*m/g, "").split(/\r?\n/)
    .some((line) => line.match(/^\s*name:\s*(.+?)\s*$/)?.[1] === name);
}

export function doctor(profile = "client", configPath = localConfig) {
  if (!["client", "node", "cloudflare", "services", "all", "deploy"].includes(profile)) throw new Error(`Unknown doctor profile: ${profile}`);
  const entries = [nodeCheck()];
  if (["client", "all"].includes(profile)) {
    const java = selectJava();
    entries.push(result("jdk", java ? "passed" : "blocked", java
      ? `JDK ${java.major}: ${java.home}${process.env.JAVA_HOME && resolve(process.env.JAVA_HOME) !== resolve(java.home) ? " (using PATH JDK instead of JAVA_HOME)" : ""}`
      : "No JDK 25+ found through JAVA_HOME or PATH. Set JAVA_HOME to a suitable JDK."));
    entries.push(result("gradle-wrapper", ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"].every((p) => existsSync(resolve(ROOT, p))) ? "passed" : "blocked", "Gradle wrapper files must be present"));
    if (process.platform === "linux" && !process.env.DISPLAY && !process.env.WAYLAND_DISPLAY)
      entries.push(result("game-display", "not-required", "Build checks can run; game checks need a working display, for example xvfb-run"));
  }
  if (["node", "services", "all"].includes(profile)) entries.push(dependencyChecks("service"));
  if (["cloudflare", "services", "all", "deploy"].includes(profile)) {
    entries.push(dependencyChecks("cloudflare"));
    const config = readConfig(configPath);
    const failures = validateCloudflareConfig(config, { local: profile !== "deploy" });
    if (!existsSync(resolve(dirname(resolve(ROOT, configPath)), config.main ?? "missing"))) failures.push("Worker main file does not exist");
    entries.push(result("cloudflare-config", failures.length ? "blocked" : "passed", failures.length ? failures.join("; ") : `${configPath}: bindings and variables are valid`));
    if (profile === "deploy") {
      if (failures.length || entries.some((entry) => entry.status === "blocked")) {
        entries.push(result("cloudflare-account", "blocked", "Fix local prerequisites before checking the account"));
      } else {
        // Read-only account/resource queries. Do not return raw CLI output or credentials.
        const env = { ...process.env, CLOUDFLARE_ACCOUNT_ID: config.account_id, WRANGLER_SEND_METRICS: "false", CI: "true" };
        const whoami = packageCommand("cloudflare", "wrangler", ["whoami", "--json", "--account", config.account_id, "--config", resolve(ROOT, configPath)]);
        const account = probe(whoami.command, whoami.args, whoami.cwd, env);
        const expected = config.r2_buckets.find((b) => b.binding === "FILES").bucket_name;
        const bucketCommand = packageCommand("cloudflare", "wrangler", ["r2", "bucket", "list", "--config", resolve(ROOT, configPath)]);
        const buckets = probe(bucketCommand.command, bucketCommand.args, bucketCommand.cwd, env);
        const present = bucketListed(buckets.output, expected);
        entries.push(result("cloudflare-account", account.ok ? "passed" : "blocked", account.ok ? "Wrangler account query succeeded" : "Wrangler could not query the account. Log in or configure a token locally."));
        entries.push(result("cloudflare-bucket", buckets.ok && present ? "passed" : "blocked", buckets.ok && present ? "Configured FILES bucket is accessible" : "Configured FILES bucket could not be confirmed; check the account, R2 permissions and bucket name"));
      }
      entries.push(result("deployment-limit", "not-required", "Read-only preflight; domain ownership, deployment and live operation are not verified"));
    } else entries.push(result("cloudflare-login", "not-required", "Local simulation does not require a Cloudflare login or an existing remote bucket"));
  }
  return entries;
}

await cli(import.meta.url, "node scripts/doctor.mjs --profile client|node|cloudflare|services|all|deploy [--config path]", ["profile", "config"], async (opts) => {
  const profile = opts.profile ?? "client";
  if (profile === "deploy" && !opts.config) throw new Error("Deploy preflight requires --config pointing to your own deployment configuration");
  const ctx = createContext(`doctor:${profile}`);
  try { record(ctx, doctor(profile, opts.config)); }
  catch (error) { record(ctx, [result("doctor", "blocked", error.message)]); }
  return finish(ctx);
});
