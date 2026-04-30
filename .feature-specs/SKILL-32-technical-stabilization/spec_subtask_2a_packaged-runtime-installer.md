---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_2_packaging-and-strict-schemas.md](spec_subtask_2_packaging-and-strict-schemas.md)
---

# Subtask 2a: Packaged Kotlin Runtime Installer

## Goal

Make packaged Kotlin runtime distributions the default installed runtime path
for both `skill-bill` and `skill-bill-mcp`.

## Scope

This subtask owns the packaging half of SKILL-32 Subtask 2:

- Ensure `runtime-cli` and `runtime-mcp` produce JVM application distributions
  through their existing `application` plugin setup.
- Update the Python launcher layer so installed commands resolve to packaged
  distribution bin scripts instead of invoking Gradle.
- Update `install.sh` so installation builds and locates those distributions.
- Leave existing Python shim paths untouched for this phase, but do not expand
  Python rollback behavior; Python deletion is separate follow-up work.
- Add installer and launcher coverage for packaged-path resolution, missing
  distributions, and Kotlin development override environment variables.

## Acceptance Criteria

1. `runtime-cli` and `runtime-mcp` produce JVM application distributions via
   the existing `application` plugin.
2. `install.sh` builds and locates those distributions.
3. Fresh installs resolve `skill-bill doctor --format json` and
   `skill-bill-mcp` to packaged bin scripts without invoking Gradle.
4. Tests cover packaged CLI path resolution, packaged MCP path resolution,
   missing-distribution error messaging, and Kotlin development override
   environment variables.
5. `install.sh` comments and launcher header comments name the packaged path
   and the development fallback.

## Non-Goals

- Strict MCP schema work.
- Removing Python CLI/MCP entry paths.
- Expanding Python rollback behavior or coverage.
- Public getting-started documentation updates.
- Native-image or single-file distributions.

## Dependencies

Subtask 1 must already have landed so the runtime gate is green and Python
ownership is inventoried.

## Implementation Notes

- `skill_bill/launcher.py` currently defaults to Kotlin by running Gradle
  tasks. Replace the default runtime command with packaged bin-script
  resolution.
- Keep development override variables such as `SKILL_BILL_KOTLIN_CLI` and
  `SKILL_BILL_KOTLIN_MCP` usable for local development if the current launcher
  supports them.
- Do not add new Python fallback coverage. Existing shim paths may remain only
  as temporary entrypoint compatibility until Python deletion work removes them.
- `install.sh` currently installs the Python package and registers MCP configs.
  Add the Gradle install distribution step for `runtime-cli` and `runtime-mcp`.
- The runtime projects already apply the `application` plugin with
  `skillbill.cli.MainKt` and `skillbill.mcp.MainKt`.

## Validation Strategy

Run:

```bash
.venv/bin/python3 -m unittest discover -s tests
(cd runtime-kotlin && ./gradlew check)
```

Also rehearse a fresh install enough to verify `skill-bill doctor --format
json` and `skill-bill-mcp` resolve without Gradle when rollback env vars are
unset.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_2a_packaged-runtime-installer.md`.
