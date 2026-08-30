import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "mocha";
import { expectedReleaseAssetNames, validateStagedReleaseAssets } from "../release/releaseAssetCheck";

describe("releaseAssetCheck", () => {
  it("accepts exactly one VSIX and one sha256 sidecar", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vsix-stage-"));
    const version = "0.2.0";
    const names = expectedReleaseAssetNames(version);
    for (const name of names) {
      fs.writeFileSync(path.join(dir, name), name.endsWith(".sha256") ? "deadbeef\n" : "vsix");
    }
    assert.doesNotThrow(() => validateStagedReleaseAssets(dir, version));
  });

  it("fails when an expected asset is missing", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vsix-stage-bad-"));
    const version = "0.2.0";
    const [vsixName] = expectedReleaseAssetNames(version);
    fs.writeFileSync(path.join(dir, vsixName), "vsix");
    assert.throws(() => validateStagedReleaseAssets(dir, version));
  });

  it("fails when an extra file is present", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vsix-stage-extra-"));
    const version = "0.2.0";
    const names = expectedReleaseAssetNames(version);
    for (const name of names) {
      fs.writeFileSync(path.join(dir, name), name.endsWith(".sha256") ? "deadbeef\n" : "vsix");
    }
    fs.writeFileSync(path.join(dir, "extra.txt"), "pollution");
    assert.throws(
      () => validateStagedReleaseAssets(dir, version),
      /Unexpected release asset: extra\.txt/,
    );
  });
});
