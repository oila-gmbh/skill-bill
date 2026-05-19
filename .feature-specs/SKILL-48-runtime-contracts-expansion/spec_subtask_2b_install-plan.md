# SKILL-48 Subtask 2b — `install-plan-schema.yaml`

Status: Complete

Parent spec: [spec_subtask_2_runtime-contracts.md](./spec_subtask_2_runtime-contracts.md)
Grandparent: [spec.md](./spec.md)

## Why this is subtask 2b

Subtask 2a established the pattern for a second contract beyond `platform-pack-schema.yaml` AND landed the desktop auto-listing + AGENTS.md D2 B-half rule. Subtask 2b reuses that pattern to ship the install-plan schema. No further desktop or AGENTS.md edits are needed — the new YAML simply appears in the desktop "Contracts" tree once dropped under `orchestration/contracts/`.

## Scope

1. Author `orchestration/contracts/install-plan-schema.yaml` as a Draft 2020-12 JSON Schema in YAML, mirroring the structural template established by `platform-pack-schema.yaml` and `workflow-state-schema.yaml` (header comments, `$schema`, `$id`, `title`, `description`, `additionalProperties: false`, `contract_version`, `x-coherence-checks`).
2. Schema covers the install-plan shape defined in `runtime-kotlin/runtime-domain/install/model/InstallModels.kt` (346 lines): every typed install-plan step, its required fields, enum values for action kinds, file path conventions, idempotency flags — whatever the runtime emits today.
3. Pin `contract_version` to a Kotlin `INSTALL_PLAN_CONTRACT_VERSION` constant (in `runtime-kotlin/runtime-domain/install/model` or `runtime-kotlin/runtime-core/install`) via a parity test.
4. Wire `InstallPlanSchemaValidator` (sibling of `PlatformPackSchemaValidator`). Gradle `Copy` task uses `doFirst {}` or `inputs.file` declaration (configuration-cache friendly, F-101 follow-up).
5. Validate at parse seams:
   - `CliRuntime` install-plan deserialization in the CLI (`skill-bill install plan` / `skill-bill install apply` flow).
   - `InstallPlanBuilder` construction path in `runtime-kotlin/runtime-core/install` (validate the built plan before emitting JSON or applying steps).
   - Desktop first-run flow that consumes install-plan JSON.
6. Loud-fail via new typed `InvalidInstallPlanSchemaError` carrying the field path (mirrors `InvalidManifestSchemaError`).
7. Tests:
   - `InstallPlanSchemaContractVersionTest` — parity with `INSTALL_PLAN_CONTRACT_VERSION`.
   - `InstallPlanSchemaValidatesExistingFixturesTest` — every fixture in the existing `InstallPlanContractCoverageTest` validates clean against the new schema (proves no breaking change).
   - `InstallPlanSchemaViolationsTest` — per-violation tests: unknown step kind, missing required field, wrong `contract_version`, additional unknown property, invalid path shape.
   - Classpath shadow guard test extended to assert this YAML is not shadowed.
8. No desktop or AGENTS.md edits required — both already handled by subtask 2a's auto-listing and D2 B-half rule. Verify the new YAML appears automatically in the desktop "Contracts" tree (a quick local check, not a new test).

## Acceptance criteria

1. `orchestration/contracts/install-plan-schema.yaml` exists, declares Draft 2020-12, has `contract_version` const, `additionalProperties: false`, documented `x-coherence-checks` block, and structurally matches the established template.
2. `INSTALL_PLAN_CONTRACT_VERSION` Kotlin constant exists; parity test fails the build if the YAML's `contract_version` and the constant diverge.
3. Every existing `InstallPlanContractCoverageTest` fixture validates clean against the new schema.
4. `CliRuntime` and `InstallPlanBuilder` parse seams validate against the schema and loud-fail via `InvalidInstallPlanSchemaError` carrying the field path.
5. Per-violation tests cover unknown step kind, missing required field, wrong contract_version, unknown additional property, invalid path shape.
6. Build gradle `Copy` task is configuration-cache friendly.
7. The new YAML appears in the desktop "Contracts" tree automatically via the auto-listing landed in 2a (no code edits to desktop).
8. `bill-quality-check` (runtime-kotlin Gradle `check`) passes.

## Non-goals

- Authoring `workflow-state-schema.yaml`, `native-agent-composition-schema.yaml`, or `telemetry-event-schema.yaml` (subtasks 2a/2c/2d).
- Touching desktop code or `AGENTS.md` (already landed in 2a).
- Replacing `PlatformManifest`, generating Kotlin types from schemas, multi-version validators, `x-runtime-anchored` (Subtask 3).
- Renaming/restructuring existing install-plan steps — schema describes what is emitted today.

## Dependencies

- Subtask 1 (SKILL-47 cleanup): C2 typed `validate()`, C7 classpath shadow guard, C8 `repoRootFromTest()`.
- Subtask 2a: desktop auto-listing + D2 B-half rule already in place; `WorkflowStateSchemaValidator` provides a near-identical pattern to copy from.

## Validation strategy

`bill-quality-check` (runtime-kotlin Gradle `check`).

## Boundaries touched

- `orchestration/contracts/install-plan-schema.yaml` (new).
- `runtime-kotlin/runtime-core/install` — validator + `InstallPlanBuilder` parse-seam wiring.
- `runtime-kotlin/runtime-domain/install/model/InstallModels.kt` — Kotlin contract-version constant; possibly typed validator entry point.
- `runtime-kotlin/runtime-contracts/error` — new `InvalidInstallPlanSchemaError`.
- CLI install flow — validate at JSON deserialization seam.
- Desktop first-run install consumer — validate at JSON deserialization seam.
- Gradle build file (configuration-cache friendly `Copy`).
- `runtime-kotlin/agent/history.md` — high-signal entry.

## Templates to cite verbatim

- Schema YAML structure: `orchestration/contracts/platform-pack-schema.yaml` + `orchestration/contracts/workflow-state-schema.yaml` (landed in 2a).
- Validator class: `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/PlatformPackSchemaValidator.kt`.
- Build copy step: configuration-cache-friendly version landed in 2a's gradle.
- Parity test: `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaContractVersionTest.kt`.
- Validates-existing test: `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaValidatesExistingPacksTest.kt`.
- Violations test: `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaViolationsTest.kt`.
- Existing install-plan fixtures: `InstallPlanContractCoverageTest`.
- Test helper: `runtime-kotlin/runtime-core/src/testFixtures/kotlin/skillbill/testing/RepoRoot.kt`.

## Recommended next prompt

`Run bill-feature-implement on .feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2b_install-plan.md`
