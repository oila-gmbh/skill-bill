# SKILL-52.3 Subtask 4 - Application Wire-Seam Relocation + Open-Boundary Reconciliation

Parent spec: [.feature-specs/SKILL-52.3-runtime-hexagon-leak-closure/spec.md](./spec.md)
Issue key: SKILL-52.3
Subtask order: 4 of 5
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Remove the last direct Jackson dependency from `runtime-application`, and
reconcile the raw-map allow-list with reality so it no longer carries stale
future-tense removal promises. Either type the entries marked for removal by
SKILL-52.1 subtasks 3/4, or relabel them as accepted permanent open boundaries
with a recorded rationale.

## Scope

In scope — application wire seam:

- `runtime-application/.../DecompositionManifestFileWrites.kt:43` —
  `decompositionManifestYamlMapper.writeValueAsString(wireMap)`. Move YAML
  *serialization* out of `runtime-application`:
  - preferred: own decomposition-manifest YAML encode + decode together behind
    the `DecompositionManifestValidator`/codec seam introduced in subtask 1
    (encode lives next to the validator impl in `runtime-infra-fs`, or in a
    contracts codec that does not import Jackson by routing through the infra
    port); or
  - expose a `DecompositionManifestFileStore.writeManifest(typedModel)` port
    method so the infra adapter owns serialization and the application passes a
    typed model.
- Remove `jackson.dataformat.yaml` from
  `runtime-application/build.gradle.kts` once no application source imports
  `com.fasterxml.jackson`.
- Update `DecompositionManifestArchitectureTest` (which currently asserts
  `applicationSeam` contains `"YAMLMapper"`) to assert the new ownership: the
  application seam no longer names `YAMLMapper`, and the infra/codec seam does.

In scope — open-boundary reconciliation:

- For each allow-list entry currently labeled in `ARCHITECTURE.md` as
  "Subtask 3 will remove" (system service / install policy) or
  "Subtask 4 will remove" (lifecycle telemetry payload surfaces and supporting
  top-level helpers), make a per-entry decision and apply it:
  - `skillbill.application.SystemService.doctor`,
    `skillbill.application.SystemService.version`
  - `skillbill.application.lifecycleOkPayload`,
    `lifecycleSkippedPayload`, `lifecycleErrorPayload`,
    `orchestratedStartedSkippedPayload`, `orchestratedPayload`
  - `skillbill.application.LifecycleTelemetryService.*` (7 methods)
  - any `install policy` raw-map surfaces still flagged
  - Decision per entry: (a) introduce a typed result/request model, move the
    map emission to the adapter, and delete the allow-list row; or (b) if the
    surface is a genuine open telemetry/extension boundary, relabel it in
    `ARCHITECTURE.md` as an accepted permanent open boundary with a one-line
    rationale and remove the "Subtask N will remove" wording.
- After reconciliation, no `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` entry and no
  `ARCHITECTURE.md` allow-list bullet carries a future-tense removal promise.
- Keep the genuinely-open entries (`PlatformManifest.customFields`,
  `WorkflowSnapshotView.artifacts`, caller-supplied workflow artifact/step
  patch maps) and record them as permanent.

Out of scope:

- Scaffold result DTOs (subtask 3).
- Schema validator extraction (subtask 1).
- Changing telemetry event names, the `doctor`/`version` output shape, or
  lifecycle payload wire keys — only where they are produced/typed changes.

## Acceptance Criteria

1. `runtime-application` main source imports no `com.fasterxml.jackson.*`, and
   `runtime-application/build.gradle.kts` no longer declares
   `jackson.dataformat.yaml`.
2. Decomposition-manifest YAML serialization is owned by an infra/codec/port
   seam; `DecompositionManifestArchitectureTest` asserts the new ownership and
   still proves the manifest write path loud-fails on invalid input.
3. Every allow-list entry previously marked "Subtask 3/4 will remove" is either
   typed and removed, or relabeled as an accepted permanent open boundary with a
   recorded rationale. No allow-list bullet contains "will remove" / future-tense
   removal wording.
4. The `open-boundary allow-list documents required exceptions` and
   `every OpenBoundaryMap annotated declaration is documented in the architecture
   allow-list` parity tests pass against the reconciled set.
5. CLI/MCP `doctor`, `version`, and lifecycle telemetry output remains
   byte-equivalent where golden tests exist; persisted telemetry payloads are
   unchanged.
6. Focused application, telemetry, system, workflow, and architecture tests
   pass.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-application:test)
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*System*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Telemetry*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*Telemetry*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*Lifecycle*')
```

## Implementation Notes

- The cleanest end state pairs decode + encode: subtask 1 already inverts
  decomposition *validation/parse* behind a port; this subtask moves the matching
  *serialize* call so the application stops touching Jackson entirely. Prefer a
  single `DecompositionManifestFileStore`/codec seam that owns both directions.
- Reconciliation is a judgement call per entry. Lifecycle telemetry payloads are
  the strongest candidates for "accepted permanent open boundary" (they are
  forward-compatible event bags); `SystemService.doctor`/`version` are better
  typed (fixed, known fields). Make the call explicitly and write it down — the
  goal is to end the "temporary debt" status, not necessarily to type everything.
- Record each reconciliation decision in `agent/decisions.md` (final wording can
  land in subtask 5, but capture the rationale as you go).
