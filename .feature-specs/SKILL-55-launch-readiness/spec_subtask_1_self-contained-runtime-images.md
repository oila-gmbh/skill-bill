# SKILL-55 Subtask 1 - Self-Contained Runtime Images (CLI + MCP), per-OS, no JDK

Parent spec: [.feature-specs/SKILL-55-launch-readiness/spec.md](./spec.md)
Issue key: SKILL-55
Subtask order: 1 of 6
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Produce self-contained, per-OS distributions of `runtime-cli` and `runtime-mcp`
that run on a machine **without a system JDK**. Today both ship as Gradle
`installDist` outputs — launcher scripts that require a JVM on `PATH`. This
subtask is the structural foundation of the feature: every later stage (release
pipeline, prebuilt-first installer, README quickstart) depends on a downloadable
binary that "just runs."

## Scope

In scope:

- Bundle a Java runtime with the CLI and MCP apps so no external JDK is needed.
  Preferred approach: `jlink`/`jpackage` (or the Gradle Badass Runtime/jlink
  plugin) producing a minimal app-image per OS/arch. GraalVM `native-image` is
  an acceptable alternative if startup/size wins justify the build complexity;
  if chosen, record the rationale in `agent/decisions.md`.
- Targets: `macos-arm64`, `macos-x64`, `windows-x64`, `linux-x64`. (arm64 Linux
  optional; if skipped, `log()` it as a known gap, do not silently omit.)
- Add Gradle tasks that produce a named, versioned, archived artifact per target
  for both `runtime-cli` and `runtime-mcp` (e.g.
  `skill-bill-runtime-<version>-<os>-<arch>.tar.gz` / `.zip`), with a stable
  internal layout (`bin/skill-bill`, `bin/skill-bill-mcp` or equivalent) that the
  installer can consume.
- Emit a SHA-256 checksum file beside each archive.
- Keep the existing `installDist` outputs intact — the from-source path
  (subtask 4 `--from-source`) and the desktop bundling that depends on
  `:runtime-cli:installDist` / `:runtime-mcp:installDist`
  (`runtime-desktop/build.gradle.kts:95`) must still work unchanged.
- Verify the produced launcher starts and reports version on each OS:
  `skill-bill version` and a minimal `skill-bill-mcp` smoke (process starts,
  speaks stdio, exits cleanly).

Out of scope:

- Wiring these artifacts into the installer (subtask 4).
- Building them in CI / attaching to releases (subtask 3).
- Desktop app packaging (subtask 2).
- Code signing / notarization (subtask 2 decision; not required here).
- Package-manager distribution (follow-up).

## Acceptance Criteria

1. A documented Gradle invocation produces, for `runtime-cli` and `runtime-mcp`,
   a self-contained archive per target (`macos-arm64`, `macos-x64`,
   `windows-x64`, `linux-x64`) that runs with **no system JDK present**.
2. Each archive has an adjacent `.sha256` checksum file.
3. The unpacked layout is stable and documented (the exact launcher paths
   subtask 4 will symlink to `skill-bill` / `skill-bill-mcp`).
4. On a host with no JDK on `PATH` and no `JAVA_HOME`, an unpacked artifact runs
   `skill-bill version` successfully (verified on at least one OS; document the
   matrix actually exercised).
5. The existing `:runtime-cli:installDist` and `:runtime-mcp:installDist` outputs
   and their consumers (desktop bundling) are unchanged and still build.
6. Artifact name and layout are derived from the project version (no hardcoded
   version string), so subtask 3 can publish them by tag.
7. If GraalVM native-image is chosen over jlink/jpackage, the trade-off is
   recorded in `runtime-kotlin/agent/decisions.md`.

## Validation

```bash
# Produce the per-OS self-contained images (task name per implementation):
(cd runtime-kotlin && ./gradlew :runtime-cli:<packageSelfContained> :runtime-mcp:<packageSelfContained>)

# Existing from-source path still intact:
(cd runtime-kotlin && ./gradlew :runtime-cli:installDist :runtime-mcp:installDist)

# Clean-runtime smoke (no JDK on PATH): unpack the archive, then:
./bin/skill-bill version

# Maintainer gate:
(cd runtime-kotlin && ./gradlew check)
```

## Implementation Notes

- Compose Desktop's `nativeDistributions` already uses `jpackage` under the
  hood for the desktop app, so the JDK toolchain for runtime images is already
  present in the build — reuse the same JDK/toolchain pin to keep image and app
  on the same Java version.
- jlink/jpackage app-image (not installer) is the lightest fit for the CLI/MCP:
  it yields a directory you can tar/zip and symlink into, matching the existing
  `install_packaged_runtime_distributions` copy-and-symlink model in
  `install.sh:234`.
- Cross-compiling jpackage images is not supported — each OS image must be built
  on its own OS. That is fine here: subtask 3 builds each on a matching CI
  runner. Locally, only the current host's image is reproducible; do not pretend
  otherwise.
- Keep archive naming aligned with what subtask 4's OS/arch detection will look
  up. Define the canonical `<os>-<arch>` token set once (e.g. in a small shared
  script or Gradle ext) and reference it from both this subtask and subtask 3/4.
- Watch artifact size: a jlink image with a trimmed module set is far smaller
  than bundling a full JDK. Trim modules to what the CLI/MCP actually need.
