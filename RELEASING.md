# Releasing Skill Bill

Skill Bill uses tag-driven GitHub Releases.

The release contract is:

- create an annotated SemVer tag such as `v0.4.0`
- push the tag to GitHub
- let the `Release` workflow rerun validation and publish the GitHub Release

Pre-release tags such as `v0.5.0-rc.1` are also supported and publish GitHub prereleases.

## What a tag push builds

Pushing a SemVer tag now does more than publish notes. The `Release` workflow
builds, on matching host runners, the full per-OS asset set and attaches every
archive plus its `.sha256` sidecar to the GitHub Release for that tag:

- self-contained runtime images for **CLI** and **MCP**
  (`runtime-cli-<version>-<os>-<arch>.zip`, `runtime-mcp-<version>-<os>-<arch>.zip`)
- desktop installers (`SkillBill-<version>-<os>-<arch>.<ext>`):
  `.dmg` on macOS, `.msi` on Windows, `.deb` + `.rpm` on Linux

The `<os>-<arch>` token is the canonical set `macos-arm64` / `macos-x64` /
`windows-x64` / `linux-x64`, so downstream installers can resolve the correct
asset by host. Each artifact ships with a matching `<name>.sha256` file. The
build toolchain is pinned to JDK 17 (temurin) on every runner.

Builds run on host-matched runners because jlink and jpackage cannot
cross-compile: macOS arm64 on `macos-14`, macOS x64 on `macos-15-intel` (the
current Intel hosted image), Windows x64 on `windows-latest`, Linux x64 on
`ubuntu-latest`. Installers ship **unsigned for v1** (see
`runtime-kotlin/agent/decisions.md`).

> **macOS x64 note.** If the Intel-based hosted runner (`macos-15-intel`) becomes
> unavailable, drop that matrix leg; the workflow's `fail-fast: false` strategy
> lets the remaining hosts still publish, and the macOS x64 asset is simply
> absent for that release rather than silently mislabeled.

## Staging a release for downstream testing

Downstream work (such as the prebuilt-first installer) sometimes needs a real,
downloadable asset set before a stable tag is cut. There are two ways to produce
one, both of which publish a GitHub **prerelease** so it never looks like a
stable release:

1. **Prerelease tag** — push a `v*.*.*-rc.N` tag (e.g. `v0.5.0-rc.1`). The
   workflow validates the ref, builds every per-OS asset, and publishes a GitHub
   prerelease with all archives + `.sha256` files attached.
2. **Manual run (`workflow_dispatch`)** — trigger the `Release` workflow from the
   Actions tab and supply a `staging_version` label (e.g. `v0.5.0-rc.1`). No tag
   push is required; the workflow builds the same per-OS asset set and publishes a
   prerelease named from the input, so testers get a real downloadable asset set.

Both paths reuse the same build matrix and the same fail-closed validation gate as
a stable release.

## Release notes

### SKILL-32 Python Runtime Retirement

Skill Bill now uses packaged Kotlin as the only normal CLI and MCP runtime.
Rollback is to install the previous Skill Bill release, not to select a Python
runtime.

Removed environment variables:

- `SKILL_BILL_RUNTIME`
- `SKILL_BILL_MCP_RUNTIME`

Removed files:

- the entire historical runtime package and its packaging manifest
- `scripts/mcp_server_start.sh`
- legacy Python runtime modules for review, learnings, workflow telemetry,
  quality-check, PR description, and SQLite runtime support

## Versioning policy

Skill Bill should stay on pre-1.0 SemVer until the install surface, taxonomy, and stable entry points feel settled.

- bump `patch` for docs-only work, validator fixes, and non-breaking tooling or installer fixes
- bump `minor` for new skills, new platform coverage, new routing behavior, or other user-visible capability additions
- reserve `major` for intentional breaking changes to taxonomy, install behavior, or stable entry points

## Release checklist

1. Make sure the release commit is on `main`.
2. Run the local checks:

   ```bash
   skill-bill validate
   (cd runtime-kotlin && ./gradlew check)
   npx --yes agnix --strict .
   scripts/validate_agent_configs
   ```

3. Pick the next version tag.
4. Create an annotated tag:

   ```bash
   git tag -a v0.x.y -m "Release v0.x.y"
   ```

5. Push the tag:

   ```bash
   git push origin v0.x.y
   ```

6. Confirm the `Release` workflow succeeds and the GitHub Release appears with
   generated notes and the per-OS runtime-image + desktop-installer assets (each
   with its `.sha256`) attached.

## Installing from a release

If you want a stable install target instead of following `main`, clone the repo at a tag and run the normal installer:

```bash
TAG=v0.x.y
git clone --branch "$TAG" --depth 1 <this-repo> ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```
