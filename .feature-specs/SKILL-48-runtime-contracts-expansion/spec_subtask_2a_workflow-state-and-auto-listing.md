# SKILL-48 Subtask 2a — `workflow-state-schema.yaml` + desktop auto-listing + D2 B-half

Status: Complete

Parent spec: [spec_subtask_2_runtime-contracts.md](./spec_subtask_2_runtime-contracts.md)
Grandparent: [spec.md](./spec.md)

## Why this is subtask 2a (and what it unlocks)

Per the parent spec's recommended landing order, `workflow-state-schema.yaml` is the smallest and most stable shape and establishes the pattern beyond `platform-pack-schema.yaml`. By bundling the desktop auto-listing change here, every subsequent schema (2b/2c/2d) appears in the desktop "Contracts" tree the moment its YAML is dropped under `orchestration/contracts/` — no follow-up desktop edits needed. The `AGENTS.md` D2 B-half rule also lands here so the rule is in effect the instant the second contract (workflow-state) appears, before subtasks 2b/2c/2d ship.

## Scope

1. Author `orchestration/contracts/workflow-state-schema.yaml` as a Draft 2020-12 JSON Schema in YAML, structurally mirroring `orchestration/contracts/platform-pack-schema.yaml` (header comments, `$schema`, `$id`, `title`, `description`, `additionalProperties: false`, `contract_version` const, `x-coherence-checks` block for cross-field rules JSON Schema cannot express).
2. Pin `contract_version` to a new Kotlin constant in `runtime-kotlin/runtime-domain` (or `runtime-contracts/workflow`) consumed by the workflow-state parse seam.
3. Wire a `WorkflowStateSchemaValidator` (sibling pattern to `PlatformPackSchemaValidator`) loaded from classpath; gradle `Copy` task copies the YAML into the validator module's resources; configure-time `require()` check uses `doFirst {}` or an `inputs.file` declaration so it is configuration-cache friendly (F-101 follow-up adopted day one — do NOT replicate the cache-unfriendly pattern from SKILL-47).
4. Validate at the parse seam where `WorkflowStateSnapshot` (runtime-domain/.../workflow/model/WorkflowModels.kt) is rehydrated and where `WorkflowContracts.fullWorkflowPayload`/`summaryWorkflowPayload`/`resumePayload`/`continuePayload` are emitted/consumed. Loud-fail via a new typed `InvalidWorkflowStateSchemaError` (mirrors `InvalidManifestSchemaError`).
5. Per-skill workflows differ on `workflow_status` enum (`FeatureImplement*Definition` has 12 step IDs; `FeatureVerify*Definition` has 8). Schema must NOT pin per-skill step enums — use `oneOf` keyed by `skill`/`workflow_kind`, or a skill-keyed `$defs` table. Document this decision under `x-coherence-checks` in the YAML header so future contributors know why the enum is not flat.
6. Tests (all under `runtime-kotlin/runtime-domain` or wherever the validator lands; reuse `repoRootFromTest()` test helper from Subtask 1):
   - `WorkflowStateSchemaContractVersionTest` — schema's `contract_version` equals the Kotlin constant.
   - `WorkflowStateSchemaValidatesExistingWorkflowsTest` — every shipped workflow definition's emitted snapshots (feature-implement + feature-verify; each step) validate clean.
   - `WorkflowStateSchemaViolationsTest` — per-violation tests for the highest-signal rules: unknown `workflow_status`, missing required field, additional unknown property, wrong `contract_version`, per-skill enum mismatch (FeatureImplement status declared with FeatureVerify `workflow_kind`).
   - Classpath shadow guard test (from SKILL-47 Subtask 1) extended to assert this new YAML is not shadowed.
7. Desktop auto-listing: switch `RuntimeRepoBrowserService.loadContracts` (runtime-kotlin/runtime-desktop/core/data, lines ~421-457) from a hard-coded single leaf to `Files.list(orchestrationContractsDir)` filtered by `*.yaml`, sorted deterministically. `TreeItemKind.CONTRACT` / `YamlSyntaxHighlighter` / read-only viewer flow remain unchanged (already contract-agnostic per SKILL-47).
8. Integration test: dropping a new `*.yaml` into `orchestration/contracts/` makes a new leaf appear in the desktop "Contracts" tree without code changes (use a temp dir or fixture YAML).
9. `AGENTS.md` D2 B-half rule: add a paragraph under the relevant runtime-contracts section stating: "Every schema under `orchestration/contracts/` is part of the runtime contract. Adding a new one requires a parity test that pins its `contract_version` to a Kotlin constant, and the desktop 'Contracts' tree will surface it automatically." Cross-link to `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaContractVersionTest.kt` as the canonical pattern.

## Acceptance criteria

1. `orchestration/contracts/workflow-state-schema.yaml` exists, declares `$schema: "https://json-schema.org/draft/2020-12/schema"`, has a `contract_version` const, sets `additionalProperties: false`, documents `x-coherence-checks` for cross-field rules, and handles per-skill `workflow_status` divergence without flat enum pinning.
2. A new Kotlin `WORKFLOW_STATE_CONTRACT_VERSION` constant pins the schema's `contract_version`; the parity test fails the build if they diverge.
3. The workflow-state parse seam validates against the schema and loud-fails via `InvalidWorkflowStateSchemaError` carrying the field path.
4. Every workflow definition shipped today (FeatureImplement + FeatureVerify, every step) produces snapshots that validate clean against the schema.
5. Per-violation tests cover: unknown enum value, missing required field, unknown property, wrong contract_version, per-skill enum mismatch.
6. `RuntimeRepoBrowserService.loadContracts` auto-lists every YAML in `orchestration/contracts/` (no hard-coded list), proven by an integration test where a new YAML appears as a new tree leaf without code edits.
7. `AGENTS.md` documents the rule that every YAML under `orchestration/contracts/` is part of the runtime contract and requires a parity test.
8. Build gradle `Copy` task is configuration-cache friendly (uses `doFirst {}` or `inputs.file`).
9. `bill-quality-check` (runtime-kotlin Gradle `check`) passes.

## Non-goals

- Authoring `install-plan-schema.yaml`, `native-agent-composition-schema.yaml`, or `telemetry-event-schema.yaml` (subtasks 2b/2c/2d).
- Replacing `PlatformManifest`, `x-runtime-anchored` extension (Subtask 3), multi-version validators, generating Kotlin types from schemas.
- Renaming/restructuring existing workflow steps or status values — schema describes what is emitted today.
- Schema editing inside the desktop UI; viewer stays read-only.

## Dependencies

- SKILL-48 Subtask 1 (SKILL-47 cleanup): C2 typed `validate()` signature, C7 classpath shadow guard, C8 shared `repoRootFromTest()`.

## Validation strategy

`bill-quality-check` (runtime-kotlin Gradle `check`). The desktop integration test must run as part of `check` (not a manual step).

## Boundaries touched

- `orchestration/contracts/workflow-state-schema.yaml` (new).
- `runtime-kotlin/runtime-domain/workflow` — validator + parse-seam wiring; new exception.
- `runtime-kotlin/runtime-contracts/workflow/WorkflowContracts.kt` — validate at payload-emit/consume seams.
- `runtime-kotlin/runtime-contracts/error` — new `InvalidWorkflowStateSchemaError`.
- Gradle build file copying the YAML resource (configuration-cache friendly).
- `runtime-kotlin/runtime-desktop/core/data/.../RuntimeRepoBrowserService.kt` — `loadContracts` auto-listing switch.
- `AGENTS.md` — D2 B-half rule paragraph.
- `runtime-kotlin/agent/history.md` — high-signal entry per `bill-boundary-history`.

## Templates to cite verbatim

- Schema YAML structure: `orchestration/contracts/platform-pack-schema.yaml` (header comments, `$schema`/`$id`/`title`/`description`/`contract_version`/`x-coherence-checks`).
- Validator class: `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/PlatformPackSchemaValidator.kt`.
- Build copy step: `runtime-kotlin/runtime-core/build.gradle.kts:44-70` — but switch to `doFirst {}` / `inputs.file` per F-101.
- Parity test: `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaContractVersionTest.kt`.
- Validates-existing test: `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaValidatesExistingPacksTest.kt`.
- Violations test: `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaViolationsTest.kt`.
- Test helper: `runtime-kotlin/runtime-core/src/testFixtures/kotlin/skillbill/testing/RepoRoot.kt`.

## Recommended next prompt

`Run bill-feature-implement on .feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2a_workflow-state-and-auto-listing.md`
