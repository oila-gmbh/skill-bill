# Skill Bill IntelliJ plugin

Isolated IntelliJ Platform plugin that surfaces Skill Bill feature-work status in
the IDE status bar. This directory is a **sibling** of `runtime-kotlin/` with its
own Gradle build — it is not included in the runtime build and adds no IntelliJ
dependency there.

See [ARCHITECTURE.md](ARCHITECTURE.md) for package ownership, persistence policy,
source-of-truth rules, status-bar expected states, and the deferred tool-window path.

## Requirements

- JDK 21
- IntelliJ IDEA 2025.2–2026.1 (Community or Ultimate) for `runIde`
- A `skill-bill` CLI on `PATH`, or a preference override to the executable

## Installing the plugin

Primary path — install the released zip:

1. Open the GitHub Release for the `plugin-vX.Y.Z` tag you want.
2. Download `skill-bill-intellij-plugin-<version>.zip` (its `.sha256` sidecar is
   published alongside it if you want to verify the download).
3. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip.
4. Restart the IDE when prompted.

Fallback — build from source:

```bash
cd intellij-plugin
./gradlew buildPlugin    # archive lands in build/distributions/
```

Then install that archive with the same *Install Plugin from Disk…* step.

## Setup: Skill Bill CLI path

1. Optional preference override (`SkillBillApplicationSettings.cliExecutableOverride`)
2. Else `PATH` lookup of `skill-bill`

Missing or unusable executables become typed **unavailable** / misconfigured
outcomes — never stack traces in the UI. Contract-version mismatches become
**incompatible**.

Do **not** run `install.sh`, `uninstall.sh`, or `skill-bill install apply` from this
plugin module; the plugin only invokes the read-only `skill-bill work status
--format json` contract.

## Common tasks

```bash
cd intellij-plugin
./gradlew check          # unit + presentation + architecture + platform fixture tests
./gradlew buildPlugin    # package the plugin archive
./gradlew runIde         # launch a sandbox IDE with the plugin
./gradlew verifyPlugin   # Plugin Verifier against 2025.2 and 2026.1 baselines
```

Configuration cache is enabled via `gradle.properties`
(`org.gradle.configuration-cache=true`).

## Status-bar behavior

- Polling starts when the status-bar widget is active for a project and coalesces
  overlapping refreshes (default interval 15s).
- Elapsed goal/work and subtask clocks advance from a lightweight local UI ticker
  that does **not** launch a CLI poll per tick. A new status snapshot re-anchors
  both clocks; disposal stops the ticker and cancels polling.
- Click performs a read-only coalesced refresh and opens a small details popup
  (issue/workflow, state, step, progress, both elapsed clocks, last update, typed
  problem summary). No start / resume / retry / cancel / abandon actions.

## What this release does not include

- Full Skill Bill tool window (explicitly deferred)
- Marketplace publish or signing
- Workflow mutation
- Remote Development / Split Mode (documented as deferred)

## Compatibility

| Item | Value |
| --- | --- |
| Plugin id | `dev.skillbill.status` |
| Products | IntelliJ IDEA Community, Ultimate |
| Builds | `252`–`261.*` (IDEA 2025.2 through 2026.1) |
| Platform Gradle Plugin | 2.x |
| JVM | 21 |
| Status-bar widget id | `SkillBillStatusBarWidget` |
