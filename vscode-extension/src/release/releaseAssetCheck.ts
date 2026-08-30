import fs from "node:fs";
import path from "node:path";

export function expectedReleaseAssetNames(version: string): [string, string] {
  const base = `skill-bill-vscode-extension-${version}.vsix`;
  return [base, `${base}.sha256`];
}

export function validateStagedReleaseAssets(stagingDir: string, version: string): void {
  const assetNames = expectedReleaseAssetNames(version);
  for (const assetName of assetNames) {
    const assetPath = path.join(stagingDir, assetName);
    if (!fs.existsSync(assetPath) || !fs.statSync(assetPath).isFile()) {
      throw new Error(`Missing expected release asset: ${assetName}`);
    }
  }
  for (const entry of fs.readdirSync(stagingDir)) {
    const stagedPath = path.join(stagingDir, entry);
    if (!fs.statSync(stagedPath).isFile()) {
      continue;
    }
    if (!assetNames.includes(entry)) {
      throw new Error(`Unexpected release asset: ${entry}`);
    }
  }
}
