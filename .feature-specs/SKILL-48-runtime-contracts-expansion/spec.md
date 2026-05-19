# SKILL-48 — Runtime contracts expansion: per-repo schema, more contracts, SKILL-47 cleanup

Status: Proposed

## Sources

- SKILL-47 (`platform-pack-schema-source-of-truth`, merged 2026-05-19 as PR #129). That work established `orchestration/contracts/platform-pack-schema.yaml` as the canonical JSON Schema for `platform.yaml`, refactored `ShellContentLoader.buildPack` to validate via it, and surfaced the schema in a read-only desktop "Contracts" tree group.
- The SKILL-47 code-review findings register (10 Majors fixed in the loop, ~15 Minors/Nits deferred).
- The SKILL-47 non-goal list, which deliberately left three follow-ups unaddressed:
  - Per-repo schema customization.
  - Migrating other flows (telemetry, workflow, install, native-agent) to the same contract-as-source-of-truth pattern.
  - Replacing the `PlatformManifest` data model (out of scope here too).

## Problem

SKILL-47 proved the pattern but stopped at one contract. Three gaps remain:

1. **The runtime still has named dependencies on specific `platform.yaml` fields.** The schema *describes* more than the parser can *consume*. A repo author cannot add a field to their copy of the schema and have the runtime accept it — the runtime hard-references `baseline`, `declared_quality_check_file`, `declared_code_review_areas`, etc. The mental model from SKILL-47 ("a platform pack is a user-defined skill container") is only half delivered: the contract is visible, but it is still the shell author's contract, not the pack author's.
2. **Other runtime contracts have no canonical document.** Telemetry event payloads (`feature_implement_started`, `quality_check_finished`, `import_review`, `triage_findings`, etc.), the durable workflow-state schema, install plans, and the native-agent composition all have implicit shapes today. None of them are surfaced in the desktop "Contracts" group. Each one is a smaller version of the same drift problem SKILL-47 solved.
3. **SKILL-47 left ~15 Minor/Nit findings deferred.** Some are pure docs (KDoc on the nullable widening, schema `uniqueItems`), some are small refactors (the `Any?` validator interface; the unused `repoRootHint`; the two-layer validation overlap between schema and Kotlin), and one is a documentation note about which uncommitted work was bundled into the SKILL-47 commit so future archaeology doesn't get confused.

The user's stated mental model after SKILL-47: a platform pack is a user-defined skill container whose value is platform-aware override — not a predefined "must have baseline + code-review-areas" structure. Achieving that fully requires (1). Doing it well requires also (2) and (3) so the pattern is consistent across the runtime and the SKILL-47 boundary is clean.

This work is too large for one `bill-feature-implement` run and must decompose into subtasks. Each subtask should be small enough to verify independently; the implementation order below names the recommended split.

## Acceptance criteria

### Subtask A — Per-repo schema customization

A1. A repo author can add a field to `orchestration/contracts/platform-pack-schema.yaml` in their fork, and the runtime accepts a `platform.yaml` that uses that field without code changes — i.e., the schema is the contract, not the Kotlin parser's view of it.
A2. Fields the runtime *does* consume (`baseline`, `declared_quality_check_file`, `declared_code_review_areas`, `routing_signals`, `pointers`, `area_metadata`) are explicitly marked in the schema as "runtime-anchored" via an extension key (e.g. `x-runtime-anchored: true`). A test asserts every runtime-anchored field is referenced by name in Kotlin and that every name-referenced field in Kotlin is marked anchored — bijection.
A3. `PlatformManifest` (or a sibling type) exposes a `customFields: Map<String, Any?>` (or similarly typed) carrying every non-anchored top-level field from `platform.yaml` verbatim, so downstream consumers (renderers, authoring UI) can read pack-author-defined metadata without runtime support being added first.
A4. The desktop "Contracts" viewer continues to render the canonical schema; no per-repo divergent copy.
A5. Tests cover: (a) a `platform.yaml` with a new top-level field passes validation and the value is reachable via `customFields`; (b) a `platform.yaml` with a typo in a runtime-anchored field name still fails loudly with the field path in the error.

### Subtask B — Other runtime contracts under `orchestration/contracts/`

B1. The following implicit runtime contracts each get a canonical JSON Schema (Draft 2020-12 in YAML) under `orchestration/contracts/`, with the same prose-section pattern for any cross-field rules:
- `telemetry-event-schema.yaml` — describes the payload shape for every `mcp__skill-bill__*_started/finished` event currently emitted by the runtime.
- `workflow-state-schema.yaml` — describes the durable workflow-state shape used by `feature_implement_workflow_open/update/get`.
- `install-plan-schema.yaml` — describes the typed install-plan shape produced by `CliRuntime` and consumed by `install.sh` / desktop first-run.
- `native-agent-composition-schema.yaml` — describes the composition shape `NativeAgentComposition` produces.
B2. Each schema declares its own `contract_version` and is pinned to the corresponding Kotlin constant via a parity test (same pattern as SKILL-47's `PlatformPackSchemaContractVersionTest`).
B3. Each runtime consumer of the corresponding payload validates against its schema at the same seam where it currently parses — telemetry tools at their entry points, workflow-state tools, install-plan parsing in CLI, composition assembly in the runtime. Loud-fail via the existing typed exceptions where they exist; introduce minimal new typed exceptions only when none fits.
B4. The desktop "Contracts" tree group automatically lists every schema under `orchestration/contracts/` (not a hand-maintained list). Adding a contract is one new file + one parity test, nothing else.
B5. Tests: per-contract validation tests for every shipped payload shape produced today (i.e., the schema must accept what the runtime already emits — proves no breaking change), plus per-violation tests for each contract's most important rules (mirroring the SKILL-47 per-violation test pattern).

### Subtask C — SKILL-47 cleanup (Minors deferred during review + housekeeping)

C1. Two-layer validation overlap removed where safe: each shape-level rule (path-suffix patterns like `content.md`, pointer name `.md` rule, `..`-segment guard) lives in exactly one place — schema or Kotlin, not both. Where the Kotlin check carries human-readable message hints the schema cannot, document why the duplication remains.
C2. `PlatformPackSchemaValidator.validate(parsedYaml: Any?, slug: String)` tightens to `validate(parsedYaml: Map<String, Any?>, slug: String)` (or equivalent typed shape). `Any?` is removed from the cross-boundary contract.
C3. Unused `repoRootHint` parameter on `CanonicalPlatformPackSchemaValidator` is either dropped or wired through from `ShellContentLoader` so the on-disk fallback resolves the canonical schema deterministically when the classpath copy is unavailable (tests, IDE runs).
C4. `declared_code_review_areas` in the schema gets `uniqueItems: true`. Duplicates are rejected at the schema layer (today they slip through and rely on Map deduplication downstream).
C5. KDoc added on `PlatformManifest.routedSkillName` and `DeclaredFiles.baseline` documenting the nullable contract: "`baseline = null` ⇒ no code-review baseline; `routedSkillName` will be null/empty; downstream code-review composition is skipped." So future readers know the widening is deliberate.
C6. The Gradle Copy task in `runtime-kotlin/runtime-core/build.gradle.kts` adds `require(canonicalSchema.exists())` (or equivalent loud-fail at configure-time) so a misconfigured build cannot silently ship a JAR without the bundled schema.
C7. Classpath-resource shadow guard: after `loadSchema()`, assert the loaded schema's `$id` (or `contract_version` `const`) matches the expected value. A downstream JAR that ships a stale copy at the same resource path fails loud at startup instead of silently validating against the wrong contract.
C8. Test-helper consolidation: `repoRootFromTest()` (duplicated across `PlatformPackSchemaContractVersionTest`, `PlatformPackSchemaValidatesExistingPacksTest`, `PlatformPackSchemaViewerStateTest`, and a near-identical helper in `ShellContentLoaderParityTest`) is extracted to a single shared test helper.

### Subtask D — Documentation / archaeology (small, lands with whichever subtask ships first)

D1. `runtime-kotlin/agent/history.md` SKILL-47 entry already names the bundled-along work (schema-relaxation + SKILL-46 follow-up). No further docs needed unless future work is confused by it.
D2. `AGENTS.md` documents the per-repo customization contract (after Subtask A lands) and the rule "every schema under `orchestration/contracts/` is part of the runtime contract; add a parity test when adding a new one" (after Subtask B lands).

## Non-goals

- **Replacing `PlatformManifest`.** Same non-goal as SKILL-47. Even with custom fields, the in-memory shape consumers depend on stays as it is.
- **Schema editing inside the UI.** Same as SKILL-47 — viewers stay read-only.
- **Versioning beyond a single `contract_version` string per contract.** No schema migrations, no multi-version validators. Mismatch is still a hard error per contract.
- **Generating Kotlin types from the schemas.** A future task could investigate code-gen so the data classes track the schema automatically; this task does not.
- **Renaming or restructuring existing telemetry events.** Subtask B describes today's payloads. If a payload turns out to be poorly-shaped, fixing it is a separate task.

## Open questions

- **Subtask A: how to mark "runtime-anchored" fields.** Options: (a) an `x-runtime-anchored: true` keyword per property; (b) a separate top-level `x-runtime-fields: [..]` list; (c) a Kotlin annotation processor that auto-marks fields referenced by name. Recommend (a) — local to each property, no separate list to drift, and JSON Schema tooling ignores `x-*` extensions.
- **Subtask A: where `customFields` lives.** On `PlatformManifest` directly, on a sibling `PlatformPackCustomMetadata` companion, or as a parallel map keyed by slug somewhere else. Recommend on `PlatformManifest` so consumers do not need a second lookup.
- **Subtask B: scope of the four schemas.** Today's telemetry payloads are partly defined by Kotlin data classes inside the MCP server, partly by JSON Schemas embedded in the MCP tool registrations. The schemas under `orchestration/contracts/` should be the source of truth and the MCP-tool registrations should derive their input schemas from there. Confirm whether that derivation should be automated (build-time) or manual-with-parity-test.
- **Subtask B: ordering.** The four contracts are independent; pick one to land first as a template. Recommend `workflow-state-schema.yaml` because the workflow runtime already has the most stable shape and the smallest contract surface, so the pattern is easiest to verify before scaling.
- **Subtask C order:** can land as one small PR alongside Subtask A or B (it touches the same files), or as its own PR before either. Recommend bundling with Subtask A because A also touches the validator surface.

## Risks

- **Subtask A makes a public-API change to `PlatformManifest`.** Any downstream consumer reading the manifest now has to handle `customFields`. Mitigation: land A behind a feature flag (`platform_pack_custom_fields`) for one release if any out-of-tree consumers exist; otherwise audit in-tree consumers in the same PR.
- **Subtask B can grow without bound.** Telemetry alone has ~30 distinct events. Scoping each contract to a stable subset and growing from there is essential. Mitigation: each schema lists only payloads currently emitted; new payloads must add a schema entry.
- **Subtask B and Subtask A are tempting to combine.** They both touch the validator wrapper and the `orchestration/contracts/` directory. Resist — A is a model-shape change with downstream callers, B is an additive contract surface. Reviewing them as one PR is harder than reviewing them separately.
- **The two-layer validation cleanup (C1) could weaken error messages.** Some Kotlin checks today produce more readable error messages than the raw schema-validator output. If a check moves to schema-only, the test for that violation may need its assertion loosened. Mitigation: keep the Kotlin layer where its message is materially better, and document why.

## Implementation order

Decomposition into three subtasks; one per `bill-feature-implement` run. Subtask D (docs) lands inline with whichever subtask ships first.

1. **Subtask C — SKILL-47 cleanup.** Small, isolated, no public-API change. Lands first to leave a clean foundation. (`spec_subtask_1_skill47-cleanup.md` when decomposed.)
2. **Subtask B — other runtime contracts under `orchestration/contracts/`.** Pick one contract first (`workflow-state-schema.yaml` recommended), land it end-to-end (schema + parity test + runtime wiring + auto-listing in the desktop viewer), then expand to the remaining three. (`spec_subtask_2_runtime-contracts.md` — itself may need further splitting depending on review.)
3. **Subtask A — per-repo schema customization.** Lands last because it is the largest design exercise (`customFields` on `PlatformManifest`, `x-runtime-anchored` annotation, parity-test on the anchored-vs-name-referenced bijection). (`spec_subtask_3_per-repo-customization.md` when decomposed.)

When the planner is invoked on this spec via `bill-feature-implement`, it should return `mode: "decompose"` and emit the three subtask specs above.
