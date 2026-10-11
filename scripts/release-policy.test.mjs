import test from "node:test";
import assert from "node:assert/strict";
import { releasePolicy, releaseTarget } from "./release-policy.mjs";

const push = { eventName: "push", ref: "refs/heads/main" };
test("documentation, badges, tests and CI configuration do not publish", () => {
  for (const path of [
    "README.md",
    "docs/GUIDE.md",
    ".github/workflows/ci.yml",
    "service/test/service.test.js",
    "client/src/gametest/java/Test.java",
    "scripts/release-policy.mjs",
  ]) {
    assert.equal(
      releasePolicy({ ...push, changedPaths: [path] }).publish,
      false,
      path,
    );
  }
});
test("runtime, dependencies and deployment changes publish even alongside docs", () => {
  for (const path of [
    "client/src/main/java/Client.java",
    "client/src/main/resources/fabric.mod.json",
    "client/build.gradle",
    "service/src/server.js",
    "service/package-lock.json",
    "service/Dockerfile",
    "gradle/wrapper/gradle-wrapper.properties",
    "compose.yaml",
    "deploy/Caddyfile",
  ]) {
    assert.equal(
      releasePolicy({ ...push, changedPaths: ["README.md", path] }).publish,
      true,
      path,
    );
  }
});
test("[skip release] suppresses publication while retaining CI checks", () => {
  assert.equal(
    releasePolicy({
      ...push,
      changedPaths: ["service/src/server.js"],
      commitMessage: "Fix sync [skip release]",
    }).publish,
    false,
  );
});
test("manual checks default to no publication and explicit publishing works on main", () => {
  const manual = { eventName: "workflow_dispatch", ref: "refs/heads/main" };
  assert.equal(releasePolicy(manual).publish, false);
  assert.equal(releasePolicy({ ...manual, publish: "false" }).publish, false);
  assert.equal(releasePolicy({ ...manual, publish: "true" }).publish, true);
  assert.equal(releasePolicy({ ...manual, publish: true }).publish, true);
});
test("PRs and unrelated branches cannot publish", () => {
  assert.equal(
    releasePolicy({
      ...push,
      eventName: "pull_request",
      changedPaths: ["service/src/server.js"],
    }).publish,
    false,
  );
  assert.equal(
    releasePolicy({
      ...push,
      ref: "refs/heads/codex/test",
      changedPaths: ["service/src/server.js"],
    }).publish,
    false,
  );
  assert.equal(
    releasePolicy({
      eventName: "workflow_dispatch",
      ref: "refs/heads/codex/test",
      publish: true,
    }).publish,
    false,
  );
});

test("the 26.3 branch automatically publishes changed client code as a prerelease", () => {
  const preview = { eventName: "push", ref: "refs/heads/minecraft-26.3" };
  assert.equal(
    releasePolicy({ ...preview, changedPaths: ["client/build.gradle"] })
      .publish,
    true,
  );
  assert.equal(
    releasePolicy({ ...preview, changedPaths: ["README.md"] }).publish,
    false,
  );
  assert.equal(
    releasePolicy({
      ...preview,
      changedPaths: ["client/build.gradle"],
      commitMessage: "Fix [skip release]",
    }).publish,
    false,
  );
  assert.equal(
    releasePolicy({
      ...preview,
      eventName: "pull_request",
      changedPaths: ["client/build.gradle"],
    }).publish,
    false,
  );
  assert.equal(
    releasePolicy({ ...preview, eventName: "workflow_dispatch", publish: true })
      .publish,
    true,
  );
});

test("the requested prerelease tag and version do not depend on CI run numbers", () => {
  for (const number of [16, 17, 100])
    assert.deepEqual(releaseTarget("refs/heads/minecraft-26.3", number), {
      version: "0.1.15-pre",
      tag: "pre-v0.1.15",
      prerelease: true,
    });
  assert.deepEqual(releaseTarget("refs/heads/main", 16), {
    version: "0.1.16",
    tag: "v0.1.16",
    prerelease: false,
  });
  assert.deepEqual(releaseTarget("refs/pull/1/merge", 17), {
    version: "0.1.17",
    tag: "v0.1.17",
    prerelease: false,
  });
  assert.throws(() => releaseTarget("refs/heads/main", "invalid"));
});
