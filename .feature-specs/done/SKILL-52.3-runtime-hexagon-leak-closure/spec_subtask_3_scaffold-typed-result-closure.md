# SKILL-52.3 Subtask 3 - Scaffold Typed-Result Closure

Parent spec: [.feature-specs/SKILL-52.3-runtime-hexagon-leak-closure/spec.md](./spec.md)
Issue key: SKILL-52.3
Subtask order: 3 of 5
Depends on: none (independent of subtasks 1-2; sequence after them on the branch)
Branch model: same-branch, commit per subtask

## Purpose

Finish the scaffold *result* boundary that SKILL-52.1 subtask 3 typed the
*producers* for but left dual-representation: the eight `Scaffold*Result` port
DTOs still carry a raw `payload: Map<String, Any?>` alongside typed scalar
fields, guarded by `init {}` desync `require(...)` checks. Make the port a fully
typed contract and move wire serialization to the adapters that own the wire
shape.

## Scope

In scope:

- The eight port result DTOs (all currently `@OpenBoundaryMap`):
  - `skillbill.ports.scaffold.catalog.model.ScaffoldListResult.payload`
  - `skillbill.ports.scaffold.catalog.model.ScaffoldShowResult.payload`
  - `skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult.payload`
  - `skillbill.ports.scaffold.repo.model.ScaffoldValidateResult.payload`
  - `skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult.payload`
  - `skillbill.ports.scaffold.source.model.ScaffoldFillResult.payload`
  - `skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult.payload`
  - `skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult.payload`
- For each DTO: lift every key currently read out of `payload` into a typed
  field (scalars, lists of typed entries, nested typed records). Delete the
  `payload: Map<String, Any?>` field and the `init {}` desync `require(...)`
  blocks (e.g. `ScaffoldEditWithBodyFileResult.kt:22-33`,
  `ScaffoldListResult.kt:35`).
- Move wire-shape (JSON/MCP) serialization from "return the `payload` map
  verbatim" to adapter-owned mappers that build the wire map from the typed
  fields. The CLI mappers already exist
  (`ScaffoldCliResultMappers`); extend/replace them so the adapter owns the
  ordered-map emission and the infra adapter returns only typed fields.
- Update the infra-fs scaffold adapters
  (`skillbill.infrastructure.fs` scaffold gateways) to populate the typed fields
  instead of (or in addition to, then collapse to) the legacy `linkedMapOf`
  payload.
- Update the desktop gateway/mapper readers
  (`runtime-desktop/core/data/.../service/mapper/`,
  `RuntimeRepoBrowserService.kt`, `ValidationSummaryMapper.kt`) to consume the
  typed fields instead of `payload["..."]` reads. This removes the last
  `.payload[` reads guarded by `RuntimeDesktopGatewayPolicyTest`.
- Remove the eight allow-list rows from `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST`
  (`RuntimeArchitectureTest.kt`) and from the `ARCHITECTURE.md`
  `open-boundary-allowlist` block, and remove the corresponding `@OpenBoundaryMap`
  annotations.

Out of scope:

- Changing CLI/MCP JSON output shape or desktop scaffold UX — output must stay
  byte-equivalent where golden tests exist.
- `PlatformManifest.customFields` (genuinely open) — retained.
- Workflow artifacts / other allow-list families (subtask 4).
- Scaffold command *input* typing — already done in SKILL-52.2 subtask 2.

## Acceptance Criteria

1. The eight `Scaffold*Result` DTOs expose typed fields only; no
   `payload: Map<String, Any?>` field and no `init {}` desync `require` block
   remains on them.
2. Wire-map emission for scaffold results lives in adapter-owned mappers
   (`runtime-cli` / `runtime-mcp` / `runtime-desktop`), built from typed fields.
3. CLI and MCP scaffold output is byte-equivalent to the pre-change golden
   snapshots; desktop scaffold/validate/list behavior is unchanged.
4. The eight `skillbill.ports.scaffold.*Result.payload` rows are removed from
   `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` and `ARCHITECTURE.md`, and the
   `every OpenBoundaryMap annotated declaration is documented in the
   architecture allow-list` parity test still passes with the reduced set.
5. `RuntimeDesktopGatewayPolicyTest` no longer needs to exempt `.payload[`
   reads in `RuntimeRepoBrowserService.kt` (the reads are gone); update the test
   to forbid them outright.
6. Focused scaffold, CLI, MCP, desktop, and architecture tests pass.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Scaffold*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*Scaffold*')
(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test --tests '*Scaffold*')
(cd runtime-kotlin && ./gradlew ':runtime-desktop:core:data:jvmTest')
(cd runtime-kotlin && ./gradlew ':runtime-desktop:feature:skillbill:jvmTest')
```

## Implementation Notes

- These DTOs are the canonical exemplars of the "typed-result-model
  open-boundary pattern" documented in `ARCHITECTURE.md`. Closing them removes
  the pattern's own examples, so update that ARCHITECTURE.md section to describe
  the pattern as retired for scaffold (keep `PlatformManifest.customFields` /
  `WorkflowSnapshotView.artifacts` as the remaining exemplars).
- The byte-equivalence risk is key ordering. The legacy `payload` was an ordered
  `LinkedHashMap`; the adapter mappers must emit keys in the same order. Drive
  this from the existing golden snapshots under
  `runtime-cli/src/test/resources/golden` and
  `runtime-mcp/src/test/resources/golden` — run with `-Pupdate-snapshots` only
  to diff, never to bless a changed shape.
- Migrate one DTO end-to-end (port field -> infra populate -> adapter mapper ->
  allow-list row removal -> golden green) before the others, to lock the pattern.
- The scaffold result wire emission previously lived partly in
  `ScaffoldCliResultMappers` returning the `payload` field directly; that file
  becomes the home of the typed-to-wire mapping.
