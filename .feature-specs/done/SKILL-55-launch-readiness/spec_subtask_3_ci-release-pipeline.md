# SKILL-55 Subtask 3 - CI Release Pipeline → GitHub Releases

Parent spec: [.feature-specs/SKILL-55-launch-readiness/spec.md](./spec.md)
Issue key: SKILL-55
Subtask order: 3 of 6
Depends on: subtask 1, subtask 2
Branch model: same-branch, commit per subtask

## Purpose

Publish the subtask 1 (runtime images) and subtask 2 (desktop installers)
artifacts as downloadable GitHub Release assets, built on matching host runners.
Today the `Release` workflow reruns validation and publishes generated notes
(`RELEASING.md`); it does not build per-OS binaries. This subtask makes a pushed
tag produce the full artifact set the prebuilt-first installer (subtask 4) and
the README quickstart (subtask 5) depend on.

## Scope

In scope:

- Extend `.github/workflows/release.yml` (or add a clearly-linked companion
  workflow) so a pushed SemVer tag (`v0.x.y`, and `-rc.N` prereleases) builds
  artifacts on a runner matrix:
  - `macos-latest` (Apple Silicon) → macOS arm64 runtime image + `.dmg`
  - a macOS x64 runner (or cross-arch where supported) → macOS x64 runtime image
  - `windows-latest` → Windows x64 runtime image + `.msi`
  - `ubuntu-latest` → Linux x64 runtime image + `.deb`/`.rpm`
- Build each OS's runtime image (subtask 1 task) and desktop installer
  (subtask 2 task) on its matching runner; cross-OS packaging is not supported.
- Upload all archives + `.sha256` files to the GitHub Release for the tag,
  preserving the existing "rerun validation + generated notes" behavior.
- Keep the existing `validate-agent-configs.yml` and the release validation gate
  intact; the release must still fail if validation fails.
- Honor the SemVer/prerelease policy in `RELEASING.md` (prerelease tags publish
  GitHub prereleases).
- Pin the JDK/toolchain version used for builds to match subtasks 1 and 2.
- Document a staging path (a prerelease tag or a manually-dispatched run) so
  subtask 4 can be developed and tested against real published assets before the
  first public stable tag.

Out of scope:

- Changing the tag-driven release contract or the version policy in
  `RELEASING.md` (only extend what the workflow produces).
- Installer consumption logic (subtask 4).
- Code signing wiring beyond what subtask 2 decided (if subtask 2 chose signing,
  surface the required secrets here; if unsigned, do not add signing).
- Package-manager publishing (follow-up).

## Acceptance Criteria

1. Pushing a SemVer tag builds, on matching host runners, the runtime images for
   macOS arm64, macOS x64, Windows x64, Linux x64 and the desktop installers for
   macOS, Windows, Linux.
2. All archives and their `.sha256` files are attached to the GitHub Release for
   that tag.
3. Prerelease tags (`-rc.N`) publish GitHub prereleases; stable tags publish
   normal releases — matching `RELEASING.md`.
4. The release still reruns the validation gate and fails closed if validation
   fails.
5. Artifact names/layout match the canonical `<os>-<arch>` token set from
   subtasks 1-2, so subtask 4 can resolve the correct asset by host.
6. A documented staging mechanism (prerelease tag or `workflow_dispatch`)
   produces a real downloadable asset set for subtask 4 testing.
7. The build toolchain (JDK version) is pinned and consistent across runners.

## Validation

```bash
# Lint the workflow locally (actionlint via npx or the project's chosen tool):
npx --yes actionlint .github/workflows/release.yml

# Maintainer gate (unchanged):
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs

# End-to-end: push a prerelease tag to a fork/staging and confirm assets appear.
```

## Implementation Notes

- The matrix is the crux: jpackage/jlink cannot cross-compile, so each OS
  artifact must be produced on its own runner. Use a `strategy.matrix` over
  `{os, arch, gradle-tasks}` and a final job that gathers and uploads.
- macOS x64 runners are being phased toward Apple Silicon on hosted CI; if a
  hosted x64 macOS runner is unavailable, `log()`/document the gap and ship
  arm64-only macOS for v1 rather than silently dropping a target.
- Reuse `actions/upload-artifact` between matrix jobs and a release-publish job,
  or upload directly to the release with `softprops/action-gh-release` (or the
  repo's existing release action). Keep checksum files adjacent to each asset.
- Cache Gradle (`actions/setup-java` + Gradle cache) to keep tag builds
  reasonable; first cold matrix build will be slow, that is expected for a
  release-time workflow.
- Do not weaken the existing validation gate to make the matrix pass; if a
  packaging step is flaky, fix it in subtask 1/2, not by skipping validation.
