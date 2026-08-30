import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { describe, it } from "mocha";
import {
  CliExecutableSource,
  EXECUTABLE_NAME,
  resolveOverride,
} from "../infrastructure/cli/CliExecutableResolver";

describe("CliExecutableResolver", () => {
  it("reports misconfigured when override path is unusable and does not fall back", () => {
    const binDir = tempBinDirWithLauncher();
    const environment = environmentOf(
      ["PATH", binDir],
      ["HOME", path.join(binDir, "..", "..")],
    );
    const resolution = resolveOverride(path.join(binDir, "not-installed"), environment);
    assert.equal(resolution.kind, "misconfigured");
  });

  it("resolves launcher from install directory when absent from PATH", () => {
    const binDir = tempBinDirWithLauncher();
    const environment = environmentOf(
      ["PATH", "/usr/bin"],
      ["HOME", path.join(binDir, "..", "..")],
    );
    const resolution = resolveOverride(undefined, environment);
    assert.deepEqual(resolution, {
      kind: "found",
      path: path.join(binDir, EXECUTABLE_NAME),
      source: CliExecutableSource.INSTALL_DIRECTORY,
    });
  });
});

function environmentOf(...entries: [string, string][]): { value(name: string): string | undefined } {
  const values = Object.fromEntries(entries);
  return { value: (name: string) => values[name] };
}

function tempBinDirWithLauncher(): string {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "skill-bill-home-"));
  const binDir = path.join(home, ".local", "bin");
  fs.mkdirSync(binDir, { recursive: true });
  const launcher = path.join(binDir, EXECUTABLE_NAME);
  fs.writeFileSync(launcher, "#!/bin/sh\n");
  fs.chmodSync(launcher, 0o755);
  return binDir;
}
