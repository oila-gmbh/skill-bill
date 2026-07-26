# SKILL-55 Subtask 2 - Desktop App Installers, per-OS

Parent spec: [.feature-specs/SKILL-55-launch-readiness/spec.md](./spec.md)
Issue key: SKILL-55
Subtask order: 2 of 6
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Make the desktop app a **prebuilt download** instead of a from-source Compose
build. The desktop app is the product's most graspable surface and the Product
Hunt hero; a launch visitor will never `git clone` + Gradle-build a Compose app.
Compose `nativeDistributions` + `packageDmg/Msi/Deb/Rpm` are already wired in
`runtime-desktop/build.gradle.kts:163,193-206`; this subtask turns those into
reproducible, named, per-OS installable artifacts ready for release.

## Scope

In scope:

- Produce installable desktop artifacts per OS from the existing
  `nativeDistributions` config:
  - macOS: `.dmg` (and/or app bundle) — `packageDmg`
  - Windows: `.msi` (and/or `.exe`) — `packageMsi`
  - Linux: `.deb` and `.rpm` — `packageDeb` / `packageRpm` (add an AppImage or a
    portable tarball only if low-cost; otherwise `log()` the gap)
- Ensure each artifact bundles its own JRE (jpackage does this) so the app runs
  with no system JDK.
- Name artifacts by project version (`SkillBill-<version>-<os>-<arch>.<ext>`) and
  emit a SHA-256 checksum beside each.
- Confirm the packaged app bundles the runtime resources it needs
  (`runtime-cli`, `runtime-mcp`, `skills/`, `platform-packs/`, `orchestration/`)
  consistent with the existing `prepareDesktopAppDistributable` staging
  (`README.md` "Desktop App"), so a downloaded app is self-sufficient.
- Make an explicit, recorded **signing decision** (see Implementation Notes):
  either (a) sign + notarize if certificates are available, or (b) ship unsigned
  for v1 and document the OS-specific "how to open" steps. Record the choice in
  `runtime-kotlin/agent/decisions.md`.
- Smoke-launch the packaged app on at least one OS and confirm the window opens
  and the repo/first-run flow renders.

Out of scope:

- CI matrix / release attachment (subtask 3).
- Installer download/placement of the app (subtask 4).
- Self-contained CLI/MCP images (subtask 1).
- Per-app auto-update (follow-up).

## Acceptance Criteria

1. A documented Gradle invocation produces an installable desktop artifact for
   macOS, Windows, and Linux, each bundling its own JRE (runs with no system
   JDK).
2. Each artifact is version-named and has an adjacent `.sha256` file.
3. The packaged app contains the staged runtime resources it needs and launches
   to the first-run / repo view on at least one verified OS (document the matrix
   exercised).
4. The signing decision is recorded in `agent/decisions.md`; if unsigned, the
   exact macOS Gatekeeper and Windows SmartScreen "open anyway" steps are written
   down for reuse by subtask 4 (post-install hint) and subtask 6 (launch FAQ).
5. The existing `prepareDesktopAppDistributable` / `--with-desktop-app`
   from-source path still works for maintainers.
6. Artifact naming uses the same canonical `<os>-<arch>` token set defined in
   subtask 1, so subtask 3/4 resolve runtime and desktop artifacts uniformly.

## Validation

```bash
# Package for the current host (cross-OS packaging is not supported; CI does the rest):
(cd runtime-kotlin && ./gradlew :runtime-desktop:packageDistributionForCurrentOS)
# or the specific task: packageDmg | packageMsi | packageDeb | packageRpm

# From-source desktop path still intact:
(cd runtime-kotlin && ./gradlew :runtime-desktop:prepareDesktopAppDistributable)

# Maintainer gate:
(cd runtime-kotlin && ./gradlew check)
```

## Implementation Notes

- **Signing is the real risk for "download and open."** On macOS, an unsigned /
  un-notarized `.dmg` triggers Gatekeeper ("can't be opened because Apple cannot
  check it"); on Windows, an unsigned `.msi`/`.exe` triggers SmartScreen. For a
  launch, an app that "won't open" is worse than no app. Two honest options:
  (a) if an Apple Developer ID + Windows code-signing cert are available, wire
  signing/notarization into the package tasks; (b) ship unsigned for v1 and make
  the launch post + README state the one-time "right-click → Open" (macOS) /
  "More info → Run anyway" (Windows) step up front. Pick one and record it; do
  not let it surface as a surprise at launch.
- jpackage installers must be built on their target OS — Linux can't build a
  `.dmg`. This subtask is reproducible only for the current host locally; the
  full set is produced by subtask 3's per-OS runners.
- Reuse the JDK/toolchain pin from subtask 1 so the bundled JRE matches the
  CLI/MCP images.
- `runtime-desktop/build.gradle.kts` already gates the package tasks
  (lines 193-206); confirm those gates don't block headless CI packaging (jpackage
  itself does not need a display to *build*, only to *run*).
