# SKILL-45 Subtask 2c: Install Migration Coverage and Validation

Status: Complete

Parent spec: [spec.md](./spec.md)

Source subtask being decomposed: [spec_subtask_2_cli-install-sh-migration.md](./spec_subtask_2_cli-install-sh-migration.md)

## Scope

Complete the test coverage and validation pass for the CLI and `install.sh` migration. This subtask owns regression tests for CLI option mapping, runtime plan/apply invocation, agent/platform selection, telemetry configuration, MCP registration intent, governed staging boundaries, and Windows symlink/elevation messaging.

Likely files/boundaries:

- `runtime-kotlin/runtime-cli/src/test/kotlin/**`
- `runtime-kotlin/runtime-core/src/test/kotlin/**/install/**`
- `runtime-kotlin/runtime-domain/src/test/kotlin/**/install/**`
- Shell wrapper test harnesses or scripts, if present
- Existing focused tests such as `CliRuntimeTest`, `CliInstallRuntimeTest`, `InstallPlanBuilderTest`, `InstallPlanContractCoverageTest`, `InstallApplyTest`, `InstallNativeAgentLinkApplyTest`, `RuntimeSurfaceContractTest`, and `RuntimeArchitectureTest`

## Acceptance Criteria

1. Tests cover CLI install option mapping into the shared `InstallPlanRequest` contract.
2. Tests cover install plan/apply invocation from runtime CLI entrypoints.
3. Tests cover agent detection/manual selection behavior for `copilot`, `claude`, `codex`, `opencode`, and `junie`.
4. Tests cover platform selection using dynamic `platform-packs` discovery with base skills included.
5. Tests cover telemetry configuration for `anonymous`, `full`, and `off`.
6. Tests cover MCP registration intent flowing through the typed plan/apply contract.
7. Tests cover Windows fallback/failure messaging based on structured runtime outcomes.
8. Validation confirms install staging remains under `~/.skill-bill/installed-skills` and generated-output boundaries are preserved.

## Non-Goals

- Do not add new product behavior beyond fixes required to make the migration meet its contract.
- Do not change skill source shape, platform-pack manifest contracts, or governed staging rules.
- Do not commit generated governed artifacts or provider-specific native-agent output.
- Do not build desktop UI or package desktop installers.

## Dependencies

Depends on Subtask 2a and Subtask 2b because this is the final coverage and validation pass for both runtime CLI entrypoints and shell delegation.

## Validation Strategy

Run focused runtime tests first, then the repository quality route:

```bash
(cd runtime-kotlin && ./gradlew check)
bill-quality-check
```

If `install.sh` changes affect install staging locally, avoid committing generated governed artifacts and inspect the worktree before handoff.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_2c_install-migration-validation.md`.
