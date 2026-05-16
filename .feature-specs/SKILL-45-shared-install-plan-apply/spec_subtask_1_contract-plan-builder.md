# SKILL-45 Shared Install Plan/Apply - Subtask 1: Contract Plan Builder

Parent overview: `.feature-specs/SKILL-45-shared-install-plan-apply/spec.md`

Original parent subtask: `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_1_shared-install-plan-apply.md`

Status: Complete

## Scope

Create the shared typed install contract and pure plan-building path in runtime Kotlin. This subtask owns DTOs/enums/value types and a plan builder that performs no installation side effects.

The implementation should follow existing runtime Kotlin surface patterns: public DTOs belong in the runtime-domain install model, runtime-core should provide application/use-case logic, and platform discovery should reuse `ShellContentLoader`, `discoverPlatformPackManifests`, and `PlatformManifest` rather than hardcoding platform names.

## Acceptance Criteria

1. A typed install plan API accepts agent selection, platform pack selection, telemetry level, MCP registration choice, runtime distribution inputs, and installation target paths.
2. Base skills are always represented in generated plans.
3. Platform pack choices are discovered dynamically from `platform-packs`.
4. Supported agents are exactly `copilot`, `claude`, `codex`, `opencode`, and `junie`.
5. The plan supports both detection-derived agent targets and manual agent selection.
6. Telemetry values are typed as `anonymous`, `full`, and `off`, matching current CLI behavior.
7. The plan includes MCP registration intent data but does not execute registration.
8. The plan explicitly represents staging root/path intent under `~/.skill-bill/installed-skills`.
9. The plan includes Windows symlink/elevation preflight state or required decision state without attempting fallback behavior.

## Non-Goals

- Do not apply/install anything.
- Do not migrate `install.sh` or CLI commands.
- Do not write generated governed artifacts into source directories.
- Do not change platform-pack manifests or supported-agent contracts.
- Do not implement desktop UI state.

## Dependencies

None. This is the foundation subtask.

## Validation Strategy

Use repo-native focused tests for pure plan generation if practical during implementation, then run `bill-quality-check` or the dominant Kotlin quality command. Full cross-layer regression coverage is deferred to subtask 4.

## Recommended Next Prompt

Run bill-feature-implement on `.feature-specs/SKILL-45-shared-install-plan-apply/spec_subtask_1_contract-plan-builder.md`.
