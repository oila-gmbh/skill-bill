# SKILL-48 Subtask 2c — `native-agent-composition-schema.yaml`

Status: Complete

Parent spec: [spec_subtask_2_runtime-contracts.md](./spec_subtask_2_runtime-contracts.md)
Grandparent: [spec.md](./spec.md)

## Why this is subtask 2c

Subtasks 2a and 2b landed the pattern for two additional contracts and the desktop auto-listing. Native-agent composition is structurally bounded (composition envelope + `compose` references) and has existing on-disk fixtures, making it the next-lowest-risk schema to land before the largest one (telemetry events, subtask 2d).

## Scope

1. Author `orchestration/contracts/native-agent-composition-schema.yaml` as a Draft 2020-12 JSON Schema in YAML, mirroring the established template.
2. Schema covers the shape `NativeAgentComposition` / `NativeAgentBundle` produce (runtime-core/nativeagent, 95+174 lines): the YAML envelope with `name`, `description`, `compose`, `body`, plus any other required/optional fields the runtime emits today.
3. Pin `contract_version` to a Kotlin `NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION` constant (in `runtime-kotlin/runtime-core/nativeagent`) via a parity test.
4. Wire `NativeAgentCompositionSchemaValidator`. Gradle `Copy` task is configuration-cache friendly (F-101).
5. Validate at the parse seams `parseNativeAgentBundle(path)` and `parseNativeAgentSourceFile` in runtime-core. Loud-fail via new typed `InvalidNativeAgentCompositionSchemaError` carrying the field path.
6. Tests:
   - `NativeAgentCompositionSchemaContractVersionTest` — parity with constant.
   - `NativeAgentCompositionSchemaValidatesExistingBundlesTest` — every existing native-agent fixture under `skills/**/native-agents/` and `platform-packs/**/native-agents/` validates clean. Discover fixtures dynamically (do not hard-code paths).
   - `NativeAgentCompositionSchemaViolationsTest` — per-violation tests: missing required `name`, unknown `compose` reference shape, unknown property, wrong `contract_version`, malformed body section.
   - Classpath shadow guard test extended to assert this YAML is not shadowed.
7. No desktop or AGENTS.md edits required — both already handled in 2a.

## Acceptance criteria

1. `orchestration/contracts/native-agent-composition-schema.yaml` exists, declares Draft 2020-12, has `contract_version` const, `additionalProperties: false`, documented `x-coherence-checks`, and matches the established template.
2. `NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION` Kotlin constant pins the schema's `contract_version`; parity test fails the build if they diverge.
3. Every native-agent fixture under `skills/**/native-agents/` and `platform-packs/**/native-agents/` validates clean against the schema.
4. `parseNativeAgentBundle(path)` and `parseNativeAgentSourceFile` validate against the schema and loud-fail via `InvalidNativeAgentCompositionSchemaError`.
5. Per-violation tests cover: missing required `name`, unknown `compose` reference shape, unknown property, wrong contract_version, malformed body.
6. Build gradle `Copy` task is configuration-cache friendly.
7. The new YAML appears in the desktop "Contracts" tree automatically via 2a's auto-listing.
8. `bill-quality-check` passes.

## Non-goals

- Authoring the other three schemas (subtasks 2a/2b/2d).
- Touching desktop code or `AGENTS.md` (already landed in 2a).
- Replacing `PlatformManifest`, generating Kotlin types, multi-version validators, `x-runtime-anchored` (Subtask 3).
- Renaming/restructuring composition fields — schema describes what is emitted today.

## Dependencies

- Subtask 1 (SKILL-47 cleanup): C2/C7/C8.
- Subtasks 2a + 2b: established pattern (validator class shape, parity test shape, validates-existing-emissions test shape, configuration-cache-friendly `Copy`).

## Validation strategy

`bill-quality-check` (runtime-kotlin Gradle `check`).

## Boundaries touched

- `orchestration/contracts/native-agent-composition-schema.yaml` (new).
- `runtime-kotlin/runtime-core/nativeagent` — validator + parse-seam wiring; constant.
- `runtime-kotlin/runtime-contracts/error` — new `InvalidNativeAgentCompositionSchemaError`.
- Gradle build file (configuration-cache friendly `Copy`).
- `runtime-kotlin/agent/history.md` — high-signal entry.

## Templates to cite verbatim

- Schema YAML structure: `orchestration/contracts/platform-pack-schema.yaml` + the two schemas landed in 2a/2b.
- Validator class: `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/PlatformPackSchemaValidator.kt`.
- Parity test: `PlatformPackSchemaContractVersionTest`.
- Validates-existing test: `PlatformPackSchemaValidatesExistingPacksTest`.
- Violations test: `PlatformPackSchemaViolationsTest`.
- Native-agent fixtures: `skills/**/native-agents/`, `platform-packs/**/native-agents/`.
- Test helper: `runtime-kotlin/runtime-core/src/testFixtures/kotlin/skillbill/testing/RepoRoot.kt`.

## Recommended next prompt

`Run bill-feature-implement on .feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2c_native-agent-composition.md`
