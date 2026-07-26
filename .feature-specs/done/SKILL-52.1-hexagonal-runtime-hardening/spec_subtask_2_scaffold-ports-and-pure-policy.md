# SKILL-52.1 Subtask 2 — Scaffold Capability Ports + Pure-Policy Extraction

Parent spec: [.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md](./spec.md)
Issue key: SKILL-52.1
Subtask order: 2 of 6 (re-decomposed from original subtask 2)
Depends on: subtask 1 (typed boundary foundation + raw-map arch test must be in place).
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before subtask 3.

## Why this comes after subtask 1, and why it is split from raw-map elimination

The original "scaffold-policy-extraction" subtask attempted to do (a) capability-port
shape, (b) pure-policy extraction, AND (c) raw-map elimination at the
`ScaffoldGateway` boundary in a single feature-implement run. The first implementation
attempt stopped because:

- A large fraction of the candidate "policy" (`validateBaselineLayerPayloadReferences`,
  `validateScaffold`, `resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`,
  `plannedAuthoringTarget`) transitively depends on `loadPlatformPack` (reads
  `platform.yaml`) and `ShellContentLoader.unsupportedCompositionModeReason`. Moving
  them into `runtime-domain` requires threading new probes (`PlatformManifestLookup`,
  `DirectoryProbe`) through every caller — a sweeping refactor on its own.
- 9 `AuthoringOperations` raw-map producers (list/show/explain/validate/upgrade/fill/
  edit/saveExactContent/scaffold) are filesystem projections, not pure policy — they
  read `target.contentFile`, scan directories via `discoverTargets`, and conditionally
  shape `linkedMapOf` payloads against ~1500 LOC of golden assertions in
  `CliScaffoldRuntimeTest.kt` (452 LOC) and `McpRuntimeTest.kt` (1040 LOC).
- Doing wire-mapper reconstruction at the same time as policy extraction risks the
  "wire-mapper triplication" pitfall called out in subtask 1's boundary history.

This subtask therefore owns the structural foundation: capability ports + typed
request/result models + the genuinely pure-policy extraction. Subtask 3 owns the
raw-map elimination + IO-coupled validator extraction against the typed-result wire
shape.

## Scope

Covers parent acceptance criteria: **AC3 (capability-port shape with typed
request/result models)**, **AC2 (filesystem adapters still own IO)**, **AC1 (partial —
genuinely pure policy moves inward)**, **AC6 (rollback preserved — by inaction)**,
**AC7 (arch test rejects scaffold planner/validator policy in `runtime-infra-fs`
adapters for the extracted-policy surface)**, **AC8**.

In scope:

### Capability-port surface

Reshape `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt`
into five capability-named ports (matching the `TelemetryLevelMutator` shape from
boundary history):

1. **Source loading** — load scaffold source, platform-pack manifests, baseline-layer
   manifests, addon-consumer skill catalogs.
2. **Manifest persistence** — read/write platform manifests, install manifests,
   baseline-layer manifests with atomic semantics.
3. **Generated-file staging** — stage generated `SKILL.md` and related artifacts into
   the working tree (preserving the `content.md` -> generated boundary in
   `docs/skill-source-generation.md`).
4. **Install link application** — apply install-target symlinks/agent-config edits
   tied to scaffold operations (the bridge to install ports).
5. **Repo validation** — probe repo invariants relevant to scaffold pre-conditions
   (no IO mutation; purely existence/state probes).

Each capability port owns typed request/result models under
`runtime-ports/src/main/kotlin/skillbill/ports/scaffold/<capability>/model/`.

Adapter implementations under
`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/` are added as
`FileSystem<Capability>` classes that **delegate into the EXISTING (unshrunk)
`ScaffoldService` + `AuthoringOperations`** for behavior. This subtask deliberately
avoids splitting the 1358 LOC `ScaffoldService.kt`; it only carves new typed
entry-points around it.

### Pure-policy extraction into `runtime-domain`

Move the genuinely pure (no filesystem/process access in the function body itself,
even if callers are IO-coupled) policy functions inward. Initial candidate list from
the original implementation digest:

- `detectKind`
- `validatePayloadVersion`
- the pure-parse half of `parseBaselineLayerPayload` (string -> typed parse result)
- `optionalSpecialistSubagents`
- `rejectLeafSubagentSpecialists`
- `rejectBaselineLayersForNonPlatformPack`
- `resolvePlatformPackSelection`
- `platformPackNotes`
- `resolvePlatformPackDefaults`
- `buildPlatformPackInstallPaths`
- `renderPlatformPackManifestContent`

Each extracted function lands under `runtime-kotlin/runtime-domain/src/main/kotlin/...`
with focused unit tests. **Do not** chase IO-coupled validators
(`validateBaselineLayerPayloadReferences`, `validateScaffold`,
`resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`,
`plannedAuthoringTarget`) into the domain in this subtask — they require port
plumbing that belongs to subtask 3.

### Application-layer wiring (limited)

Update `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt`
to call the new pure-policy domain functions where they sit on the call path AND to
acquire the new capability ports via DI. Raw-map `ScaffoldGateway` entry points stay
in place for now — they are the explicit target of subtask 3.

### Architecture coverage

- Add `ScaffoldPolicyArchitectureTest` (or extend
  `ImplementationOwnershipArchitectureTest`, per boundary history "extend, don't
  duplicate") that forbids `runtime-infra-fs` imports inside the extracted-policy
  packages under `runtime-domain` and `runtime-application`.
- Document the new capability-port surface and pure-policy ownership in
  `runtime-kotlin/ARCHITECTURE.md` (source of truth first, then tests).
- **Do not** remove existing `ScaffoldGateway` raw-map allow-list entries from
  `RuntimeArchitectureTest.kt` / `ARCHITECTURE.md` — those drop in subtask 3 with the
  raw-map elimination.

### Preserved invariants

- Atomic rollback behavior — preserved by inaction (no rollback code is touched).
- `content.md` / generated `SKILL.md` boundary and install-staging exclusions per
  `docs/skill-source-generation.md` — preserved (no generated files committed).
- All existing scaffold CLI/MCP goldens — unchanged, because adapter behavior is
  unchanged and `ScaffoldGateway` raw-map returns are unchanged.

Out of scope:

- `ScaffoldGateway` raw-map elimination (moves to subtask 3).
- IO-coupled validator extraction
  (`validateBaselineLayerPayloadReferences`, `validateScaffold`,
  `resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`,
  `plannedAuthoringTarget`) — moves to subtask 3.
- Splitting the 1358 LOC `runtime-infra-fs/scaffold/ScaffoldService.kt` body — its
  internals stay; only new capability adapters are added that delegate into it.
- Install policy extraction (subtask 4).
- `Path` policy decision and migration (subtask 5).
- `runtime-core` API shrink (subtask 5).
- Removing the 18 scaffold allow-list entries from arch tests / `ARCHITECTURE.md`.

## Acceptance criteria

1. (Partial AC1) Scaffold kind detection, payload-version validation, the pure-parse
   half of baseline-layer payload parsing, specialist-subagent rejection rules,
   platform-pack selection/defaults/notes, install-path building, and manifest
   content rendering live in `runtime-domain` with focused unit tests. Remaining
   IO-coupled validators are explicitly deferred to subtask 3.
2. (AC2) `runtime-infra-fs` scaffold adapters still own file IO, symlink operations,
   manifest persistence, rollback file operations, generated artifact staging, and
   install target mutation — no IO is moved out.
3. (AC3) The scaffold port surface exposes five capability-named ports — source
   loading, manifest persistence, generated-file staging, install link application,
   repo validation — each with typed request/result models under
   `ports/scaffold/<capability>/model/`. `FileSystem<Capability>` adapters in
   `runtime-infra-fs` implement them.
4. (AC6) All existing scaffold rollback tests continue to pass — rollback semantics
   are preserved by inaction.
5. (AC7) A new architecture test (`ScaffoldPolicyArchitectureTest` or extension of
   `ImplementationOwnershipArchitectureTest`) rejects any `runtime-infra-fs` import
   inside the extracted-policy packages under `runtime-domain` and
   `runtime-application`.
6. `runtime-kotlin/ARCHITECTURE.md` documents the new capability-port surface and the
   pure-policy ownership boundary (source-of-truth update precedes arch test change).
7. CLI JSON outputs and MCP envelope outputs for
   `scaffold list/show/explain/validate/upgrade/fill/edit` are unchanged (verified by
   existing goldens — no mapper rework happens here).
8. (AC8) `(cd runtime-kotlin && ./gradlew check)` passes.

Strict deferral note: parent ACs **AC4 (no raw `Map<String, Any?>` from
`ScaffoldGateway`)** and the full completion of **AC1** are explicitly NOT claimed in
this subtask and move to subtask 3.

## Non-goals

- Do not eliminate `ScaffoldGateway` raw-map returns.
- Do not drop the 18 scaffold allow-list entries from `RuntimeArchitectureTest.kt` or
  `ARCHITECTURE.md`.
- Do not move adapter-only code inward (rollback file IO, symlink mechanics, process
  env) just to shrink `runtime-infra-fs`.
- Do not redesign scaffold CLI prompts, command names, or persisted manifest formats.
- Do not change the source/generated boundary or `content.md` -> `SKILL.md` flow.
- Do not introduce Kotlin codegen from YAML schemas.
- Do not chase IO-coupled validators inward in this subtask.

## Dependencies

- Subtask 1: typed-boundary arch test + workflow typed models on the parent branch.

## Reference files

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt`
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt`
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt` (1358 LOC — only new capability adapters land here this subtask)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ImplementationOwnershipArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/DecompositionManifestArchitectureTest.kt` (projection-pattern guard model)
- `runtime-kotlin/ARCHITECTURE.md`
- `docs/skill-source-generation.md`
- Existing typed DTO packages under `ports/<area>/model/` as shape templates
  (telemetry-level, install, scaffold catalog/render, repo-validation, persistence).
- `runtime-kotlin/runtime-cli/src/test/.../CliScaffoldRuntimeTest.kt` (452 LOC, golden coverage — unchanged this subtask)
- `runtime-kotlin/runtime-mcp/src/test/.../McpRuntimeTest.kt` (1040 LOC, golden coverage — unchanged this subtask)

## Validation strategy

Primary: `bill-quality-check`.

Full local pass:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew check)
```

`skill-bill validate`, `scripts/validate_agent_configs`, and `npx --yes agnix --strict .`
run in the final-validation subtask.

## Recommended next prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_2_scaffold-ports-and-pure-policy.md
```

After completion, commit on `feat/SKILL-52.1-hexagonal-runtime-hardening`, then
proceed to subtask 3 (Scaffold Raw-Map Elimination).
