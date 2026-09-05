# SKILL-233 · Subtask 2: Ports evacuation and domain purity

## Scope

Make `runtime-ports` the thinnest module in the graph and `runtime-domain` a
module that compiles against the Kotlin standard library alone.

**Evacuate behaviour from `runtime-ports`.** The module is 10,571 lines, and
7,031 of them are in files declaring no interface. Every such file is one of
three things and moves accordingly:

- **Domain logic** moves to `runtime-domain` under the matching area:
  `GoalContinuationArtifactCodec`, `GoalWorkerSubtaskRequestArtifactCodec`,
  `GoalTerminalOutcomeDerivation`, `GoalContinuationOutcomeSelection`,
  `AttemptLedgerAccumulator`, `AttemptLedgerDecoding`,
  `AttemptLedgerWorkflowDecoding`, `AttemptLedgerProgressEvents`,
  `GoalSubtaskReviewSummaryReducer`, `GoalSubtaskReviewStructuredFindingsParse`,
  `GoalSubtaskReviewOutcomeDispositionReduction`,
  `GoalSubtaskReviewVerificationRejection`, `GoalObservabilityArtifacts`,
  `DecompositionManifestRuntimeState`, `DecompositionManifestRuntimeStateSupport`,
  `DecompositionWorkflowRuntimeLookup`,
  `DecompositionWorkflowRuntimeLookupParentDiscovery`,
  `FeatureTaskRuntimePhaseArtifactDecoders`. Where a same-named file already
  exists in `runtime-application` (`AttemptLedgerAccumulator`,
  `AttemptLedgerDecoding`, `AttemptLedgerProgressEvents`,
  `AttemptLedgerWorkflowDecoding`, `DecompositionManifestRuntimeStateSupport`,
  `LegacyGoalRunnerControlMigration`), diff the pair, keep one, and delete the
  other. The 51 duplicated basenames across modules are reduced to the ones
  that are genuinely different types, and the report lists the deletions.
- **Adapter logic** moves to the infrastructure module that owns the effect:
  `DecompositionManifestFileWrites` (calls `Files.*`) to `runtime-infra-fs`;
  `LegacyGoalRunnerControlMigration` to `runtime-infra-sqlite` beside the
  schema it migrates; `WorkflowGoalRunnerStoreDepsModels` (the only `@Inject`
  in the module) to `runtime-infra-sqlite`.
- **DTO helpers** that are pure functions over a port DTO
  (`GoalRunnerProgressEvent.summary()`, `toProgressEvent()`) may stay beside
  the DTO. The report names each one kept and why it is not domain logic.

After the move, `runtime-ports` imports nothing from `kotlinx.serialization`,
`JsonSupport`, `java.nio.file.Files`, or `me.tatarka.inject`. The remaining
`GoalRunnerManifest*` sub-interfaces whose sole implementation and consumer are
both in `runtime-infra-sqlite` (`GoalRunnerManifestLookup`, `PauseOps`,
`ExecutionLease`, `ControlCommands`, `PersistenceCommands`, `ReviewCommands`)
move there as `internal` seams; `GoalRunnerManifestStore` stays as the port.

**Move every null object off the production classpath.** SKILL-231 classified
the 34 `Noop*` / `Unavailable*` / `Empty*` objects as total refusal, recording
null object, delegation composite, or diagnostic sink, and every one still
ships in main source: 23 in `runtime-ports`, 6 in `runtime-domain`, 1 in
`runtime-application`, plus the `RecordingNullObjectDiagnostics` global in
`runtime-contracts`. Resolve each classification to its end state:

- A **recording null object** is a test substitute. It moves to the owning
  module's `testFixtures` and drops its `RecordingNullObjectDiagnostics` call.
  `NoopWorkflowGitBranchOperations.checkoutBranch` returning `status = "ok"`
  without running git is the shape this removes from production.
- A **total refusal** that a production path genuinely reaches is replaced by
  an explicit model of absence: a nullable port on the consumer, or a sealed
  `Availability` type bound at the composition root. A total refusal that no
  production path reaches moves to `testFixtures`.
- `UnitOfWork` loses its three default getters (`unaddressedFindings`,
  `featureTaskRuntimeAuditGenerations`, `agentActivityStamps`) and its two
  nullable members become abstract; every implementation declares every
  member.
- `RecordingNullObjectDiagnostics` is deleted. Its 22 main-source references
  go with the objects that made them.
- `PortNullObjectClassification` in the architecture tests becomes an assertion
  that no such object exists in any main source set, with a rejection fixture.

**Strip JVM and serialization from `runtime-domain`.** Fifteen domain files
import `java.nio.file.Path` and six import kotlinx serialization; the module
also inherits kotlinx serialization through `runtime-contracts`'
`api(libs.kotlinx.serialization.json)`.

- `Path` in a domain model is a repository-relative or install-relative
  location. Introduce a value type for it (`RepositoryRelativePath` or the
  area's equivalent) in `runtime-domain`, convert at the port boundary, and
  remove the import. Where a domain file does real path arithmetic
  (`ScaffoldModels`, `TargetValidationPaths`), that arithmetic moves to the
  infrastructure adapter or is expressed on the value type without `java.nio`.
- JSON codecs in domain (`WorkflowEngineSnapshotCodec`,
  `WorkflowEngineSnapshotCodecDurable`, `WorkflowInputProjectionSelector`, the
  `JsonSupport` callers) move to `runtime-contracts` or the adapter that reads
  and writes the encoded form. `WorkflowEngine` operates on typed snapshot
  fields; the `linkedMapOf("workflow_id" to ...)` projection becomes a contract
  mapper outside the domain.
- `runtime-contracts` changes `api(libs.kotlinx.serialization.json)` to
  `implementation`. Any consumer that breaks was reaching kotlinx types through
  the wrong module and declares its own dependency.
- The `ArchitectureScanSupport` import-rule scanner gains a `runtime-domain`
  rule forbidding `java.nio`, `java.io`, `kotlinx.serialization`,
  `com.fasterxml`, and `org.yaml`, with an empty baseline.

## Acceptance Criteria

1. Every `runtime-ports` main-source file declares at least one interface, or
   contains only DTO declarations and pure extension functions over DTOs
   declared in the same package. The subtask report states the new
   non-interface line count against the starting 7,031.
2. `runtime-ports` main source has zero imports of `kotlinx.serialization`,
   `skillbill.contracts.JsonSupport`, `java.nio.file.Files`, and
   `me.tatarka.inject`.
3. `GoalRunnerManifestLookup`, `GoalRunnerManifestPauseOps`,
   `GoalRunnerManifestExecutionLease`, `GoalRunnerManifestControlCommands`,
   `GoalRunnerManifestPersistenceCommands`, and
   `GoalRunnerManifestReviewCommands` live in `runtime-infra-sqlite` as
   `internal`. `GoalRunnerManifestStore` remains the port.
4. Of the 51 duplicated basenames, every ports-versus-application or
   ports-versus-domain pair is resolved to one file. The report lists the
   deleted files and states the remaining duplicate count with the reason each
   remaining pair is two distinct types.
5. No `Noop*`, `Unavailable*`, `Empty*`, or `Unconfigured*` port object exists
   in any module's `src/main`. `PortNullObjectClassification` asserts that
   absence and has a rejection fixture.
6. `RecordingNullObjectDiagnostics` does not exist.
7. Every `UnitOfWork` member is abstract, and every `UnitOfWork` implementation
   in main and test source declares every member.
8. Each production path that previously reached a total-refusal object now
   either receives a real implementation, a nullable port, or a sealed
   availability value bound in `runtime-core`. Each such site has a test that
   drives the absent case and asserts the loud failure or the explicit absent
   branch. The report lists the sites.
9. `runtime-domain` main source imports nothing under `java.nio`, `java.io`,
   `kotlinx.serialization`, `com.fasterxml`, or `org.yaml`. An import-rule
   scanner enforces it with an empty baseline and a rejection fixture.
10. `runtime-contracts/build.gradle.kts` declares kotlinx serialization as
    `implementation`, and every module that uses kotlinx types declares its own
    dependency.
11. `WorkflowEngine` builds no `Map<String, Any?>` snapshot. The wire projection
    is a mapper in `runtime-contracts` or the SQLite adapter, and the workflow
    snapshot validator receives a typed snapshot or the mapper's output at the
    adapter boundary.
12. Behaviour is unchanged except where criterion 8 names it. A test whose
    assertion changed exposed a behaviour difference and is reported, not
    patched.
13. `runtime-kotlin/gradlew check` and `skill-bill validate` pass with no new
    suppression, no new exemption, and no baseline entry.

## Non-Goals

- Introducing value-class identifiers or closing `status: String` fields.
  Subtask 3.
- Touching `runtime-application`'s run loop or `runtime-core`'s DI shape.
  Subtask 1.
- Retiring inventoried `@OpenBoundaryMap` payloads. The 342 raw-map
  occurrences in domain shrink only where criterion 11 reaches; the inventoried
  open boundaries stay.
- Renaming the `*GitOperations` family.
- Changing SQLite schema or migrations. `LegacyGoalRunnerControlMigration`
  moves; it does not change.
- Re-homing `skillbill.contracts.*` validator packages out of
  `runtime-infra-fs`. Subtask 4 owns package roots.

## Dependency Notes

No dependency on another subtask. Subtask 1 may run in parallel; the two touch
disjoint modules except `PrincipleEnforcementInventory`, `ARCHITECTURE.md`, and
`runtime-kotlin/agent/decisions.md`, which merge textually.

Subtask 3 depends on this one: the value types it introduces live in a domain
module that no longer carries JVM imports, and the port signatures it retypes
are the ones this subtask leaves as pure interfaces.

Subtask 4 depends on this one: `runtime-engine` must not inherit a `runtime-ports`
that still carries behaviour.

## Validation Strategy

- The ports import rule and the domain import rule are set-equality scanners
  against empty baselines; each has a synthetic rejection fixture. The bug they
  catch is a codec or a filesystem call creeping back into a contract module.
- Null-object removal is validated by `PortNullObjectClassification` asserting
  zero, and by the per-site tests in criterion 8. The bug those tests catch is
  a git operation or a repository write that reports success without doing
  anything.
- `UnitOfWork` abstractness is validated by the compiler: every implementation
  must declare every member.
- Domain purity is validated by `gradlew :runtime-domain:compileKotlin` with
  only `runtime-contracts` on the classpath and by the import scanner.
- Codec relocation is validated by the existing workflow snapshot and
  decomposition manifest round-trip tests passing unchanged in assertion.
- `runtime-kotlin/gradlew check` in a clean checkout.

## Next Path

```bash
skill-bill goal SKILL-233
```
