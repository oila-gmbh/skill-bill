# SKILL-52.1 Subtask 3 — Scaffold Raw-Map Elimination + IO-Coupled Validator Carve

Parent spec: [.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md](./spec.md)
Issue key: SKILL-52.1
Subtask order: 3 of 6 (re-decomposed from original subtask 2)
Depends on: subtask 1 (typed boundary foundation) and subtask 2 (scaffold capability
ports + pure-policy extraction).
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on
completion before subtask 4.

## Why this is split from subtask 2

Subtask 2 carves the capability-port shape, typed request/result models, and the
genuinely pure-policy extraction. This subtask finishes the original "scaffold-policy-
extraction" intent by:

1. Replacing `ScaffoldGateway` raw-map returns with typed result models in
   `runtime-application` + ports.
2. Introducing byte-equivalent CLI/MCP mappers from the typed results to the
   pre-existing wire shape.
3. Carving the IO-coupled validator surface
   (`validateBaselineLayerPayloadReferences`, `validateScaffold`,
   `resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`,
   `plannedAuthoringTarget`) into capability-port-aligned adapter classes inside
   `runtime-infra-fs` (NOT moved to the domain — they remain IO-coupled).
4. Dropping the 18 scaffold allow-list entries from `RuntimeArchitectureTest.kt` and
   `ARCHITECTURE.md`.

Doing this in its own subtask keeps the wire-mapper reconstruction work — and the
~1500 LOC of golden assertions in `CliScaffoldRuntimeTest.kt` (452 LOC) +
`McpRuntimeTest.kt` (1040 LOC) — isolated from the structural carving in subtask 2.

## Scope

Covers parent acceptance criteria: **AC1 (completed — IO-coupled validators now live
in capability-aligned adapter classes, full surface ownership documented)**,
**AC4 (strict — no `ScaffoldGateway`/`ScaffoldService` raw `Map<String, Any?>` FQNs in
the allow-list)**, **AC5 (byte-equivalent CLI/MCP outputs)**, **AC8**.

In scope:

### Raw-map elimination at `ScaffoldGateway`

Replace `ScaffoldGateway` raw-map return types for the 9 producers (list, show,
explain, validate, upgrade, fill, edit, saveExactContent, scaffold) with typed result
models. Place the typed models under `runtime-ports/src/main/kotlin/skillbill/ports/scaffold/<capability>/model/`
where they fit naturally next to the capability ports introduced in subtask 2.

**Open-boundary pattern choice (load-bearing).** Use the same pattern that
`PlatformManifest.customFields` and `WorkflowSnapshotView.artifacts` already use: each
typed result model carries typed fields PLUS a single `payload: Map<String, Any?>`
property annotated with the documented `@OpenBoundaryMap` annotation. The
`payload` field carries the byte-equivalent wire shape forward without forcing every
optional/conditional key in the existing `linkedMapOf` outputs to be modeled as a
strongly typed field.

This interpretation satisfies AC4 strictly: the open-boundary allow-list entries land
on the new documented typed-model FQNs (e.g.
`skillbill.ports.scaffold.list.model.ScaffoldListResult#payload`), **never** on
`ScaffoldGateway` or `runtime-infra-fs.ScaffoldService` FQNs. The spec must call this
out in `ARCHITECTURE.md`: the rule is "no raw maps on service/gateway public APIs;
documented typed-model payload fields are the explicit open-boundary seam."

### CLI and MCP mappers

Introduce `ScaffoldCliResultMappers` (under `runtime-cli/src/main/kotlin/...`) and
`ScaffoldMcpResultMappers` (under `runtime-mcp/src/main/kotlin/...`) that translate
the new typed result models into the existing CLI JSON shape and the existing MCP
envelope shape. Mappers consume the typed fields PLUS the `payload` open-boundary map
to reconstruct the byte-equivalent output.

**Avoid wire-mapper triplication** (boundary-history pitfall): mappers live in the
adapter modules only, not in the application layer. The application layer returns
typed results; adapters own the wire shape.

### IO-coupled validator carve

Move `validateBaselineLayerPayloadReferences`, `validateScaffold`,
`resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`, and
`plannedAuthoringTarget` out of the monolithic
`runtime-infra-fs/scaffold/ScaffoldService.kt` and into capability-port-aligned
adapter classes inside `runtime-infra-fs` (e.g. `FileSystemRepoValidation`,
`FileSystemSourceLoading`, etc., as introduced in subtask 2). They stay in
`runtime-infra-fs` because they transitively depend on `loadPlatformPack` and
`ShellContentLoader.unsupportedCompositionModeReason` — they are IO-coupled, not pure
policy. This carve gives them a capability-aligned home so the
`ImplementationOwnershipArchitectureTest` ownership rules can express "these
validators belong to repo-validation / source-loading capability adapters."

### Allow-list cleanup

- Remove the 18 scaffold raw-map allow-list entries from
  `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`.
- Remove the corresponding documentation entries from `runtime-kotlin/ARCHITECTURE.md`.
- Replace with the new documented `@OpenBoundaryMap` payload-field entries on typed
  result models (only — not on services/gateways).

### Architecture coverage

- Re-run the subtask 1 raw-map arch test — it must now pass with zero
  `ScaffoldGateway` / `runtime-infra-fs.ScaffoldService` raw-map allow-list entries.
- Extend `ImplementationOwnershipArchitectureTest` to express ownership of the
  IO-coupled validators in their capability-aligned adapter classes.

### Preserved invariants

- Byte-equivalent CLI/MCP output verified by existing
  `CliScaffoldRuntimeTest.kt` (8 tests, 452 LOC) and `McpRuntimeTest.kt` (1040 LOC) —
  these are the contract guardrails for AC5.
- Atomic rollback behavior — unchanged.
- `content.md` -> generated `SKILL.md` boundary — unchanged.

Out of scope:

- Install policy extraction (subtask 4).
- `Path` policy decision (subtask 5).
- `runtime-core` API shrink (subtask 5).
- Generated-output staging redesign or schema codegen.
- Moving IO-coupled validators into `runtime-domain` (explicit non-goal — they are
  IO-coupled and stay in `runtime-infra-fs`).

## Acceptance criteria

1. (Completion of AC1) IO-coupled validators
   (`validateBaselineLayerPayloadReferences`, `validateScaffold`,
   `resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`,
   `plannedAuthoringTarget`) live in capability-aligned adapter classes inside
   `runtime-infra-fs`. Ownership is expressed in
   `ImplementationOwnershipArchitectureTest`.
2. (AC4, strict) `ScaffoldGateway` no longer exposes raw `Map<String, Any?>` return
   types. The subtask 1 raw-map arch test passes with zero allow-list entries on
   `ScaffoldGateway`, `runtime-infra-fs.ScaffoldService`, or
   `runtime-application.ScaffoldService` FQNs.
3. The open-boundary pattern is documented in `ARCHITECTURE.md`: typed result models
   may carry a single `@OpenBoundaryMap`-annotated `payload: Map<String, Any?>`
   field; service/gateway public APIs may not.
4. (AC5) CLI JSON outputs and MCP envelope outputs for
   `scaffold list/show/explain/validate/upgrade/fill/edit` are byte-equivalent to
   pre-change goldens — verified by the existing 8 tests in
   `CliScaffoldRuntimeTest.kt` and the matching tests in `McpRuntimeTest.kt`.
5. CLI and MCP scaffold mappers (`ScaffoldCliResultMappers`,
   `ScaffoldMcpResultMappers`) live in the adapter modules only. No wire-mapping
   logic in `runtime-application` (wire-mapper triplication pitfall).
6. The 18 scaffold allow-list entries removed from `RuntimeArchitectureTest.kt` are
   replaced (where still needed for byte-equivalence) by documented entries on the
   new typed-model `payload` FQNs.
7. (AC6) All existing scaffold rollback tests still pass — semantics unchanged.
8. (AC8) `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-goals

- Do not move IO-coupled validators into `runtime-domain` (they require
  `PlatformManifestLookup` / `DirectoryProbe` threading that is out of scope).
- Do not introduce strongly typed fields for every optional/conditional key in the
  existing `linkedMapOf` outputs — use the documented `payload: Map<String, Any?>`
  open-boundary seam instead.
- Do not redesign scaffold CLI prompts, command names, or persisted manifest formats.
- Do not change the source/generated boundary or `content.md` -> `SKILL.md` flow.
- Do not introduce Kotlin codegen from YAML schemas.
- Do not address install policy here (subtask 4) or path/core shrink (subtask 5).

## Dependencies

- Subtask 1: typed-boundary arch test + `@OpenBoundaryMap` annotation convention.
- Subtask 2: capability ports + typed request/result model packages already exist;
  pure-policy extraction landed.

## Reference files

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt`
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/scaffold/...` (capability packages from subtask 2)
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt` (1358 LOC — IO-coupled validators carve out into capability-aligned adapter classes)
- `runtime-kotlin/runtime-cli/src/main/kotlin/...` (scaffold CLI handlers — landing zone for `ScaffoldCliResultMappers`)
- `runtime-kotlin/runtime-mcp/src/main/kotlin/...` (scaffold MCP handlers — landing zone for `ScaffoldMcpResultMappers`)
- `runtime-kotlin/runtime-cli/src/test/.../CliScaffoldRuntimeTest.kt` (452 LOC, 8 tests — byte-equivalence guardrail)
- `runtime-kotlin/runtime-mcp/src/test/.../McpRuntimeTest.kt` (1040 LOC — byte-equivalence guardrail)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt` (18 scaffold allow-list entries — drop)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ImplementationOwnershipArchitectureTest.kt`
- `runtime-kotlin/ARCHITECTURE.md`
- Existing `@OpenBoundaryMap` usages on `PlatformManifest.customFields` and
  `WorkflowSnapshotView.artifacts` as the pattern template.

## Validation strategy

Primary: `bill-quality-check`.

Full local pass:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*CliScaffoldRuntimeTest*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*McpRuntimeTest*')
(cd runtime-kotlin && ./gradlew check)
```

`skill-bill validate`, `scripts/validate_agent_configs`, and `npx --yes agnix --strict .`
run in the final-validation subtask.

## Recommended next prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_3_scaffold-raw-map-elimination.md
```

After completion, commit on `feat/SKILL-52.1-hexagonal-runtime-hardening`, then
proceed to subtask 4 (Install Policy Extraction —
`spec_subtask_3_install-policy-extraction.md`).
