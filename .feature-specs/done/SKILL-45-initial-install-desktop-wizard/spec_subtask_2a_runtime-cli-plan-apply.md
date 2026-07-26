# SKILL-45 Subtask 2a: Runtime CLI Install Plan/Apply Entrypoints

Parent spec: [spec.md](./spec.md)

Source subtask being decomposed: [spec_subtask_2_cli-install-sh-migration.md](./spec_subtask_2_cli-install-sh-migration.md)

Status: Complete

## Scope

Expose stable `runtime-cli` install plan/apply commands, or equivalent stable entrypoints, backed by the shared runtime install contract. This subtask owns CLI option parsing, mapping user options into `InstallPlanRequest`, invoking `InstallOperations.planInstall` / `InstallOperations.applyInstall`, and rendering structured outcomes into clear CLI messages.

Likely files/boundaries:

- `runtime-kotlin/runtime-cli/src/main/kotlin/**`
- `runtime-kotlin/runtime-cli/src/test/kotlin/**`
- `runtime-kotlin/runtime-core/src/main/kotlin/**/install/**` only for small adapter refinements needed by CLI mapping
- `runtime-kotlin/runtime-domain/src/main/kotlin/**/install/**` only if existing typed fields are insufficient

## Acceptance Criteria

1. `runtime-cli` exposes install plan/apply commands or equivalent stable entrypoints backed by the shared runtime install contract.
2. Agent detection and manual agent selection work for `copilot`, `claude`, `codex`, `opencode`, and `junie` through CLI inputs.
3. Platform pack selection maps through dynamic runtime discovery from `platform-packs`, while base skills remain included by the plan.
4. Telemetry options map to the existing `anonymous`, `full`, and `off` runtime contract.
5. MCP registration choices are accepted through the typed plan/apply request surface.
6. CLI messages for Windows symlink preflight/apply outcomes are rendered from structured runtime outcomes.

## Non-Goals

- Do not migrate `install.sh` in this subtask beyond any tiny compatibility shim required to compile.
- Do not duplicate install planning or application logic in CLI command handlers.
- Do not change skill source shape, platform-pack manifest contracts, or governed staging rules.
- Do not commit generated governed artifacts or provider-specific native-agent output.

## Dependencies

Depends on SKILL-45 Subtask 1 shared install plan/apply contract already exposing `InstallPlanRequest`, `InstallPlan`, and `InstallApplyResult`. If a missing field blocks CLI mapping, add the smallest typed contract refinement and keep it covered at the runtime boundary.

## Validation Strategy

Run focused runtime-cli tests for command parsing and invocation behavior, plus any impacted install contract tests:

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test :runtime-core:test)
```

If shared contract files are touched, also run `bill-quality-check`.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_2a_runtime-cli-plan-apply.md`.
