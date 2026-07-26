# SKILL-51 — Auto reinstall after publish

Status: Complete
Issue key: SKILL-51

## Context

Desktop users can edit or delete governed `content.md` sources and pack-owned add-ons, then publish those changes. Published source changes do not affect the locally installed agent skills until Skill Bill is reinstalled. The current desktop path requires the user to reopen setup and make the same installation selections again.

## Acceptance Criteria

1. After a successful desktop publish that pushes changes, the app asks whether to reinstall Skill Bill so changed governed skills/add-ons take effect.
2. Confirming the prompt reuses the latest completed install selections instead of reopening the full setup wizard.
3. Latest install preferences are persisted whenever the first-run/setup install flow succeeds, including agents, platform packs, telemetry, MCP registration behavior, and desktop defaults already owned by the installer.
4. The reinstall path runs through the existing typed install gateway plan/apply contract and reports success, warning, or failure in UI state.
5. Declining or dismissing the prompt leaves the publish result intact and does not mutate install state.
6. Tests cover preference reuse, reinstall gateway behavior, ViewModel prompt/state transitions, and publish-to-reinstall success/failure paths.

## Non-goals

- Change CLI `install.sh` behavior outside the desktop runtime.
- Reinstall automatically without explicit user confirmation.
- Rework PR publishing provider behavior.

## Validation Strategy

- Add focused ViewModel tests for post-publish reinstall prompt, decline, success, and failure.
- Add datastore/gateway tests where needed for persisted install preference replay.
- Run the relevant desktop JVM tests, then a broader Gradle check if feasible.
