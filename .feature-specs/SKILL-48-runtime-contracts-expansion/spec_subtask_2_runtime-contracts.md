# SKILL-48 Subtask 2 — Other runtime contracts under `orchestration/contracts/`

Status: In Progress

Parent spec: [spec.md](./spec.md)

## Scope

This subtask extends the SKILL-47 contract-as-source-of-truth pattern to the four remaining implicit runtime contracts: telemetry events, durable workflow state, install plans, and native-agent composition. Each gets a canonical JSON Schema (Draft 2020-12 in YAML) under `orchestration/contracts/`, a Kotlin `contract_version` constant pinned by a parity test, and runtime wiring at the parse seam that validates and loud-fails. The desktop "Contracts" tree group switches from a hand-maintained leaf to auto-listing every YAML in `orchestration/contracts/`, so adding a contract is one new file + one parity test, nothing else. Includes the B-half of D2: the `AGENTS.md` rule that every schema under `orchestration/contracts/` is part of the runtime contract and adding a new one requires a parity test.

Recommended landing order inside this subtask (per the parent spec's open question "Subtask B: ordering"): start with `workflow-state-schema.yaml` because the workflow runtime has the most stable shape and smallest contract surface — that PR establishes the template. Then `install-plan-schema.yaml`, `native-agent-composition-schema.yaml`, and finally `telemetry-event-schema.yaml` (largest, ~30 events).

**Note to a future planner:** this subtask is itself sizeable (four schemas + auto-listing + AGENTS.md + per-contract validation and per-violation tests). If a future `bill-feature-implement` run on this spec triggers another decomposition decision, it is correct to split this into four schema-per-subtask runs plus a desktop auto-listing run. Treat each schema's "schema + parity test + runtime wiring + tests" as its own atomic milestone.

Deferred to other subtasks:
- Any `PlatformManifest.customFields` work or `x-runtime-anchored` annotation on the platform pack schema (Subtask 3).
- The SKILL-47 validator-surface cleanup itself (Subtask 1 — depended on, not duplicated here).

## Acceptance criteria

B1. The following implicit runtime contracts each get a canonical JSON Schema (Draft 2020-12 in YAML) under `orchestration/contracts/`, with the same prose-section pattern for any cross-field rules:
- `telemetry-event-schema.yaml` — describes the payload shape for every `mcp__skill-bill__*_started/finished` event currently emitted by the runtime.
- `workflow-state-schema.yaml` — describes the durable workflow-state shape used by `feature_implement_workflow_open/update/get`.
- `install-plan-schema.yaml` — describes the typed install-plan shape produced by `CliRuntime` and consumed by `install.sh` / desktop first-run.
- `native-agent-composition-schema.yaml` — describes the composition shape `NativeAgentComposition` produces.

B2. Each schema declares its own `contract_version` and is pinned to the corresponding Kotlin constant via a parity test (same pattern as SKILL-47's `PlatformPackSchemaContractVersionTest`).

B3. Each runtime consumer of the corresponding payload validates against its schema at the same seam where it currently parses — telemetry tools at their entry points, workflow-state tools, install-plan parsing in CLI, composition assembly in the runtime. Loud-fail via the existing typed exceptions where they exist; introduce minimal new typed exceptions only when none fits.

B4. The desktop "Contracts" tree group automatically lists every schema under `orchestration/contracts/` (not a hand-maintained list). Adding a contract is one new file + one parity test, nothing else.

B5. Tests: per-contract validation tests for every shipped payload shape produced today (i.e., the schema must accept what the runtime already emits — proves no breaking change), plus per-violation tests for each contract's most important rules (mirroring the SKILL-47 per-violation test pattern).

D2 (B-half). `AGENTS.md` documents the rule: "every schema under `orchestration/contracts/` is part of the runtime contract; add a parity test when adding a new one."

## Non-goals

- **Replacing `PlatformManifest`.** Out of scope for the whole parent feature.
- **Schema editing inside the UI.** Viewers stay read-only.
- **Versioning beyond a single `contract_version` per contract.** No multi-version validators.
- **Generating Kotlin types from the schemas.** Future task.
- **Renaming or restructuring existing telemetry events.** Subtask B describes today's payloads — if a payload is poorly shaped, fixing it is a separate task.
- **Per-repo customization of the four new schemas.** This subtask establishes the runtime contracts; per-repo extension via `x-runtime-anchored` is Subtask 3 and scoped to `platform-pack-schema.yaml` there.
- **Automating MCP-tool input-schema derivation from these schemas.** The parent spec's open question allows either build-time derivation or manual-with-parity-test; this subtask chooses manual-with-parity-test unless the implementer finds derivation is trivially cheap inside the same PR.

## Dependencies

Depends on Subtask 1 (SKILL-47 cleanup). Subtask 1 tightens the validator surface and shared test helper that every new schema relies on:
- C2 typed `validate(Map<String, Any?>, slug)` — the new schemas need the same typed entry-point shape.
- C7 classpath shadow guard — must apply to every new schema, not just the platform-pack one.
- C8 shared `repoRootFromTest()` — every new schema's parity test and validates-existing-emissions test consumes it.

## Validation strategy

`bill-quality-check` (runtime-kotlin Gradle `check`). Each schema lands with the existing emissions validating against it (proving no breaking change) and per-violation tests asserting the loud-fail typed exception triggers with the field path. The desktop auto-listing change must survive an integration test where dropping a new YAML into `orchestration/contracts/` makes it appear in the "Contracts" tree without code edits.

## Boundaries touched (high-level)

- `orchestration/contracts/` — four new YAML schemas.
- `runtime-kotlin/runtime-mcp` — `McpToolDispatcher`, `McpInputSchemas`, `McpWorkflowToolHandlers` (telemetry + workflow seams).
- `runtime-kotlin/runtime-application` — `LifecycleTelemetryPayloads`, `LifecycleTelemetryService`, `WorkflowService`.
- `runtime-kotlin/runtime-contracts/workflow/WorkflowContracts.kt`.
- `runtime-kotlin/runtime-domain/workflow` — `WorkflowModels`, `WorkflowEngine`, `FeatureImplement*Definition`, `FeatureVerify*Definition`.
- `runtime-kotlin/runtime-core/install` — `InstallApply`, `InstallPlanBuilder`.
- `runtime-kotlin/runtime-domain/install/model/InstallModels.kt`.
- `runtime-kotlin/runtime-core/nativeagent` — `NativeAgentBundle`, `NativeAgentComposition`, `NativeAgentDiscovery`.
- `runtime-kotlin/runtime-desktop/core/data/.../RuntimeRepoBrowserService.kt` — switch `loadContracts` from hard-coded leaf to `Files.list` filter.
- `runtime-kotlin/runtime-desktop/feature/skillbill` — `TreeItemKind.CONTRACT` selection detail unchanged in shape, fed by auto-listed leaves.
- `AGENTS.md` — D2 B-half rule paragraph.

## Recommended next prompt

`Run bill-feature-implement on .feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2_runtime-contracts.md`
