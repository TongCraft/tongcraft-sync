import { execFileSync } from "node:child_process";
import { appendFileSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const distributable =
  /^(?:client\/(?:src\/main\/|build\.gradle$)|service\/(?:src\/|package(?:-lock)?\.json$|Dockerfile$)|gradle\/|gradle\.properties$|settings\.gradle$|gradlew(?:\.bat)?$|compose\.yaml$|deploy\/|(?:build-client|start-sync-local)\.ps1$|LICENSE$)/;

export function releasePolicy({
  eventName,
  ref,
  publish,
  commitMessage = "",
  changedPaths = [],
}) {
  if (ref !== "refs/heads/main")
    return { publish: false, reason: "Only main can publish." };
  if (eventName === "workflow_dispatch") {
    return {
      publish: publish === true || publish === "true",
      reason: "Manual publish selection.",
    };
  }
  if (eventName !== "push")
    return { publish: false, reason: "Checks only for this event." };
  if (/\[skip[ -]release\]/i.test(commitMessage)) {
    return { publish: false, reason: "Commit requested [skip release]." };
  }
  const changed = changedPaths.some((path) => distributable.test(path));
  return {
    publish: changed,
    reason: changed
      ? "Distributable files changed."
      : "No distributable files changed.",
  };
}

function run() {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, "utf8"));
  let changedPaths = [];
  if (process.env.GITHUB_EVENT_NAME === "push") {
    const before = event.before;
    const commit = process.env.GITHUB_SHA;
    const args =
      !before || /^0+$/.test(before)
        ? ["ls-tree", "-r", "--name-only", "-z", commit]
        : ["diff", "--name-only", "-z", before, commit];
    changedPaths = execFileSync("git", args, { encoding: "utf8" })
      .split("\0")
      .filter(Boolean);
  }
  const result = releasePolicy({
    eventName: process.env.GITHUB_EVENT_NAME,
    ref: process.env.GITHUB_REF,
    publish: event.inputs?.publish,
    commitMessage: event.head_commit?.message,
    changedPaths,
  });
  appendFileSync(process.env.GITHUB_OUTPUT, `publish=${result.publish}\n`);
  console.log(
    `Release: ${result.publish ? "publish" : "skip"} — ${result.reason}`,
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
)
  run();
