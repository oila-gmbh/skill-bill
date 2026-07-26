# SKILL-45 Subtask 2: CLI and install.sh Migration

Parent spec: [spec.md](./spec.md)

Status: Complete

## Scope

Migrate the command-line and shell installer paths to the shared install plan/apply backend from Subtask 1. Keep `install.sh` usable, but make it a thin wrapper around runtime-owned install behavior wherever practical. Preserve existing user-facing install capabilities while replacing duplicated shell-only install logic with typed runtime calls.

Likely files/boundaries:

- `install.sh`
- `runtime-kotlin/runtime-cli/src/main/kotlin/**`
- `runtime-kotlin/runtime-cli/src/test/kotlin/**`
- `runtime-kotlin/runtime-core/src/main/kotlin/**/install/**` only for small adapter refinements discovered during migration
- CLI launcher/build scripts that package or invoke `runtime-cli`

## Acceptance Criteria

1. `runtime-cli` exposes install plan/apply commands or equivalent stable entrypoints backed by the shared runtime install contract.
2. `install.sh` remains usable for existing install flows and delegates install planning/application to the reusable runtime path instead of owning the full install algorithm.
3. Agent detection and manual agent selection work for `copilot`, `claude`, `codex`, `opencode`, and `junie`.
4. Platform packs are discovered dynamically from `platform-packs`; base skills are always included.
5. Telemetry choices remain `anonymous`, `full`, and `off`, with behavior matching the existing CLI contract.
6. The install path continues to use `~/.skill-bill/installed-skills` staging and preserves governed generated-output boundaries.
7. MCP registration behavior is reachable from CLI/install paths through the typed plan/apply contract.
8. Windows symlink/elevation behavior produces clear CLI/install messages based on structured runtime outcomes.
9. Tests cover CLI install option mapping, install plan/apply invocation, agent/platform selection, telemetry configuration, MCP registration intent, and Windows fallback/failure messaging.

## Non-Goals

- Do not build desktop UI or package desktop installers in this subtask.
- Do not duplicate install behavior in shell after it exists in the shared runtime backend.
- Do not change skill source shape, platform-pack manifest contracts, or governed staging rules.
- Do not commit generated governed artifacts or provider-specific native-agent output.

## Dependencies

Depends on Subtask 1 because the CLI and shell wrapper must call the shared install plan/apply contract. If Subtask 1 exposes missing adapter fields, refine the shared contract minimally as part of this subtask and keep tests at the runtime boundary.

## Validation Strategy

Run the CLI/runtime tests for install command behavior, then run:

```bash
(cd runtime-kotlin && ./gradlew check)
```

For shell wrapper changes, manually exercise a dry-run or non-destructive install planning path if available. If broader behavior is touched, run `bill-quality-check`.

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_2_cli-install-sh-migration.md`.
