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
plugin module. Beyond the read-only `skill-bill work status --format json` contract,
the plugin invokes exactly two mutating verbs — `skill-bill goal stop` and
`skill-bill goal pause` — from the goal controls described below.

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
- Click performs a coalesced refresh and opens a details popup (issue/workflow,
  state, step, progress, both elapsed clocks, last update, typed problem summary).

### Goal controls

The details popup offers two controls, and only when the snapshot reports an active
`feature-goal` with an issue key:

- **Stop goal** → `skill-bill goal stop <issue-key> --repo-root <canonical>`. The
  runtime records the operator stop durably and terminates its own runner.
- **Pause after current subtask** → `skill-bill goal pause <issue-key> --repo-root
  <canonical>`. The runtime consumes the request at the next subtask boundary, so
  nothing in flight is interrupted. The control renders disabled while the snapshot
  reports a pause already requested, including one made from the CLI.

Why this stays safe: both are bounded CLI invocations, each on its own `ProcessRunner`
instance so a mutation can never return a status poll's result; the plugin reads no
Skill Bill database and terminates no process itself — termination belongs to the
runtime verb. Failures surface as a bounded summary that never carries process output,
stderr, or filesystem paths, and the next status snapshot remains authoritative.

There is deliberately **no Resume control**: resuming picks an agent, a profile, and a
launch environment the IDE does not own, so it stays a CLI decision.

## What this release does not include

- Full Skill Bill tool window (explicitly deferred)
- Marketplace publish or signing
- Goal launching, resume, retry, or abandon actions
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
