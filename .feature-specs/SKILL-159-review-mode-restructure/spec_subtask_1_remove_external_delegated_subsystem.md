# SKILL-159 Subtask 1 — Remove the external-process delegated review subsystem

## Scope

Delete every runtime capability to launch, supervise, persist, or aggregate
external provider CLI processes as code-review workers. The existing mode
vocabulary (`inline` = fan-out default) is untouched in this subtask; after
this subtask, an explicit `delegated` selection must fail loudly as
unavailable rather than launch anything.

Wholly-delegated sources to delete (with their tests):

- `runtime-application/.../review/DelegatedReviewExecutionBroker.kt`, `DelegatedReviewLaunchBroker.kt`, `DelegatedReviewWorkerLauncher.kt`, `model/DelegatedReviewWorkerModels.kt`, `ReviewLifecycleRecovery.kt`, `GoalRunnerNativeReviewWorkerAdapter.kt`
- `runtime-domain/.../review/DelegatedReviewAggregationGate.kt`, `model/DelegatedReviewAggregationModels.kt`, `plan/DelegatedReviewCapacityPlanner.kt`, `plan/model/DelegatedReviewPlanModels.kt`
- `runtime-ports/.../review/model/DelegatedReviewLifecycleModels.kt`, `NativeReviewWorkerLauncher.kt`, `model/NativeReviewWorkerRequest.kt`
- `runtime-infra-fs/.../agentrun/DelegatedReviewProviderCapabilityRegistry.kt`, `ProviderLifecycleSignals.kt`
- `runtime-infra-sqlite/.../review/ReviewLifecycleSnapshotCodec.kt`, `contracts/review/ReviewLifecycleSchemaValidator.kt`
- `runtime-contracts/.../review/ReviewLifecycleSchemaPaths.kt`

Shared files to prune of delegated-lane logic: `ParallelCodeReviewRunner.kt`
(lane dispatch, wave execution, lifecycle recording), `ReviewLifecycleRecorder.kt`,
`ReviewAccountingProjection.kt`, `ParallelReviewPreparationCompiler.kt`,
`AgentRunCommandBuilders.kt` (`NativeReviewLifecycleCallbacks` and the
Claude/Codex/Cursor implementations, `delegatedReviewCapability`),
`AgentRunAdapters.kt`, `FileSystemAgentRunLauncher.kt` (unsupported-provider
refusal for review workers, `DelegatedReviewLivenessSignals` watchdog),
`JvmAgentRunProcessRunner.kt` heartbeat plumbing, `ReviewPersistenceSupport.kt`
lifecycle save/load, `ReviewRepository.kt` (`ReviewLifecycleRepository`),
`ReviewEvidenceBroker.kt`/`ReviewEvidenceModels.kt` delegated envelopes,
`RuntimeComponent.kt` DI exposure, `SQLiteRepositories.kt` wiring.

Contracts: delete `orchestration/contracts/review-lifecycle-schema.yaml`, its
validator, version constant, parity test
(`ReviewLifecycleSchemaContractVersionTest`), and the Gradle resource copy in
`runtime-infra-sqlite/build.gradle.kts`. Remove or re-scope
`review-lifecycle-evidence-schema.yaml` and its constant/parity test to
surviving inline-evidence surfaces only; whichever is chosen, the schema,
constant, validator, and parity test must stay mutually consistent.

Database: keep applied migration bodies untouched (append-only history). Stop
all reads/writes of `review_lifecycle_events` and `review_delegated_lifecycle`;
add a new forward migration dropping those tables and indices so fresh and
existing databases converge without startup failures.

## Acceptance Criteria

1. All wholly-delegated Kotlin sources listed in scope, and their dedicated tests (`DelegatedReviewProviderCapabilityRegistryTest`, `DelegatedReviewAggregationGateTest`, `DelegatedReviewCapacityPlannerTest`, `ReviewLifecycleRecoveryTest`, `ReviewLifecyclePersistenceTest`, `ReviewLifecycleSchemaContractVersionTest`), are deleted.
2. No main-source Kotlin file contains a code path that launches or supervises an external provider CLI process as a review worker; `ParallelCodeReviewRunner` contains no delegated lane dispatch, wave execution, or lifecycle recording.
3. An explicit `code-review:delegated` / `--code-review-mode delegated` selection fails loudly with a typed, actionable error stating external delegated review was removed; nothing silently falls back to inline.
4. `review-lifecycle-schema.yaml` is gone from `orchestration/contracts/` with no dangling validator, version constant, parity test, or Gradle copy task; the review-lifecycle-evidence contract is deleted or re-scoped with its constant and parity test consistent.
5. Existing migration bodies are unmodified; a new migration drops `review_lifecycle_events` and `review_delegated_lifecycle`; an existing database and a fresh database both start cleanly.
6. `parallel-review:<agent>` second-lane review still functions on the surviving fan-out path.
7. `(cd runtime-kotlin && ./gradlew check)` passes; `RuntimeArchitectureTest`, `ApplicationPersistencePortTest`, and `ReviewAccountingDurableRedactionTest` pass against the pruned graph.

## Non-Goals

- Renaming modes, changing the default, or changing `auto` resolution (subtask 2).
- Any governed content, docs, or pack changes beyond what compilation of removed symbols forces (subtask 3).
- Removing `ReviewFailureMatrixTest` or `docs/delegated-review/` artifacts.

## Dependency Notes

No dependencies. Subtasks 2 and 3 build on the pruned runtime.

## Validation Strategy

`(cd runtime-kotlin && ./gradlew check)` plus a targeted migration test run
(`DatabaseMigrationsTest`) and a CLI-level assertion that
`--code-review-mode delegated` produces the typed unavailable error.

## Next Path

Subtask 2 restructures the mode vocabulary on the pruned runtime.
