import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
export const localConfig = "cloudflare/wrangler.local.jsonc";

export function readJson(path) {
  return JSON.parse(readFileSync(resolve(ROOT, path), "utf8"));
}

// Also used for Gradle, whose dependency URLs commonly use single quotes.
export function stripComments(source) {
  const chars = [...source];
  let quote = null;
  for (let i = 0; i < chars.length; i++) {
    if (quote) {
      if (chars[i] === "\\") i++;
      else if (chars[i] === quote) quote = null;
    } else if (chars[i] === '"' || chars[i] === "'") quote = chars[i];
    else if (chars[i] === "/" && chars[i + 1] === "/") {
      while (i < chars.length && chars[i] !== "\n") chars[i++] = " ";
    } else if (chars[i] === "/" && chars[i + 1] === "*") {
      chars[i++] = " ";
      chars[i] = " ";
      while (i + 1 < chars.length && !(chars[i + 1] === "*" && chars[i + 2] === "/")) {
        i++;
        if (chars[i] !== "\n") chars[i] = " ";
      }
      if (i + 2 >= chars.length) throw new Error("Unterminated JSONC comment");
      chars[++i] = " ";
      chars[++i] = " ";
    }
  }
  return chars.join("");
}

// Remove JSONC trailing commas outside quoted strings only.
export function parseJsonc(source) {
  const chars = [...stripComments(source)];
  let quoted = false;
  for (let i = 0; i < chars.length; i++) {
    if (quoted) {
      if (chars[i] === "\\") i++;
      else if (chars[i] === '"') quoted = false;
    } else if (chars[i] === '"') quoted = true;
    else if (chars[i] === ",") {
      let next = i + 1;
      while (/\s/.test(chars[next] ?? "") && next < chars.length) next++;
      if (chars[next] === "}" || chars[next] === "]") chars[i] = " ";
    }
  }
  return JSON.parse(chars.join(""));
}

export function readConfig(path = localConfig) {
  return parseJsonc(readFileSync(resolve(ROOT, path), "utf8"));
}

export function options(argv, allowed = []) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i].replace(/^--/, "");
    if (!argv[i].startsWith("--") || !["help", ...allowed].includes(key))
      throw new Error(`Unknown argument: ${argv[i]}`);
    if (Object.hasOwn(result, key)) throw new Error(`Duplicate argument: --${key}`);
    if (key === "help") result.help = true;
    else {
      if (!argv[i + 1] || argv[i + 1].startsWith("--")) throw new Error(`Missing value for --${key}`);
      result[key] = argv[++i];
    }
  }
  return result;
}

export function isMain(url) {
  return process.argv[1] && url === pathToFileURL(resolve(process.argv[1])).href;
}

export function versionAtLeast(actual, minimum) {
  const a = actual.replace(/^v/, "").split(".").map(Number);
  const b = minimum.split(".").map(Number);
  if (a.some(Number.isNaN) || b.some(Number.isNaN)) return false;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) > (b[i] ?? 0);
  }
  return true;
}

export function probe(command, args, cwd = ROOT, env = process.env) {
  const result = spawnSync(command, args, { cwd, env, encoding: "utf8", windowsHide: true, timeout: 15000 });
  return { ok: !result.error && result.status === 0, output: (result.stdout ?? "") + (result.stderr ?? "") };
}

export function selectJava(env = process.env) {
  const exe = process.platform === "win32" ? ".exe" : "";
  const homes = [];
  if (env.JAVA_HOME) homes.push(env.JAVA_HOME);
  const pathJava = probe("java", ["-XshowSettings:properties", "-version"]);
  const pathHome = pathJava.output.match(/^\s*java\.home\s*=\s*(.+)$/m)?.[1].trim();
  if (pathHome && !homes.includes(pathHome)) homes.push(pathHome);
  for (const home of homes) {
    const java = join(home, "bin", `java${exe}`);
    const compiler = probe(join(home, "bin", `javac${exe}`), ["-version"]);
    const major = Number(compiler.output.match(/javac (\d+)/)?.[1]);
    const runtime = probe(java, ["-version"]);
    const runtimeMajor = Number(runtime.output.match(/version "(\d+)/)?.[1]);
    if (compiler.ok && runtime.ok && major >= 25 && runtimeMajor >= 25)
      return { home, major, java, jar: join(home, "bin", `jar${exe}`) };
  }
  return null;
}

export function gradleCommand(args, java) {
  const env = { ...process.env, JAVA_HOME: java.home };
  if (process.platform !== "win32") return { command: "sh", args: [join(ROOT, "gradlew"), ...args], env };
  const quote = (value) => `'${value.replaceAll("'", "''")}'`;
  return {
    command: "powershell.exe",
    args: ["-NoProfile", "-NonInteractive", "-Command",
      `& ${[join(ROOT, "gradlew.bat"), ...args].map(quote).join(" ")}; exit $LASTEXITCODE`],
    env,
  };
}

export function packageCommand(pkg, name, args) {
  const manifestPath = join(ROOT, pkg, "node_modules", name, "package.json");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  const bin = typeof manifest.bin === "string" ? manifest.bin : manifest.bin[name];
  if (!bin) throw new Error(`No ${name} command in ${manifestPath}`);
  return { command: process.execPath, args: [resolve(dirname(manifestPath), bin), ...args], cwd: join(ROOT, pkg) };
}

export function result(id, status, message, extra = {}) {
  return { id, status, message, ...extra };
}

export function successful(results) {
  return results.length > 0 && results.every((entry) => entry.status === "passed" || entry.status === "not-required");
}

function git(args) {
  const entry = probe("git", args);
  return entry.ok ? entry.output.trim() : null;
}

function sourceState() {
  const untracked = git(["ls-files", "--others", "--exclude-standard", "-z"])?.split("\0").filter(Boolean) ?? [];
  const untrackedHashes = {};
  for (const path of untracked) {
    try { untrackedHashes[path] = createHash("sha256").update(readFileSync(resolve(ROOT, path))).digest("hex"); }
    catch { untrackedHashes[path] = "unreadable"; }
  }
  return {
    revision: git(["rev-parse", "HEAD"]),
    worktree: git(["status", "--short"]),
    diffHash: createHash("sha256").update(git(["diff", "HEAD", "--binary"]) ?? "").digest("hex"),
    untrackedHashes,
  };
}

export function createContext(profile) {
  const dir = join(ROOT, ".checks", `${new Date().toISOString().replace(/[:.]/g, "-")}-${process.pid}`);
  mkdirSync(dir, { recursive: true });
  return { profile, dir, startedAt: new Date().toISOString(), results: [], ...sourceState() };
}

export function record(ctx, entries) {
  for (const entry of entries) {
    ctx.results.push(entry);
    console.log(`[${entry.status}] ${entry.id}: ${entry.message}`);
  }
}

export function finish(ctx) {
  const current = sourceState();
  const changed = ["revision", "diffHash", "untrackedHashes"].some((key) => JSON.stringify(ctx[key]) !== JSON.stringify(current[key]));
  if (changed) record(ctx, [result("source-changed", "blocked", "Source changed while checks were running; rerun against the final code")]);
  const report = { ...ctx, finishedAt: new Date().toISOString(), sourceAtFinish: current, ok: successful(ctx.results) };
  writeFileSync(join(ctx.dir, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(`Report: ${join(ctx.dir, "report.json")}`);
  console.log(report.ok ? "Selected checks passed; see the documented limits of this profile." : "Selected checks did not complete successfully.");
  return report.ok ? 0 : 1;
}

// Always wait for process closure before returning; on Windows terminate only this child tree.
export async function runCommand(ctx, id, spec, timeoutMs = 10 * 60 * 1000) {
  if (ctx.cancelled) return result(id, "blocked", "Check run was interrupted");
  const started = Date.now();
  let output = "", timedOut = false, interrupted = false;
  const commandLabel = `${spec.command} ${spec.args.map((arg) => JSON.stringify(arg)).join(" ")}`;
  console.log(`\n${id}: ${commandLabel}`);
  return await new Promise((resolvePromise) => {
    const child = spawn(spec.command, spec.args, {
      cwd: spec.cwd ?? ROOT, env: spec.env ?? process.env,
      windowsHide: true, detached: process.platform !== "win32", stdio: ["ignore", "pipe", "pipe"],
    });
    let spawnError;
    const stop = () => {
      if (!child.pid) return;
      if (process.platform === "win32")
        spawnSync("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], { windowsHide: true, timeout: 15000, stdio: "ignore" });
      else {
        try { process.kill(-child.pid, "SIGKILL"); } catch (error) { if (error.code !== "ESRCH") spawnError = error; }
      }
    };
    const interrupt = () => { interrupted = true; ctx.cancelled = true; stop(); };
    process.once("SIGINT", interrupt);
    process.once("SIGTERM", interrupt);
    const timer = setTimeout(() => { timedOut = true; stop(); }, timeoutMs);
    for (const [stream, destination] of [[child.stdout, process.stdout], [child.stderr, process.stderr]]) {
      stream.on("data", (bytes) => { output += bytes.toString(); destination.write(bytes); });
    }
    child.on("error", (error) => { spawnError = error; });
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      process.removeListener("SIGINT", interrupt);
      process.removeListener("SIGTERM", interrupt);
      const log = join(ctx.dir, `${id}.log`);
      writeFileSync(log, `${commandLabel}\n${output}${spawnError ? `\n${spawnError.message}` : ""}`);
      const passed = code === 0 && !timedOut && !interrupted && !spawnError;
      resolvePromise(result(id, passed ? "passed" : "failed",
        timedOut ? "Timed out" : interrupted ? "Interrupted" : spawnError?.message ?? `Exit ${code}${signal ? ` (${signal})` : ""}`,
        { command: commandLabel, code, durationMs: Date.now() - started, log, output }));
    });
  });
}

export async function cli(url, usage, allowed, run) {
  if (!isMain(url)) return;
  try {
    const opts = options(process.argv.slice(2), allowed);
    if (opts.help) { console.log(usage); return; }
    process.exitCode = await run(opts);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
