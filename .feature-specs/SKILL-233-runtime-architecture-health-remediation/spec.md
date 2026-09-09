# SKILL-233: runtime architecture health remediation

## Intended Outcome

The `runtime-kotlin` module graph is already correct and mechanically held:
dependencies point inward, no inner layer imports an adapter, no application
code touches SQL or the filesystem, and every inner-layer guard baseline is
empty. SKILL-227, SKILL-229, SKILL-231, and SKILL-232 delivered that. This
feature fixes the layer below the module graph, where the units inside each
module stopped being cohesive because four mechanical rules shaped them.

An architectural audit on 2026-09-04 measured the tree and found eight
conditions. Each is stated with the number that proves it so the exit state is
checkable.

1. **Ceiling-driven fragmentation.** `PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING`
   is 500, detekt `TooManyFunctions` is 11 and `LongParameterList.constructorThreshold`
   is 7. Together they produced `skillbill.application.featuretask`: 185 files,
   177 prefixed `FeatureTaskRuntime`, 66 `@Inject` classes. The run loop alone
   is 65 files and 10,606 lines. SKILL-231 renamed the `Continued<N>` files, but
   the fragments survived as separate `@Inject` classes: `FeatureTaskRuntimeRunLoopCollaborators`
   exposes 31 getters named `driveContinued1..4`, `checkpointContinued1..6`,
   `attemptSettlementContinued1..3`, `launchContinued1..N`, fed by five
   `*Collaborators` bundle classes. Thirty `*Dependencies` / `*Collaborators`
   bundles exist across the runtime to pass the constructor threshold, which
   hides over-injection rather than removing it. In `runtime-core`, 126
   `@Provides` functions sit behind 13 mixin interfaces named by arbitrary
   pairing (`RuntimeTelemetryInstallProvides`, `RuntimeGoalRunnerScaffoldProvides`,
   `RuntimeCompositionMiscProvides`), each forwarding to a `*Bindings` object;
   35 of them are identity binds `fun x(impl: Impl): Port = impl` written twice.
   `scripts/split-runloop.py` is the script that performed the original split.
   The last 100 commits added 1,246 production files and deleted 241.

2. **Behaviour in `runtime-ports`.** The module is 10,571 lines; 7,031 of those
   are not interfaces. It carries codecs (`GoalContinuationArtifactCodec`,
   `GoalWorkerSubtaskRequestArtifactCodec`), reducers
   (`GoalSubtaskReviewSummaryReducer`, `GoalSubtaskReviewOutcomeDispositionReduction`),
   derivations (`GoalTerminalOutcomeDerivation`, `GoalContinuationOutcomeSelection`),
   filesystem writes (`DecompositionManifestFileWrites` calls `Files.*`), a
   migration (`LegacyGoalRunnerControlMigration`, which also exists under
   `skillbill.application.workflow`), an `@Inject` dependency bundle for a
   SQLite store (`WorkflowGoalRunnerStoreDepsModels`), 14 files importing
   kotlinx serialization or `JsonSupport`, and 23 `Noop*` / `Unavailable*` /
   `Empty*` objects. Fifty-one file basenames are duplicated across modules,
   several of them ports-versus-application pairs (`AttemptLedgerAccumulator`,
   `AttemptLedgerDecoding`, `DecompositionManifestRuntimeStateSupport`).

3. **Null objects on the production classpath, backed by a global.** SKILL-231
   classified 34 null objects; the classification holds, but every one still
   ships in main source. `UnitOfWork` gives `unaddressedFindings`,
   `featureTaskRuntimeAuditGenerations`, and `agentActivityStamps` default
   getters returning an `Unavailable*` or `Empty*` singleton.
   `NoopWorkflowGitBranchOperations.checkoutBranch` returns `status = "ok"`
   without touching git and reports the swallow to
   `skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics`, a
   `@Volatile var` global in `runtime-contracts` with a `resetBindingForTests`
   hook, referenced from 22 main-source files. A recording null object is a
   test convenience; the runtime's loud-fail contract does not need it in
   production.

4. **Stringly-typed core.** Zero `value class` declarations. Identifiers
   (`workflowId`, `issueKey`, `subtaskId`, `runId`, `sessionId`, `agentId`)
   are `String` in 674 signatures across `runtime-ports` and
   `runtime-application`. Seventy-eight model fields named `status`, `mode`,
   `kind`, `phase`, or `outcome` are `String`; 23 of them are literally
   `val status: String`. `WorkflowGitOperationResult.ok` is `status == "ok"`.
   Application code compares status strings against literals in 58 places.
   The vocabulary behind those strings is declared nowhere and everywhere: the
   literal `"completed"` appears in 70 main-source files, 48 files define
   their own completed constant, 131 local `setOf("token", ...)` sets and 41
   local alias maps (for example `ProsePhaseOutputParse.STATUS_TOKENS` and
   `STATUS_ALIASES` in `skillbill.workflow.taskruntime`) each re-declare a
   subset of the same tokens, and 1,695 distinct snake_case literals sit in
   application, domain, and ports main source, most of them wire keys such as
   `"contract_version"`, `"status"`, `"subtask_id"`, and `"produced_outputs"`.

5. **A per-run setting threaded through every signature.** `dbOverride` /
   `dbPathOverride: String?` appears as a parameter 305 times in
   `runtime-application`, 70 in `runtime-ports`, and 105 in `runtime-cli`,
   although `EnvironmentContext.dbPathOverride` already exists on the
   `RuntimeContext` bound at the composition root.

6. **Two areas form a monolith inside `runtime-application`.** `featuretask`,
   `goalrunner`, `goalplanning`, and `planningprojection` are 344 of 534 files.
   `featuretask` imports 13 sibling areas; `goalrunner` imports 13 including
   `featuretask`; `work` imports both. Of the last 150 commits' production-file
   touches, 1,014 landed in `featuretask` and 416 in `goalrunner`.

7. **Package roots do not reveal module or layer.** `runtime-infra-sqlite`
   splits 97 files under `skillbill.db` and 63 under
   `skillbill.infrastructure.sqlite`. `runtime-infra-fs` has 125 files under
   `skillbill.infrastructure.fs` and 268 under eight other roots (`scaffold`
   109, `install` 61, `launcher` 28, `contracts` 23, `nativeagent` 20,
   `agentaddon` 10, `skillremove` 9, `goalplanning` 8). `runtime-application`
   has seven files under `skillbill.telemetry` outside `skillbill.application`.
   `RuntimeModuleCatalog.declaredSubsystemPackages` lists 30 roots for 10
   modules.

8. **Domain is not JVM-free.** `runtime-domain` imports `java.nio.file.Path` in
   15 files and kotlinx serialization in 6, and `runtime-contracts` exposes
   kotlinx serialization as an `api` edge so every dependant inherits it.
   `WorkflowEngine` builds its snapshot as `linkedMapOf("workflow_id" to ...)`;
   raw `Map<String, Any?>` appears 342 times in domain main source.

The outcome is a runtime where a reader can open one file and see one
responsibility, where `runtime-ports` is interfaces and their DTOs and nothing
else, where an identifier or a status is a type the compiler checks, where a
run's database location is decided once, where the feature engine is a module
with a named inbound API, where a package name tells you the module, and where
the domain compiles against the Kotlin standard library alone.

## Dependency

This feature follows SKILL-231 (inward-layer hardening) and SKILL-232 (unused
declaration deletion) and reuses their guards. It does not reopen their
acceptance criteria. The null-object classification test, the spillover
filename scanner, the module-edge pins, the package-cycle census, and the
ambient-clock and ambient-environment scanners all stay; this feature changes
what they measure, not how.

Adding a Gradle module was named a plan-level decision in SKILL-231. This spec
is that decision for one module, `runtime-engine`, in subtask 4.

## Acceptance Criteria

1. `PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING` is raised or removed
   in favour of a cohesion rule, detekt `TooManyFunctions` and
   `LongParameterList.constructorThreshold` are re-tuned, and the reason for
   each new value is recorded in `runtime-kotlin/agent/decisions.md`. Per-function
   complexity limits (`LongMethod`, `CyclomaticComplexMethod`, `NestedBlockDepth`,
   `ComplexCondition`) do not change.
2. No production identifier, file name, or type name in any runtime module
   carries a `Continued<N>`, `Support`, `Helpers`, `Misc`, `Extras`, or
   letter-plus-digit (`A1`, `B7`) suffix. The spillover scanner covers
   identifiers as well as file names, and its baseline is empty.
3. The run loop under `skillbill.application.featuretask` is expressed as a
   small set of cohesive collaborators, each with one responsibility a reader
   can name (phase execution, attempt settlement, checkpoint, review gate,
   validation gate, launch, output verification and persistence, planning
   branch, transitions). No `*Collaborators` or `*Dependencies` bundle exists
   whose only purpose is to pass a constructor threshold. The subtask report
   states the before and after file, class, and `@Inject` counts.
4. `runtime-core` binds each port to its adapter with one `@Provides` function
   directly on the component or on area-named mixins. No `*Bindings` object
   forwards an identity bind, and no mixin is named by pairing two unrelated
   areas.
5. `runtime-ports` main source contains interfaces, their DTOs, and pure
   extension helpers over those DTOs only. No codec, reducer, derivation,
   filesystem call, migration, `@Inject` class, or kotlinx serialization import
   remains. The subtask report states the new non-interface line count.
6. No `Noop*`, `Unavailable*`, or `Empty*` port object ships in any module's
   main source. Test substitutes live in `testFixtures`. Every `UnitOfWork`
   member is abstract. A production path that needs an optional capability
   models it with a nullable port or a sealed availability type bound at the
   composition root. `RecordingNullObjectDiagnostics` is deleted.
7. `runtime-domain` main source imports nothing under `java.nio`, `java.io`,
   `kotlinx.serialization`, `com.fasterxml`, or `org.yaml`. `runtime-contracts`
   no longer exposes kotlinx serialization as an `api` edge. JSON and YAML
   codecs live in `runtime-contracts` or an infrastructure module.
8. Workflow id, issue key, subtask id, review run id, session id, and agent id
   are `@JvmInline value class` types in `runtime-domain`, used in every port
   and application signature that carries them. Wire boundaries (CLI, MCP,
   SQLite, contracts DTOs) convert at the edge. The `String`-typed count for
   those six names in `runtime-ports` and `runtime-application` main source
   is zero.
9. Every `status`, `mode`, `kind`, `phase`, and `outcome` field in
   `runtime-ports` and `runtime-domain` models is an enum or sealed type with
   one `wireValue` and one `fromWire` companion, or is inventoried in
   `ARCHITECTURE.md` as an open pack-authored surface with the reason. No
   application `when` or `if` compares one of those fields to a string literal.
   Every wire token and wire key has exactly one declaration: tokens as the
   `wireValue` of their enum in `runtime-domain`, keys as constants on the
   contract that owns them in `runtime-contracts`. No main-source file outside
   those declarations contains a local token set, alias map, or snake_case
   literal that duplicates a declared token or key. A scanner enforces it with
   an empty baseline.
10. `dbOverride` and `dbPathOverride` do not appear in any `runtime-application`
    or `runtime-ports` method signature. `DatabaseSessionFactory` resolves the
    database path once from the bound `EnvironmentContext`. `runtime-cli`
    keeps its `--db` option and feeds it into `RuntimeContext` only.
11. `skillbill.application.featuretask`, `goalrunner`, `goalplanning`, and
    `planningprojection` live in a new Gradle module `runtime-engine` with
    dependencies `runtime-ports`, `runtime-domain`, `runtime-contracts`, and
    the remaining `runtime-application`. `runtime-application` does not depend
    on `runtime-engine`; entry adapters and `runtime-core` do. The engine's
    inbound API is the set of `RuntimeComponent` abstract properties it
    exposes today, and that set is pinned by an architecture test.
    `RuntimeModuleCatalog`, `settings.gradle.kts`, the module-edge pins, and
    `ARCHITECTURE.md` record the eleventh module.
12. Every `runtime-infra-sqlite` file is under `skillbill.infrastructure.sqlite`;
    every `runtime-infra-fs` file is under `skillbill.infrastructure.fs`;
    every `runtime-application` file is under `skillbill.application`; every
    `runtime-engine` file is under `skillbill.engine`. Classpath resource paths
    that followed the old validator packages move with them or are loaded by
    an explicit path constant. `RuntimeModuleCatalog.declaredSubsystemPackages`
    shrinks accordingly.
13. Behaviour is unchanged. The only intended observable difference is
    criterion 6: a capability that was silently absent now fails or is
    explicitly modelled as absent, and each such site carries a test naming the
    silent path it closes.
14. `runtime-kotlin/gradlew check`, `skill-bill validate`, and `./install.sh`
    pass with no new suppression, no new exemption, and no baseline entry
    recorded to make a test pass.

## Constraints

- Never mix a behaviour change with a structural move in the same commit.
- Guards ratchet one way. A rule is changed by argument in
  `runtime-kotlin/agent/decisions.md` first (criterion 1 is that argument), never
  by recording a baseline entry to turn a red test green.
- No new port without a second implementation, a test substitute, or a module
  boundary the composition root must cross. The engine module boundary in
  subtask 4 qualifies; nothing else does by default.
- No comments. Names and small functions carry the meaning.
- `@OpenBoundaryMap` sites inventoried in `ARCHITECTURE.md` stay open. This
  feature closes string-typed *fields*, not the inventoried raw-map payloads.
- The `*GitOperations` port family keeps its name.
- Subtask commits stand alone: a commit that moves a type also moves every
  reference, and `runtime-kotlin/gradlew check` is green at every subtask
  boundary.
- Detekt per-function complexity thresholds are not relaxed to absorb a merge.
  A merged unit that exceeds them is split by responsibility, not by count.

## Non-Goals

- `intellij-plugin`, `vscode-extension`, `skills/`, `platform-packs/`,
  `orchestration/`, `install.sh`, and the skill render pipeline. The duplicated
  `IdeStatusJsonMapper` in the two IDE extensions is recorded as a finding for a
  later spec.
- Retiring inventoried `@OpenBoundaryMap` payloads or shrinking the raw-map
  allow-list.
- Collapsing CLI and MCP presentation into a shared module (SKILL-231 recorded
  this decision).
- Introducing coroutines or changing the thread-based execution model.
- Reworking SQLite schema, migrations, or the Early/Late migration file split
  beyond the package move in criterion 12.
- Deleting `scripts/split-runloop.py` is in scope only as cleanup after
  criterion 3; rewriting it is not.
- Adding tests for coverage. `bill-unit-test-value-check` applies: each new
  test names the bug it catches.

## Validation Strategy

- Architecture tests are the primary proof. Criteria 2, 4, 5, 6, 7, 8, 11, and
  12 each map to a scanner that asserts set equality against an empty baseline
  or a pinned inventory, with an acceptance and a rejection fixture.
- Criterion 3 is proven by the file, class, and `@Inject` counts in the
  subtask report plus the absence of bundle classes; the run-loop tests in
  `runtime-application/src/test` stay green without modification beyond
  constructor wiring.
- Criterion 6 is proven per site: a test that drives the path with the
  capability absent and asserts the loud failure or the explicit absent branch.
- Criteria 8, 9, and 10 are proven by the compiler and by a search showing the
  old signatures gone from main source.
- Criterion 11 is proven by `gradlew :runtime-engine:compileKotlin` succeeding
  without `runtime-application` on its compile classpath for the engine's own
  packages, and by the entry adapters compiling against the pinned API.
- `runtime-kotlin/gradlew check` runs in a clean checkout at every subtask
  boundary.

## Delivery Plan

1. **Guard recalibration, run-loop consolidation, DI flattening.** Re-tune the
   four shaping rules, widen the spillover scanner to identifiers, merge the
   run-loop fragments into cohesive collaborators, dissolve the bundle classes,
   and replace the two-layer DI binds with direct ones.
2. **Ports evacuation and domain purity.** Move behaviour out of
   `runtime-ports` to domain or infrastructure, move null objects to
   `testFixtures`, make `UnitOfWork` abstract, delete the diagnostics global,
   and strip JVM and serialization imports from `runtime-domain`.
3. **Typed boundary signatures and shared vocabulary.** Introduce value-class
   identifiers and closed status types, declare every wire token and key once,
   and resolve the database path once at the composition root.
4. **Engine module and package roots.** Extract the feature engine into
   `runtime-engine` behind a pinned API, and give every module one package root.

Subtasks 1 and 2 are independent. Subtask 3 depends on 2 because the types it
introduces live in the domain module that subtask 2 cleans. Subtask 4 depends
on all three so that files are moved once, after they have their final shape
and names.
