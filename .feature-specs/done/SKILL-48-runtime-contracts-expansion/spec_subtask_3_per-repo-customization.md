# SKILL-48 Subtask 3 — Per-repo schema customization

Status: Proposed

Parent spec: [spec.md](./spec.md)

## Scope

This subtask delivers the user-facing half of the parent spec's mental model: a platform pack is a user-defined skill container whose value is platform-aware override. A repo author can add fields to their fork of `orchestration/contracts/platform-pack-schema.yaml` and the runtime accepts a `platform.yaml` that uses those fields without any Kotlin change. The runtime's own anchored fields stay strongly typed and bijection-tested against the schema; everything else flows into a `PlatformManifest.customFields` map that downstream consumers (renderers, authoring UI, future tooling) can read without runtime support being added first. Includes the A-half of D2: the `AGENTS.md` per-repo customization contract documentation.

Per the parent spec's "Open questions" recommendations, this subtask uses option (a) for anchoring (`x-runtime-anchored: true` per property) and places `customFields` directly on `PlatformManifest`.

Deferred to other subtasks:
- The SKILL-47 validator-surface cleanup (Subtask 1 — depended on, not duplicated here).
- Extending `x-runtime-anchored` semantics to the other four runtime contracts introduced in Subtask 2. Those contracts are entirely runtime-anchored today; per-repo customization of telemetry/workflow/install/native-agent shapes is a future task.

## Acceptance criteria

A1. A repo author can add a field to `orchestration/contracts/platform-pack-schema.yaml` in their fork, and the runtime accepts a `platform.yaml` that uses that field without code changes — i.e., the schema is the contract, not the Kotlin parser's view of it.

A2. Fields the runtime *does* consume (`baseline`, `declared_quality_check_file`, `declared_code_review_areas`, `routing_signals`, `pointers`, `area_metadata`) are explicitly marked in the schema as "runtime-anchored" via an extension key (e.g. `x-runtime-anchored: true`). A test asserts every runtime-anchored field is referenced by name in Kotlin and that every name-referenced field in Kotlin is marked anchored — bijection.

A3. `PlatformManifest` (or a sibling type) exposes a `customFields: Map<String, Any?>` (or similarly typed) carrying every non-anchored top-level field from `platform.yaml` verbatim, so downstream consumers (renderers, authoring UI) can read pack-author-defined metadata without runtime support being added first.

A4. The desktop "Contracts" viewer continues to render the canonical schema; no per-repo divergent copy.

A5. Tests cover: (a) a `platform.yaml` with a new top-level field passes validation and the value is reachable via `customFields`; (b) a `platform.yaml` with a typo in a runtime-anchored field name still fails loudly with the field path in the error.

D2 (A-half). `AGENTS.md` documents the per-repo customization contract — the rule that `x-runtime-anchored` fields are the runtime's named dependencies and everything else is pack-author space accessible via `PlatformManifest.customFields`.

## Non-goals

- **Replacing `PlatformManifest`.** Same non-goal as the parent spec. The in-memory shape that existing consumers depend on stays as it is; `customFields` is additive.
- **Schema editing inside the UI.** Viewer stays read-only.
- **Versioning beyond a single `contract_version`.** Adding `x-runtime-anchored` is a schema extension, not a version bump beyond the existing single-version policy.
- **Generating Kotlin types from the schema.**
- **Renaming or restructuring existing telemetry events.**
- **Auto-typing or runtime support for non-anchored fields.** `customFields` is intentionally `Map<String, Any?>`; pack authors get raw values, not generated types.
- **Per-repo extension of any contract other than `platform-pack-schema.yaml`.** The other four contracts introduced in Subtask 2 remain runtime-anchored end-to-end.

## Dependencies

Depends on Subtask 1 and Subtask 2 (in that order, both landed).
- Subtask 1 provides the cleaned validator surface (C2 typed `validate`, C3 deterministic on-disk fallback, C7 classpath shadow guard) that the bijection test in A2 leans on.
- Subtask 2 establishes the precedent that every schema under `orchestration/contracts/` is a runtime contract with parity tests, so the bijection test fits the established pattern rather than inventing one. Subtask 2's D2 AGENTS.md rule is also a natural companion to A's per-repo customization rule — both land in the same doc section.

## Validation strategy

`bill-quality-check` (runtime-kotlin Gradle `check`). The bijection test in A2 is the linchpin: it must fail loudly if either side drifts (Kotlin starts reading a new field by name without marking it anchored in the schema, or the schema marks a field anchored that Kotlin no longer references). A5's two test cases (custom field reaches `customFields`; typo in anchored field name fails loudly with field path) ride the existing SKILL-47 per-violation test pattern. Per the parent spec's "Risks" section, since no out-of-tree consumers are known, audit in-tree consumers of `PlatformManifest` in this same subtask rather than guarding with a feature flag.

## Boundaries touched (high-level)

- `orchestration/contracts/platform-pack-schema.yaml` — add `x-runtime-anchored: true` on `baseline`, `declared_quality_check_file`, `declared_code_review_areas`, `routing_signals`, `pointers`, `area_metadata`.
- `runtime-kotlin/runtime-domain/scaffold/model/PlatformManifest.kt` — add `customFields: Map<String, Any?>` and KDoc.
- `runtime-kotlin/runtime-core/scaffold` — `ShellContentLoader.buildPack` populates `customFields` with every non-anchored top-level entry verbatim; `PlatformPackSchemaValidator` exposes the anchored-field set for the bijection test.
- `runtime-kotlin/runtime-core/src/test/...` — bijection test (A2), custom-field roundtrip test (A5a), typo-still-fails-loudly test (A5b).
- In-tree audit: every reader of `PlatformManifest` across runtime modules + desktop, ensuring `customFields` is either ignored safely or surfaced where appropriate.
- `AGENTS.md` — D2 A-half per-repo customization contract paragraph.

## Recommended next prompt

`Run bill-feature-implement on .feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_3_per-repo-customization.md`
