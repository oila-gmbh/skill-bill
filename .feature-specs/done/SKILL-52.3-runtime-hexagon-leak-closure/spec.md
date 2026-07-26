# SKILL-52.3 - Runtime Hexagon Leak Closure

Created: 2026-05-28
Status: Draft
Issue key: SKILL-52.3
Parent: SKILL-52 - Full hexagonal runtime architecture
Predecessor: SKILL-52.2 - Runtime Boundary Closure

## Decomposition

This feature is intentionally decomposed because the remaining hexagonal leaks
cross the contracts leaf, domain policy, ports, and the architecture-test
enforcement layer. Implement on one branch with a commit per subtask:

1. [Schema Validator Extraction + Install/Decomposition Validator Ports](./spec_subtask_1_schema-validator-extraction.md)
2. [Domain Effect Purity](./spec_subtask_2_domain-effect-purity.md)
3. [Scaffold Typed-Result Closure](./spec_subtask_3_scaffold-typed-result-closure.md)
4. [Application Wire-Seam Relocation + Open-Boundary Reconciliation](./spec_subtask_4_application-wire-seam-and-open-boundary-reconciliation.md)
5. [Enforcement Hardening + External-Schema Decision + Final Lock](./spec_subtask_5_enforcement-hardening-and-final-lock.md)

## Sources

- Architecture assessment from 2026-05-28 (six per-layer audits + enforcement-suite review):
  - `runtime-kotlin/ARCHITECTURE.md`
  - `runtime-kotlin/agent/decisions.md`
  - `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/`
  - `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaValidator.kt`
  - `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaValidator.kt`
  - `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaValidator.kt`
  - `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestCoherenceValidator.kt`
  - `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanWireMap.kt`
  - `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/policy/InstallPlanPolicy.kt`
  - `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/telemetry/TelemetryConfigRules.kt`
  - `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/telemetry/TelemetryRemoteStatsRuntime.kt`
  - `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/domain/skillremove/SkillRemove.kt`
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/DecompositionManifestFileWrites.kt`
  - `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/scaffold/` (eight `Scaffold*Result` DTOs)
- Reference pattern (the design this feature generalizes):
  - `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowSnapshotValidator.kt` (domain-owned validator port)
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/WorkflowSnapshotValidatorAdapter.kt` (application-wired impl)
- Verification baseline:
  - `(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')` passes on `main` as of 2026-05-28 (findings below are green-by-construction, not test failures).
- Predecessor specs:
  - `.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md`
  - `.feature-specs/SKILL-52.2-runtime-boundary-closure/spec.md`

## Context

SKILL-52 created the physical hexagonal split, SKILL-52.1 hardened it (typed
boundaries, capability ports, inert `Path` policy, raw-map allow-list
governance, `runtime-core` shrink), and SKILL-52.2 closed the scaffold command
input, review/telemetry, and workflow-schema-ownership boundaries. The module
graph and dependency direction are sound and well-defended by architecture
tests.

The 2026-05-28 assessment confirmed there are **no unsanctioned cross-layer
imports in main source**. The remaining problems are leaks that the current
tests pass *by construction* — they are formalized into allow-lists,
`@OpenBoundaryMap` annotations, and the placement of infrastructure logic inside
the foundational `runtime-contracts` leaf — rather than removed. One of them is a
genuine hexagonal violation that the tests actively enshrine.

## Problem

The runtime is hexagonal in shape but leaks infrastructure concerns inward at
four points:

- **Contracts is a "god leaf."** `runtime-contracts` — the most-depended-on
  module, depended on even by the pure `runtime-domain` — contains three
  networknt + Jackson JSON-Schema validators that read schema YAML off disk
  (`InstallPlanSchemaValidator`, `WorkflowStateSchemaValidator`/
  `CanonicalWorkflowStateSchemaValidator`, `DecompositionManifestSchemaValidator`)
  plus a cross-field business-rule validator
  (`DecompositionManifestCoherenceValidator`). `RuntimeArchitectureTest`
  (`runtime schema validators and schema resources are owned by runtime
  contracts`) locks them there.

- **Domain policy invokes a filesystem/Jackson/networknt validator at runtime.**
  `runtime-domain/.../install/policy/InstallPlanPolicy.kt:76` calls
  `validateInstallPlanWireSnapshot(plan)`
  (`install/model/InstallPlanWireMap.kt:70`), which calls
  `InstallPlanSchemaValidator.validate(...)` — Jackson `YAMLMapper` + networknt +
  `java.nio.file.Files` schema discovery. The structurally identical workflow
  path was deliberately inverted behind the domain-owned
  `WorkflowSnapshotValidator` port; the install path was not.
  `InstallPolicyOwnershipArchitectureTest` (lines 87, 121) currently *asserts*
  the domain policy makes this call, so the debt is locked in by a test.
  The ban in `RuntimeArchitectureTest` (`runtime domain workflow source must not
  import contract schema validators or contract mappers`, line 433) is scoped to
  `skillbill/workflow/` and does not cover `skillbill/install/`.

- **Ports leak wire shapes; "typed boundary" is partly fictional.** The eight
  `Scaffold*Result` port DTOs each carry a typed-scalar veneer plus a
  `payload: Map<String, Any?>` that is the real wire contract, guarded by
  `init {}` desync `require(...)` checks. The raw-map allow-list carries ~60
  entries, several documented as "Subtask 3 will remove" / "Subtask 4 will
  remove" (system service, lifecycle telemetry payloads) that still persist —
  temporary debt that has hardened into permanent architecture.

- **Application links Jackson directly for one wire seam.**
  `runtime-application/.../DecompositionManifestFileWrites.kt:43` calls
  `YAMLMapper().writeValueAsString(...)`; it is the only consumer of
  `runtime-application`'s `implementation(jackson-dataformat-yaml)` edge. The
  inverse (parsing) already lives in `runtime-contracts`.

Secondary issues:

- **Domain effect impurity.** `TelemetryConfigRules.kt:9` calls
  `UUID.randomUUID()`; `TelemetryRemoteStatsRuntime.kt:14,22` default args read
  the system clock (`LocalDate.now(...)`); `SkillRemove.kt` uses
  `java.util.logging.Logger` directly.
- **Enforcement-mechanism blind spots.** Package-boundary checks
  (`assertNoBannedImports`) match parsed `import` statements only and can be
  bypassed by fully-qualified inline references; the central scanner's
  `sourceRoots` include `runtime-desktop/core/data/src/commonMain` but **not**
  `.../jvmMain` (where the desktop runtime imports actually live); the
  install-validator leak survives in the two-test gap between the workflow-only
  ban and the install-model scan.
- **External schema source-of-truth coupling.** Six schema YAMLs live outside
  the Gradle project at `../orchestration/contracts/` and are copied in at build
  time by three modules (`runtime-contracts`, `runtime-infra-fs`, `runtime-mcp`)
  via `rootProject.projectDir.parentFile.resolve("orchestration/contracts/...")`.
  The coupling is undocumented in `agent/decisions.md`.

## Goals

1. Relocate the schema/coherence validators out of `runtime-contracts` and
   behind ports, so the foundational leaf holds DTOs, version constants, schema
   path constants, and the exception taxonomy only.
2. Invert the install-plan and decomposition-manifest validation behind
   domain-neutral ports (mirroring `WorkflowSnapshotValidator`) so neither
   `runtime-domain` nor `runtime-application` imports a concrete networknt /
   Jackson / filesystem validator directly.
3. Type the eight `Scaffold*Result` port DTOs and remove their raw-map
   `payload` fields, deleting the `init {}` desync guards and the eight
   allow-list rows.
4. Move decomposition-manifest YAML serialization out of `runtime-application`
   so the application module no longer declares a direct Jackson dependency.
5. Reconcile the raw-map allow-list with reality: type the entries marked for
   removal by SKILL-52.1 subtasks 3/4 (system service, lifecycle telemetry), or
   relabel them as accepted permanent boundaries with a recorded rationale.
6. Remove non-deterministic effects and JVM logging from `runtime-domain` by
   injecting a clock and id source and routing skill-remove logging through a
   port or removing it.
7. Harden the architecture-test enforcement so the closed leaks cannot recur:
   source-text scanning for package-boundary rules, desktop `jvmMain`/`jvmTest`
   source roots in the central scanner, and an install/decomposition
   validator-import ban.
8. Record the external-schema source-of-truth coupling as an explicit decision
   (or internalize the schemas), tested for parity.
9. Preserve all existing CLI, MCP, desktop, install, scaffold, review,
   telemetry, workflow, validation, and persisted-data behavior, byte-for-byte
   where golden tests exist.

## Non-Goals

- Do not change CLI commands, MCP tool names, desktop UX, telemetry event
  names, install prompts, persisted database schema, scaffold payload semantics,
  or any wire/JSON output shape.
- Do not remove intentionally open maps: `PlatformManifest.customFields`,
  caller-supplied workflow artifacts (`WorkflowSnapshotView.artifacts`), schema
  custom fields, MCP argument maps before parsing, or private serializer maps
  internal to contract emission.
- Do not generate Kotlin types from JSON Schemas.
- Do not weaken loud-fail typed errors or relax schema validation to ease
  migration. Every validation seam that loud-fails today must still loud-fail
  with the same typed error.
- Do not split new Gradle modules unless a subtask proves a smaller boundary
  cannot close the gap. (Validators move into the existing
  `runtime-infra-fs`/`runtime-infra-http` modules alongside the
  `PlatformPackSchemaValidator` and `NativeAgentCompositionSchemaValidator` that
  already live there.)
- Do not change which library performs JSON-Schema validation (networknt) or
  YAML parsing (Jackson); only move where it lives and how it is reached.

## Target Architecture

The module graph and dependency direction are unchanged:

```text
runtime-cli / runtime-mcp / runtime-desktop data gateways
  -> runtime-application use cases
    -> runtime-ports
      -> runtime-domain models and rules
      -> runtime-contracts DTOs, version + schema-path constants, exceptions

runtime-infra-fs / runtime-infra-http / runtime-infra-sqlite
  -> implement runtime-ports (own schema validation, parsing, and all effects)

runtime-core
  -> DI composition and runtime metadata only
```

SKILL-52.3 tightens the interpretation:

- `runtime-contracts` is a pure leaf: DTOs, `*_CONTRACT_VERSION` constants,
  `*SchemaPaths` constants, `JsonSupport`/`WorkflowContracts` ordered-map
  helpers, and the `skillbill.error` taxonomy. **No JSON-Schema validator,
  Jackson `ObjectMapper`/`YAMLMapper`, networknt type, or `java.nio.file.Files`
  call may live in `runtime-contracts`.**
- Schema/coherence validation is a port capability. Validation interfaces live
  in `runtime-domain` or `runtime-ports`; concrete networknt/Jackson
  implementations live in `runtime-infra-fs`; composition wires them in
  `runtime-core`.
- `runtime-domain` is effect-free: no random id generation, no system-clock
  reads, no JVM logging, no validator/parse/IO calls. `Path` stays legal as
  inert data per the existing decisions.md carve-out.
- Port result DTOs are fully typed. A raw `Map<String, Any?>` survives on a
  public application/domain/port surface only when it is a genuinely open
  extension/artifact boundary with a recorded rationale.

## Acceptance Criteria

1. `runtime-contracts` main source contains no import of `com.networknt.*`,
   `com.fasterxml.jackson.*`, or `java.nio.file.Files`, and no JSON-Schema
   validator, `ObjectMapper`, or `YAMLMapper` usage. `runtime-contracts`
   `build.gradle.kts` no longer declares `json.schema.validator`,
   `jackson.databind`, or `jackson.dataformat.yaml` (except where still needed
   strictly for `kotlinx.serialization`-free DTO support, which is none).
2. No `runtime-domain` or `runtime-application` main-source file imports a
   concrete schema validator (`*SchemaValidator`) or coherence validator.
   Install-plan, workflow-state, and decomposition-manifest validation are
   reached only through domain-neutral ports wired by `runtime-application` /
   `runtime-core`.
3. Install-plan and decomposition-manifest validation loud-fail with the
   existing typed errors (`InvalidInstallPlanSchemaError`,
   `InvalidDecompositionManifestSchemaError`, and the coherence-check errors) at
   the same effective coverage as today. `InstallPolicyOwnershipArchitectureTest`
   is updated to assert the port-based seam instead of the direct domain call.
4. The eight `Scaffold*Result` port DTOs expose typed fields only; the
   `payload: Map<String, Any?>` fields and their `init {}` desync `require`
   guards are removed, and the eight `skillbill.ports.scaffold.*Result.payload`
   rows are removed from `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` and `ARCHITECTURE.md`.
   CLI/MCP/desktop output remains byte-equivalent where golden tests exist.
5. `runtime-application` `build.gradle.kts` no longer declares
   `jackson.dataformat.yaml`; decomposition-manifest YAML serialization is owned
   by a contract/infra serializer or a port, and
   `DecompositionManifestArchitectureTest` is updated to assert the new seam.
6. Every raw-map allow-list entry previously labeled "Subtask 3 will remove"
   (system service / install policy) or "Subtask 4 will remove" (lifecycle
   telemetry payloads) is either typed and removed from the allow-list, or
   relabeled in `ARCHITECTURE.md` as an accepted permanent open boundary with a
   one-line rationale and a matching `agent/decisions.md` entry. No allow-list
   entry carries a stale future-tense removal promise.
7. `runtime-domain` main source contains no `UUID.randomUUID()`, no
   `LocalDate.now(...)`/`Instant.now()`/other system-clock read, and no
   `java.util.logging` usage. Random ids and current time are injected as
   data/suppliers; skill-remove logging is routed through a port or removed.
8. Architecture-test enforcement is hardened: (a) package-boundary checks for
   `runtime-domain`/`runtime-ports`/`runtime-application` scan source text in
   addition to parsed imports so fully-qualified inline references are caught;
   (b) the central scanner `sourceRoots` include
   `runtime-desktop/core/data/src/jvmMain/kotlin`; (c) a test bans
   `*SchemaValidator` imports from `runtime-domain/.../skillbill/install/` and
   `.../skillbill/workflow/` and from `runtime-application` main source.
9. The external schema source-of-truth coupling (`../orchestration/contracts/`
   copied by three modules) is documented in `agent/decisions.md` with the
   parity guarantee, or the schemas are internalized; either way a parity test
   covers contract-version constants against the schema files.
10. `runtime-kotlin/ARCHITECTURE.md`, `RuntimeModule`, `agent/decisions.md`,
    `agent/history.md`, and the architecture tests agree on the final rules and
    the reduced raw-map allow-list.
11. Full validation passes:
    - `(cd runtime-kotlin && ./gradlew check)`
    - `skill-bill validate`
    - `scripts/validate_agent_configs`
    - `npx --yes agnix --strict .`

## Validation Strategy

Run focused checks after each subtask:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
```

Run full validation at the end:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

## Recommended Next Prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.3-runtime-hexagon-leak-closure/spec.md
```
