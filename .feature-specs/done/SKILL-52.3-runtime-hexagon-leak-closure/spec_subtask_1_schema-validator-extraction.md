# SKILL-52.3 Subtask 1 - Schema Validator Extraction + Install/Decomposition Validator Ports

Parent spec: [.feature-specs/SKILL-52.3-runtime-hexagon-leak-closure/spec.md](./spec.md)
Issue key: SKILL-52.3
Subtask order: 1 of 5
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Stop the foundational `runtime-contracts` leaf from owning infrastructure-grade
schema validation, and stop `runtime-domain` policy code from invoking a
networknt + Jackson + filesystem validator at runtime. Generalize the existing
`WorkflowSnapshotValidator` port pattern to install-plan and decomposition
validation. This is the structural core of the feature; all other subtasks build
on the seams it introduces.

## Scope

In scope:

- Introduce domain-neutral validator ports mirroring
  `skillbill.workflow.WorkflowSnapshotValidator`:
  - `InstallPlanWireValidator` — validates the install-plan wire snapshot,
    loud-fails with `InvalidInstallPlanSchemaError`.
  - `DecompositionManifestValidator` — validates decomposition-manifest maps /
    YAML text and runs coherence checks, loud-fails with
    `InvalidDecompositionManifestSchemaError` and the existing coherence errors.
  - Place each port interface where `WorkflowSnapshotValidator` lives today
    (domain-owned port consumed by domain logic; or `runtime-ports` if the
    consumer is application-only). Match the predecessor's placement decision.
- Move the concrete validator implementations out of `runtime-contracts` into
  `runtime-infra-fs`, alongside the existing `PlatformPackSchemaValidator` and
  `NativeAgentCompositionSchemaValidator`:
  - `InstallPlanSchemaValidator`
  - `WorkflowStateSchemaValidator` / `CanonicalWorkflowStateSchemaValidator`
  - `DecompositionManifestSchemaValidator`
  - `DecompositionManifestCoherenceValidator`
  - Keep `*SchemaPaths` constants and `*_CONTRACT_VERSION` constants in
    `runtime-contracts` (they are pure constants and are referenced widely).
- Provide an application-side adapter for each port mirroring
  `WorkflowSnapshotValidatorAdapter` (the predecessor already does this for
  workflow state — reuse or relocate it consistently so all three validators are
  reached the same way).
- Wire the validator ports in `runtime-core` composition
  (`skillbill/di/RuntimeComponent.kt`) using `@Provides @JvmSynthetic internal`
  bindings, exactly as other infra adapters are wired.
- Remove `validateInstallPlanWireSnapshot`'s direct call to
  `InstallPlanSchemaValidator` from `runtime-domain`
  (`install/model/InstallPlanWireMap.kt`, `install/policy/InstallPlanPolicy.kt`).
  Relocate the validation call to the application/infra seam(s) that already
  emit the plan (`runtime-infra-fs` `InstallPlanBuilder`, `runtime-cli`
  `InstallCliPayloads`), or pass the injected `InstallPlanWireValidator` into
  the policy through its existing construction path. Preserve dual-seam coverage
  semantics.
- Remove the direct `DecompositionManifestSchemaValidator.validateYamlText`
  call from `runtime-application`
  (`DecompositionManifestFileWrites.kt`) in favor of the injected
  `DecompositionManifestValidator` port. (YAML *serialization* relocation is
  subtask 4; this subtask only inverts the *validation* call.)
- Move the `runtime-contracts` build-file schema-copy tasks
  (`copyWorkflowStateSchema`, `copyInstallPlanSchema`,
  `copyDecompositionManifestSchema`) to `runtime-infra-fs` so the schema
  resources ship on the classpath of the module that now reads them. Keep the
  canonical YAML source path (`../orchestration/contracts/...`) unchanged
  (internalizing the source is subtask 5's decision).
- Remove `json.schema.validator`, `jackson.databind`, and
  `jackson.dataformat.yaml` from `runtime-contracts/build.gradle.kts`. Add them
  to `runtime-infra-fs/build.gradle.kts` if not already present.
- Update `runtime-core/.../RuntimeComponent` ABI guards if a new port accessor
  is required; keep the public ABI closure limited per
  `RuntimeCoreCompositionOnlyTest` and `RuntimeImplementationImportRules`.

Out of scope:

- Changing schema files, contract versions, or validation rules.
- Changing the library choice (networknt / Jackson stay).
- YAML serialization relocation (subtask 4).
- Domain effect purity — UUID/clock/logging (subtask 2).
- Internalizing the schema source-of-truth (subtask 5 decision).

## Acceptance Criteria

1. `runtime-contracts` main source contains no `*SchemaValidator`,
   `*CoherenceValidator`, `com.networknt.*`, `com.fasterxml.jackson.*`, or
   `java.nio.file.Files`. `runtime-contracts/build.gradle.kts` no longer
   declares the networknt or Jackson dependencies.
2. The three schema validators and the coherence validator live in
   `runtime-infra-fs` and are reached only through ports.
3. `runtime-domain` (`install/model/InstallPlanWireMap.kt`,
   `install/policy/InstallPlanPolicy.kt`, and all `skillbill/install/` /
   `skillbill/workflow/` source) imports no concrete schema validator.
4. `runtime-application` imports no concrete schema/coherence validator;
   decomposition validation flows through `DecompositionManifestValidator`.
5. Install-plan and decomposition validation loud-fail with the existing typed
   errors at equivalent coverage. A test proves malformed install-plan and
   malformed decomposition inputs still throw the same exceptions.
6. The `runtime schema validators and schema resources are owned by runtime
   contracts` test is replaced/updated to assert validator ownership now lives
   in `runtime-infra-fs`, and that the legacy contracts validator files are
   absent.
7. `RuntimeComponent` wires the validator ports with
   `@Provides @JvmSynthetic internal`; `RuntimeCoreCompositionOnlyTest` and the
   public-ABI closure tests still pass.
8. Focused install, workflow, decomposition, and architecture tests pass.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-domain:test --tests '*Install*')
(cd runtime-kotlin && ./gradlew :runtime-domain:test --tests '*Decomposition*')
(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test)
(cd runtime-kotlin && ./gradlew :runtime-application:test)
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Install*')
```

## Implementation Notes

- The reference design is already in the tree for workflow state:
  `runtime-domain/.../skillbill/workflow/WorkflowSnapshotValidator.kt` (port) +
  `runtime-application/.../WorkflowSnapshotValidatorAdapter.kt` (impl that
  constructs `CanonicalWorkflowStateSchemaValidator`). After this subtask the
  adapter must construct an impl that lives in `runtime-infra-fs`, not
  `runtime-contracts`. Keep all three validators reached the same way so the
  pattern is uniform.
- This subtask supersedes two prior decisions; record the reversal in
  `agent/decisions.md` (final wording is subtask 5, but note it here so the
  implementer expects test churn):
  - 2026-05-24 "Preserve dual install-plan validation after policy extraction" —
    dual-seam coverage is preserved, but neither seam may be inside
    `runtime-domain`.
  - 2026-05-18 "Platform-pack manifest validation moves to a canonical JSON
    Schema" added the validator deps to `runtime-core`; they later moved to
    `runtime-contracts`. This subtask moves all schema validators to
    `runtime-infra-fs`, the module that already owns `PlatformPackSchemaValidator`.
- `InstallPolicyOwnershipArchitectureTest` (lines ~87, ~121) asserts the domain
  policy calls `validateInstallPlanWireSnapshot`. It must be updated to assert
  the policy delegates to the injected `InstallPlanWireValidator` port and that
  the domain no longer imports the concrete validator.
- Keep `runtime-mcp`'s `TelemetryEventSchemaValidator` out of scope here; it is
  a driver-adapter wire-input validator (assessed Minor, defensible). If desired
  it can be inverted behind a port in a later pass.
- Watch the `runtime-domain` runtime classpath: after extraction, the domain's
  runtime closure should no longer pull networknt/Jackson transitively, since
  it no longer calls the validators. Confirm with
  `./gradlew :runtime-domain:dependencies --configuration runtimeClasspath`.
