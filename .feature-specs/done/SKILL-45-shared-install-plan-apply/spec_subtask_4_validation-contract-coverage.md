# SKILL-45 Shared Install Plan/Apply - Subtask 4: Validation And Contract Coverage

Status: Complete

Parent overview: `.feature-specs/SKILL-45-shared-install-plan-apply/spec.md`

Original parent subtask: `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_1_shared-install-plan-apply.md`

## Scope

Add final regression coverage and contract validation for the shared install plan/apply layer after subtasks 1-3 are implemented. This subtask should be primarily tests and small correctness fixes discovered by those tests.

Extend existing test surfaces such as `InstallStagingTest`, `CliRuntimeTest` install cases, `CliInstallRuntimeTest`, `RuntimeSurfaceContractTest`, and focused runtime-core install plan/apply tests as appropriate to the final implementation shape.

## Acceptance Criteria

1. Tests cover install plan generation inputs and outputs.
2. Tests cover apply behavior and structured success, warning, and failure outcomes.
3. Tests verify base skills are always included and platform packs are discovered dynamically.
4. Tests verify selected and detected agent flows for `copilot`, `claude`, `codex`, `opencode`, and `junie`.
5. Tests verify telemetry choices `anonymous`, `full`, and `off`.
6. Tests verify staging paths remain under `~/.skill-bill/installed-skills` and generated governed artifacts are not written into source directories.
7. Tests verify MCP registration intent and outcome data are represented in the contract.
8. Tests verify Windows symlink/elevation warning/failure branches and guidance.
9. The full repository quality gate passes through `bill-quality-check`.

## Non-Goals

- Do not add new product behavior beyond small fixes required to make the implemented contract correct.
- Do not migrate `install.sh` or build desktop UI.
- Do not change platform-pack manifest contracts or supported-agent contracts.

## Dependencies

- Depends on subtasks 1, 2, and 3 because this validates the completed shared plan/apply layer.

## Validation Strategy

Run `bill-quality-check`. If routed to Kotlin, this should include `(cd runtime-kotlin && ./gradlew check)` or the equivalent command selected by the quality skill.

## Recommended Next Prompt

Run bill-feature-implement on `.feature-specs/SKILL-45-shared-install-plan-apply/spec_subtask_4_validation-contract-coverage.md`.
