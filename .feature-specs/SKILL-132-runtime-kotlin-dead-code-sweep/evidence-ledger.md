# SKILL-132 Deletion Evidence Ledger

Governs every SKILL-132 subtask. No declaration is removed without an entry here.

## Proof Rules

- Lexical absence is a lead, not proof. A repository-wide symbol search must cover Kotlin,
  Gradle, resources, orchestration contracts, governed skills, docs, and scripts.
- Ambiguous candidates require compile-delete proof: delete the smallest unit, compile and test
  the consuming modules, restore and reclassify on any non-lexical dependency.
- A single production caller in CLI, MCP, desktop, DI, KSP, serialization, or a resource
  reclassifies the candidate as `active` or `compatibility-retained`; it is not removed.
- Wire-name constants, database schemas, migrations, Gradle plugin implementations, and
  KSP/DI declarations are out of scope.

## Entry Schema

| field | meaning |
| --- | --- |
| declaration | fully-qualified symbol and source path |
| consumers checked | runtime, generated, reflection, serialization, migration, resource, script, governed-skill, install, CLI, MCP, docs |
| disposition | `active` / `compatibility-retained` / `test-only relocation` / `removable` |
| verification | the command or search that proved the disposition |

## Subtask 1 Entries

### Runtime-surface metadata and marker objects (AC-001, AC-002)

| declaration | consumers checked | disposition | verification |
| --- | --- | --- | --- |
| `skillbill.contracts.surface.RuntimeSurfaceContract` / `RuntimeSurfaceStatus` — `runtime-contracts/src/main/kotlin/skillbill/contracts/surface/RuntimeSurfaceContract.kt` | all | removable | Repo-wide `grep RuntimeSurface` over `runtime-kotlin`, `docs`, `skills`, `orchestration`, `scripts`, `platform-packs`: only the marker objects, `RuntimeSurfaceContractTest`, `RuntimeModuleSmokeTest`, `ARCHITECTURE.md`, and `gradle-module-split-evaluation.md`. No resource, serialization, reflection, DI, CLI, or MCP consumer. |
| `skillbill.install.runtime.InstallRuntime` — `runtime-infra-fs/.../install/runtime/InstallRuntime.kt` | all | removable | Same sweep. Only test and doc consumers. `CliInstallRuntimeTest` is an unrelated CLI test whose name merely contains the substring. |
| `skillbill.launcher.agentrun.LauncherRuntime` — `runtime-infra-fs/.../launcher/agentrun/LauncherRuntime.kt` | all | removable | Same sweep. |
| `skillbill.nativeagent.discovery.NativeAgentRuntime` — `runtime-infra-fs/.../nativeagent/discovery/NativeAgentRuntime.kt` | all | removable | Same sweep. |
| `skillbill.scaffold.runtime.ScaffoldRuntime` — `runtime-infra-fs/.../scaffold/runtime/ScaffoldRuntime.kt` | all | removable | Same sweep. `McpScaffoldRuntime` and `CliScaffoldRuntimeTest` are distinct classes and stay. |
| `skillbill.workflow.implement.FeatureImplementWorkflowRuntime` — `runtime-application/.../workflow/implement/` | all | removable | Same sweep. `FeatureImplementWorkflowRuntimeTest` tests workflow behavior, not the marker. |
| `skillbill.workflow.verify.FeatureVerifyWorkflowRuntime` — `runtime-application/.../workflow/verify/` | all | removable | Same sweep. |
| `RuntimeSurfaceContractTest` — `runtime-core/src/test/kotlin/skillbill/contract/` | n/a (test) | removable | Validates only the deleted metadata. |

Stale references stripped: `ARCHITECTURE.md` guardrail bullet, `docs/architecture/gradle-module-split-evaluation.md` bullet, `RuntimeArchitectureDocumentationTest` `assertContains("RuntimeSurfaceContract")`, `ImplementationOwnershipArchitectureTest` marker-file assertion, `runtime-cli/build.gradle.kts` and `runtime-mcp/build.gradle.kts` comments.

### Generic contract wrappers (AC-001, AC-003)

| declaration | consumers checked | disposition | verification |
| --- | --- | --- | --- |
| `skillbill.contracts.ContractEnvelope` | all | removable | Repo-wide `grep ContractEnvelope`: only `ContractResultTest`. `ScaffoldCliResultMappers` / `ScaffoldCliWireMaps` (the grep-grounding hits) reference `CliScaffoldRuntimeTest` in comments, not these wrappers — no runtime-cli production consumer exists. |
| `skillbill.contracts.ContractResult` | all | removable | Same sweep; only `ContractResultTest`. |
| `skillbill.error.ContractViolationException` | all | removable | Same sweep; only `ContractResultTest`. |
| `skillbill.contracts.RuntimeContract` | all | removable | Cascade: sole remaining consumer was `ContractViolationException`; post-deletion sweep returns zero hits. |
| `ContractResultTest` — `runtime-core/src/test/kotlin/skillbill/contracts/` | n/a (test) | removable | Validates only the deleted wrappers. |

### Module catalog (AC-001, AC-004, AC-005)

| declaration | consumers checked | disposition | verification |
| --- | --- | --- | --- |
| `skillbill.RuntimeModule` — `runtime-core/src/main/kotlin/skillbill/RuntimeModule.kt` | all | test-only relocation | Repo-wide `grep RuntimeModule`: consumers are `RuntimeArchitectureTest`, `RuntimeAdapterDependencyAllowlistTest`, `RuntimeArchitectureDocumentationTest`, `RuntimeModuleSmokeTest` — all tests. Relocated verbatim to `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeModuleCatalog.kt`. |
| `RuntimeModule.NAME` / `RuntimeModule.TOOLCHAIN_JDK` | all | removable | Sole consumer was `RuntimeModuleSmokeTest`; not carried into the catalog. |
| `RuntimeModuleSmokeTest` — `runtime-mcp/src/test/kotlin/skillbill/` | n/a (test) | removable | Its runtime-surface assertions die with the markers; its module/package assertions duplicate `RuntimeArchitectureDocumentationTest` and `RuntimeArchitectureTest` in runtime-core. Deleting it loses no distinct assertion. |

Assertion strength: the module list, subsystem-package list, `settings.gradle.kts` include list, and `ARCHITECTURE.md` fenced lists are still cross-checked against each other and against the independently hard-coded package set in `RuntimeArchitectureDocumentationTest`. The per-module dependency allow-list and package-ownership checks are unchanged apart from the catalog reference.

### Test fakes and no-op implementations (AC-001, AC-004)

| declaration | consumers checked | disposition | verification |
| --- | --- | --- | --- |
| `EmptyWorkListRepository` — `runtime-ports/.../persistence/WorkListRepository.kt` | all | test-only relocation | Consumers are runtime-core and runtime-application tests only (two modules), so it moves to `runtime-ports/src/testFixtures`. |
| `EmptyGoalPlanningPreparationRepository` (with its two private delegates) — `runtime-ports/.../persistence/GoalPlanningPreparationRepository.kt` | all | test-only relocation | Same two-module test consumption; moved to `runtime-ports/src/testFixtures`. |
| `EmptyReviewAttributionPort` — `runtime-ports/.../review/ReviewAttributionPort.kt` | all | test-only relocation | Consumed only by `runtime-core` tests; co-located in the same new `runtime-ports` test-fixtures source set with the other port fakes rather than splitting port fakes across two homes. |
| `NoopGoalPlanningPreparationEnvelopeValidator` — `runtime-domain/.../workflow/GoalPlanningPreparationEnvelopeValidator.kt` | all | test-only relocation | Consumed only by `runtime-application` tests; moved into that module's test source set. |

Port interfaces stay in `runtime-ports`; only implementations moved. Packages are unchanged, so no consumer import edits were required. `java-test-fixtures` was added to `runtime-ports` and wired as `testImplementation(testFixtures(project(":runtime-ports")))` in `runtime-core` and `runtime-application`.

### Individually audited unused helpers (AC-001, AC-005)

Method: a whole-repository token-frequency scan over `runtime-kotlin`, `skills`, `orchestration`, `scripts`, `docs`, `platform-packs`, and `desktop` (all `.kt`, `.kts`, `.md`, `.yaml`, `.json`, `.sh`, `.sql`, `.py`), counting every occurrence of each top-level declaration in the four target trees. Only declarations with exactly one occurrence — their own declaration site — were considered. The scan was re-run after deletion to catch cascades; it returned only the retained entries below.

| declaration | consumers checked | disposition | verification |
| --- | --- | --- | --- |
| `skillbill.telemetry.config.TelemetryRuntime` — runtime-application | all | removable | Zero occurrences outside its declaration. Dead facade over the live `TelemetryConfigRuntime`, which is retained. |
| `skillbill.application.review.RoutingSignalPathMatcher` — runtime-application | all | removable | Zero occurrences outside its declaration. Delegated wholly to the live `ReviewPathMatcher`. |
| `REQUIRED_CONTENT_SECTIONS` — `scaffold/runtime/ScaffoldSupport.kt` | all | removable | Zero occurrences. `REQUIRED_GOVERNED_SECTIONS` is live and retained. |
| `CANONICAL_CEREMONY_SECTION` — `scaffold/runtime/ScaffoldSupport.kt` | all | removable | Zero occurrences. |
| `CANONICAL_EXECUTION_SECTION` — `scaffold/runtime/ScaffoldSupport.kt` | all | removable | Sole consumer was `renderSkillBody`, removed in this sweep; its import in `ScaffoldTemplateRendering.kt` is deleted too. `REQUIRED_GOVERNED_SECTIONS` keeps live consumers in `AuthoringContentMutation` and `SkillMdShapeValidator` and is retained. |
| `selectedFeatureAddonSupportTargets` — `scaffold/runtime/ScaffoldSupport.kt` | all | removable | Zero occurrences. Its private helper `featureAddonPointerSpecsFor` has two live callers and is retained. |
| `renderSkillBody` — `scaffold/rendering/ScaffoldTemplateRendering.kt` | all | removable | Zero occurrences. `renderFrontmatter`, `renderDescriptorSection`, and `renderCeremonySection` remain live. |
| `GoalRunnerCompletedReport` — `runtime-domain/goalrunner/model/` | all | removable | Zero occurrences. Superseded by the live `GoalRunnerRunReport.Completed`. |
| `ReviewStatsSnapshot` — `runtime-domain/review/model/` | all | removable | Zero occurrences; not a serialized wire shape. |
| `RETIRED_PARTIAL_SCAFFOLD_KINDS` — `runtime-domain/scaffold/policy/` | all | removable | Zero occurrences. `RETIRED_PARTIAL_SCAFFOLD_KIND_ALIASES` is the live constant. |
| `summarizeLearningEntries` — `runtime-domain/learnings/LearningEntry.kt` | all | removable | Zero occurrences. |
| `QUARANTINE_REJECTION_CLASS_HANDOFF_ENVELOPE` — `runtime-domain/workflow/taskruntime/model/` | all | compatibility-retained | Zero Kotlin occurrences, but it is a documented mirror of the quarantine-rejection-class schema enum whose sibling value is live. Retained as a wire-name constant per the subtask non-goals. |
| `SCAFFOLD_COMMAND_KIND_PLATFORM_OVERRIDE_PILOTED`, `SCAFFOLD_COMMAND_KIND_CODE_REVIEW_AREA`, `SUPPORTED_SCAFFOLD_COMMAND_KINDS`, `RETIRED_PARTIAL_SCAFFOLD_COMMAND_KIND_ALIASES` — `runtime-domain/scaffold/model/command/ScaffoldCommandConstants.kt` | all | compatibility-retained | Zero Kotlin occurrences, but the file is an explicit CLI/MCP/Desktop adapter re-export facade. Removing CLI or MCP compatibility names is a subtask non-goal. |

### Deviations from the plan's proof procedure

The runtime implement phase forbids building, compiling, and running tests; compilation and test
execution belong to the validate phase. Compile-delete proof and the deliberate-violation
architecture-test probe therefore could not be executed inline. Every deletion above rests on an
exhaustive whole-repository occurrence count rather than a compiler round-trip, and the validate
phase runs the targeted module gate that closes AC-006:

```
rtk proxy ./gradlew :runtime-contracts:test :runtime-core:test :runtime-domain:test \
  :runtime-application:test :runtime-ports:test :runtime-infra-fs:test \
  :runtime-infra-sqlite:test :runtime-cli:test :runtime-mcp:test detekt
```

Known pre-existing failure, not attributable to this sweep: `CliCodeReviewParallelRuntimeTest`
fails 8 of 16 tests with `Platform pack discovery failed for platform-packs: Platform pack 'kmp':
feature_addon_usage[feature-task] entry 'android-compose-implementation' ... add-on file does not
exist`. The add-on file is present in the repository and on `main`. The failure comes from the
installed review catalog, not the repository: `publishInstalledReviewCatalog` stages only
`platform.yaml`, the baseline file, and area files into
`~/.skill-bill/installed-skills/<hash>/review-catalog/`, never `addons/`, while
`requirePackOwnedAddonPointer` resolves `feature_addon_usage` pointer targets as repo-relative.
The tests read the real `$HOME`, so every pack declaring `feature_addon_usage` (currently `kmp`)
hard-fails discovery on any machine with an install present.

Triage evidence: a clean `git worktree` at `main` (`69c97d01`) running
`./gradlew :runtime-cli:test --tests "*CliCodeReviewParallelRuntimeTest*"` reproduces the same 8
failures with the same message. This branch changes no file under `platform-packs/`, `skills/`,
`orchestration/`, or install staging, and does not touch this test or pack discovery. Out of scope
for this subtask; `install.sh` / `uninstall.sh` are not run here.

### Residual-reference sweep (AC-001, AC-002, AC-005)

Word-boundary `grep -rnw` for every deleted symbol across `*.kt`, `*.kts`, `*.md`, `*.yaml`,
`*.json`, `*.sh`, `*.sql`, `*.py`, excluding `.feature-specs/` and `build/`, returns exactly one
hit outside `runtime-kotlin/agent/history.md`: none. AC-002's live documentation targets —
`ARCHITECTURE.md` and `docs/architecture/gradle-module-split-evaluation.md` — are both stripped.

Retained `agent/history.md` references, all inside dated append-only entries:

- `:2150` — `RuntimeSurfaceContract`, in `## [2026-04-25] runtime-placeholder-surface-contracts`.
- `:708`, `:1261`, `:1475`, `:2235`, `:2238` — `RuntimeModule`, in entries dated at their time.

These are past-tense records of changes that did happen, not claims about current state; rewriting
dated entries would falsify the boundary-history log. Two `RuntimeModule` lines carry the
`reusable` marker and read as forward-looking lockstep guidance, so the write_history phase must
append an entry recording the `RuntimeModule` -> `RuntimeModuleCatalog` relocation into
`runtime-core` test sources and the deletion of the runtime-surface metadata, keeping that
guidance accurate going forward. The lockstep rule itself still holds and is still enforced: the
module list, subsystem-package list, `settings.gradle.kts` include list, and `ARCHITECTURE.md`
fenced lists remain cross-checked by the passing architecture tests.

### Validate-phase repair

`ImplementationOwnershipArchitectureTest` "runtime core is composition only and not an
implementation umbrella" failed sweep-caused after `RuntimeModule.kt` moved to test sources:
its `allowedPackages` allow-list still expected the now-absent `skillbill` root package in
`runtime-core/src/main/kotlin`. The assertion is an exact set equality, so the stale entry failed
loudly rather than silently passing. Fixed at root cause by tightening the allow-list to
`setOf("skillbill.di")` and updating the two assertion messages, which also strengthens the
downstream non-composition-package check (AC-005).

### Assertion-strength probe after the RuntimeModule rework

Deliberate-violation probe run once locally and reverted: a bogus `"runtime-bogus-probe"` module
was added to `RuntimeModuleCatalog.declaredModules`, then
`./gradlew :runtime-core:test --tests "*RuntimeArchitectureTest*"
--tests "*RuntimeArchitectureDocumentationTest*" --tests "*RuntimeAdapterDependencyAllowlistTest*"`.
Three assertions failed on the injected edge:

- `RuntimeAdapterDependencyAllowlistTest` "every declared module has only the curated main-source
  runtime project dependencies"
- `RuntimeAdapterDependencyAllowlistTest` "every declared module has only the curated
  test-fixtures runtime project dependencies"
- `RuntimeArchitectureDocumentationTest` "architecture document settings and runtime module
  declare the same graph"

The catalog was restored byte-for-byte from a backup copy and the working tree confirmed clean of
the probe. Relocating the catalog into test sources preserved assertion strength.

## Subtask 2 Entries — dormant review pilot

Re-traced against `main` (`69c97d01`) from every composition root (`RuntimeComponent`, CLI dispatch,
MCP registry) with word-boundary `grep -rnw` over `*.kt`, `*.kts`, `*.md`, `*.yaml`, `*.json`, `*.sh`,
excluding `build/` and `.feature-specs/`. Most spec-named candidates re-traced to **active**; the
removable slice is the ignored auto-eligibility path.

### Candidate dispositions (AC-001, AC-002, AC-004, AC-005)

| declaration | consumers checked | disposition | verification |
| --- | --- | --- | --- |
| `skillbill.review.context.ReviewExecutionModePolicy` (runtime-domain/.../ReviewExecutionModePolicy.kt) | Kotlin, DI, docs | active (signature narrowed) | `ParallelCodeReviewRunner.kt:99,103` call `resolveWithRule`/`resolve` on the live parallel-review path |
| `ResolvedReviewDepth`, `ResolvedReviewExecutionMode` (ReviewContextModels.kt) | Kotlin, durable state, docs | active | `ResolvedReviewExecutionMode` gates `preflightDelegatedWorkers`; `ResolvedReviewDepth.decidingRule` is persisted via `GoalSubtaskReviewState.deciding_rule` |
| `ReviewAutoEligibility` (ReviewContextModels.kt) | Kotlin, DI, serialization, resources, docs, skills, scripts | **removable** | Sole consumer `resolveAutoByEligibility` carried `@Suppress("UNUSED_PARAMETER")` and returned a constant; `grep -rnw ReviewAutoEligibility` after deletion returns no hits outside `.feature-specs/` |
| `ParallelCodeReviewRunner.HIGH_RISK_SIGNAL` regex | Kotlin | **removable** | Only fed `ReviewAutoEligibility.highRisk`, whose value was discarded |
| `ReviewAssignment` (ReviewContextModels.kt) | Kotlin, DI, serialization | active | Composed by `ReviewPreparationService.composeAssignments`, launched by `DelegatedReviewWorkerLauncher`, projected by `ReviewPacketProjection` |
| `ReviewContextPacket` (ReviewContextModels.kt) | Kotlin, DI, serialization | active | Produced by `ReviewPreparationService.composePacket`, consumed by `ReviewPacketProjection` and `DelegatedReviewLaunchBroker` |
| `GovernedReviewLaunch` (ReviewContextModels.kt) | Kotlin, DI | active | `ReviewPacketProjection` and `DelegatedReviewLaunchBroker` construct it on the delegated launch path |
| `skillbill.ports.review.ReviewEvidenceBroker` + `ReviewEvidenceBrokerBinding` | Kotlin, DI, launch wire | active | Bound at `RuntimeComponent.kt:636-637`; consumed by `DelegatedReviewLaunchBroker`, `NativeReviewOperationProtocol`, `AgentRunProcessRunner` |
| `FileSystemReviewEvidenceBroker(+Factory)` | Kotlin, DI, Gradle | active | Bound in `RuntimeComponent`; `runtime-application/build.gradle.kts` references the factory |
| `review_context_budget` parsing (`FileSystemRepoLocalConfig`) | Kotlin, config file, docs | active — all nine sub-keys consumed | See sub-key table below |
| `ReviewContextSchemaPaths` + `orchestration/contracts/review-context-schema.yaml` + `ReviewContextSchemaValidator` + `ReviewContextEnvelopeValidatorAdapter` + runtime-infra-fs `Copy` task | Kotlin, DI, classpath resource, Gradle | active — retained atomically | `RuntimeComponent.kt:629-631` binds the adapter as `ReviewContextEnvelopeValidator`; `ReviewPreparationService.prepare` and `DelegatedReviewLaunchBroker` invoke `validate` on every packet and assignment envelope. Not orphaned, so AC-005's removal branch does not apply and nothing is deleted |

### `review_context_budget` sub-key consumption (AC-002)

Every parsed sub-key reaches an active consumer, so AC-002 resolves on the proven-active branch and
no key is removed. No silently-ignored key remains: `validateBudgetKeys` rejects anything outside
this set.

| sub-key | active consumer | behavior-affecting test |
| --- | --- | --- |
| `max_parent_packet_bytes` | `ReviewPreparationService.kt:139`, `DelegatedReviewLaunchBroker.kt:154` | added `ReviewPreparationServiceTest` "a configured parent-packet bound the default would accept rejects the same packet" |
| `max_lane_launch_bytes` | `ReviewContextModels.kt:611` (`budgetOutcomeOrNull`), `DelegatedReviewWorkerLauncher.kt:66` | `ReviewContextModelsTest` "oversized compact launch returns typed budget evidence" |
| `max_lane_evidence_bytes` | `FileSystemReviewEvidenceBroker.kt:313` | `ParallelCodeReviewRegressionTest.kt:170` |
| `max_evidence_result_bytes` | `FileSystemReviewEvidenceBroker.kt:309` | `ParallelCodeReviewRegressionTest.kt:143` |
| `max_lane_result_bytes` | `ReviewBudgetEvaluator.laneResultOutcome` | `ParallelCodeReviewRegressionTest.kt:98`, `ParallelLaneIsolationTest.kt:85` |
| `max_assignment_expansions` | `ReviewPreparationService.kt:71`, `FileSystemReviewEvidenceBroker.kt:159` | `ReviewPreparationServiceTest` "an assignment exceeding the configured expansion bound is rejected" |
| `max_specialist_tool_calls` | `FileSystemReviewEvidenceBroker.kt:243` | `FileSystemReviewEvidenceBrokerTest.kt:553` |
| `max_specialist_model_turns` | `FileSystemReviewEvidenceBroker.kt:256` | `ParallelCodeReviewRegressionTest.kt:122` |
| `provider_token_thresholds.*` | `FileSystemReviewEvidenceBroker.kt:284` | `ParallelCodeReviewRegressionTest.kt:197,223` |

### Legacy-configuration contract (AC-003)

No `review_context_budget` key was removed, so the removal branch is vacuous. The contract is strict
inside the object and tolerant at the document root, and both directions are now covered:

- strict: `validateBudgetKeys` raises `MalformedRepoLocalConfigError` naming `review_context_budget.<key>`
  — `FileSystemRepoLocalConfigTest` "review context budget rejects an unsupported nested key naming it"
  and the pre-existing "rejects unknown nested keys before launch".
- accepted set: `FileSystemRepoLocalConfigTest` "review context budget accepts exactly the consumed key set"
  pins all nine keys with non-default values, so a future removal cannot pass silently.
- tolerant root: pre-existing "unknown future keys are tolerated without error".

### Deletion and rename record

- Deleted `ReviewAutoEligibility` and the `eligibility` parameter from
  `ReviewExecutionModePolicy.resolve` / `resolveWithRule`; deleted the private
  `resolveAutoByEligibility` and `ParallelCodeReviewRunner.HIGH_RISK_SIGNAL`.
- Renamed `ELIGIBILITY_RULE` (`auto_depth_by_size_and_risk_eligibility`) to `DEFAULT_RULE`
  (`auto_depth_default`). The string is behavior-neutral: `ParallelCodeReviewRunner` keeps only
  `.resolvedMode`, and the persisted `deciding_rule` values come from
  `FeatureTaskRuntimeReviewPassSequence`, which is untouched and still emits
  `auto_depth_by_pass_number:*`. `goal-subtask-review-state-schema.yaml` stores `deciding_rule` as a
  free string with no enum.
- Resolved depth is unchanged in every case: `auto` still resolves to `inline`.
- Docs reconciled: `orchestration/review-orchestrator/PLAYBOOK.md:58`, `README.md:111`,
  `docs/capabilities.md:67`, `docs/getting-started.md:253`,
  `docs/getting-started-for-teams.md:63`. `docs/review-telemetry.md` needed no change because the
  accepted key set did not change.
- Post-change sweep: `grep -rn "auto_depth"` and `grep -rnw ReviewAutoEligibility` show no stale
  reference outside `.feature-specs/`.

### Known pre-existing failure

`CliCodeReviewParallelRuntimeTest`'s 8 install-staging failures reproduce on a clean `main` worktree
and are recorded in the subtask 1 triage above. They are not attributed to this slice.

## Subtask 3 — orphan contract assets

Scope: the four suspect schema families named in `spec.md:21`. Each was traced
producer -> serialization/persistence seam -> resource copy -> parser -> validator -> consumers
before any disposition. Verification command shared by every family (word-boundary symbol and
wire-name sweep across Kotlin, Gradle, resources, contracts, skills, docs, scripts, manifests,
excluding `build/`):

```
grep -rn "<schema-file-name>\|<SchemaPaths symbol>\|<CONTRACT_VERSION const>\|<$id>" \
  runtime-kotlin orchestration skills docs scripts --include="*.kt" --include="*.kts" \
  --include="*.yaml" --include="*.md" --include="*.sh" | grep -v "/build/"
```

### Family dispositions (AC-001, AC-002, AC-004)

| family | producer | persistence seam | resource copy | runtime validator | parity coverage | disposition |
| --- | --- | --- | --- | --- | --- | --- |
| `feature-task-execution-identity` | `GoalRunnerWorkflowStores.kt:302-303`, `FeatureTaskContinuationLookupService` | `FeatureTaskExecutionIdentity.contractVersion` persisted via `saveFeatureTaskExecutionIdentity`; re-validated on read at `GoalRunnerWorkflowStores.kt:280,302` | `copyFeatureTaskExecutionIdentitySchema` (`runtime-infra-fs/build.gradle.kts:278`), consumed at `:491,:518` | none reads the YAML; `FeatureTaskExecutionIdentityPolicy` validates in Kotlin against `FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION` | **added** `FeatureTaskExecutionIdentitySchemaContractVersionTest` | **active — blocker** |
| `feature-task-runtime-worker-ownership` | `FeatureTaskRuntimeWorkerCoordinator` lease claim path | `FeatureTaskRuntimeWorkerOwnership.contractVersion` persisted and loud-failed on read at `WorkflowStateStore.kt:494-531` (`InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError`); crash reconciliation replays these rows | `copyFeatureTaskRuntimeWorkerOwnershipSchema` (`build.gradle.kts:265`), consumed at `:492,:519` | none reads the YAML; `WorkflowStateStore.kt:510` validates in Kotlin against the const | `FeatureTaskRuntimeWorkerOwnershipSchemaContractVersionTest` | **active — blocker** |
| `goal-subtask-review-state` | `FeatureTaskRuntimeGoalContinuationRecorder`, `GoalRunnerWorkflowStores.kt:1711` | `GoalSubtaskReviewState.contractVersion` (`GoalSubtaskReviewState.kt:343-346`) persisted per subtask; `GoalSubtaskReviewStateLegacyContractTest` pins the 0.1 -> 0.2 record migration | `copyGoalSubtaskReviewStateSchema` (`build.gradle.kts:236`) | none reads the YAML; `GoalSubtaskReviewState` parses and loud-fails in Kotlin | `GoalSubtaskReviewStateContractVersionTest` (scaffold) + `GoalSubtaskReviewStateSchemaContractVersionTest` (contracts) | **active — blocker** |
| `review-context` | `ReviewPreparationService.composePacket` | packet/assignment envelopes | `copyReviewContextSchema` (`build.gradle.kts:47`) | **yes** — `ReviewContextSchemaValidator` reads `ReviewContextSchemaPaths.CLASSPATH_RESOURCE` and is bound as `ReviewContextEnvelopeValidator` at `RuntimeComponent.kt:629-631` | `ReviewContextSchemaContractVersionTest` | **active** (subtask 2 decision, not reversed) |

### Blockers: why the three durable-record schemas are not removable (AC-004, AC-002)

Lexical reachability of the YAML asset is parity-test-only for all three, but AC-002's veto list is
not lexical. Each family fails the removal test on a concrete durable-record dependency:

- **execution-identity** — every continued feature-task workflow persists a
  `feature_task_execution_identity` row carrying `contract_version: "0.1"`, and
  `FeatureTaskExecutionIdentityPolicy.validate` rejects any other value on read. Existing databases
  in the field already hold these rows. The YAML is the only specification of that on-disk shape.
- **worker-ownership** — worker-lease rows carry `contract_version: "0.1"`, enforced at the
  `WorkflowStateStore` read seam that crash reconciliation depends on. `CliGoalRuntimeTest:1396`
  writes the const directly when seeding a lease row.
- **goal-subtask-review-state** — this very run persists goal-subtask review state at
  `contract_version: "0.2"`, and a legacy-record migration path from `0.1` is live and tested.

In all three cases the `..._CONTRACT_VERSION` const is live product surface, not schema residue:
deleting the YAML would leave a durable, version-gated record with no contract of record, and would
break the governed contract recipe in `AGENTS.md` ("Every YAML under `orchestration/contracts/` is a
runtime contract", with a mandated Kotlin const plus parity test). Removal, and any decision to add a
YAML-backed runtime validation seam in place of the hand-written Kotlin checks, requires a separate
contract-versioning decision with a record-migration plan — out of scope for a dead-code sweep and
forbidden here by AC-005.

Gap closed in this subtask: execution-identity was the one family with neither a runtime validator
nor parity coverage, so its schema could drift from
`FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION` silently. Added
`runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/contracts/workflow/FeatureTaskExecutionIdentitySchemaContractVersionTest.kt`,
mirroring the worker-ownership parity test, which also completes step 3 of the `AGENTS.md` contract
recipe for that family. No validation seam was added and no loud-fail behavior was weakened.

### Removals (AC-003, AC-005, AC-006)

None. No family reached the `removable` disposition, so the deletion unit, the post-deletion residue
sweep, and the JAR-bundling delta are vacuous for this subtask: the bundled resource set under
`skillbill/contracts/` is unchanged by design, and the four schemas must all still be present. No
database table, column, or historical migration was touched (AC-005 holds by scope exclusion,
verifiable in the branch diff). AC-006's evidence is that the retained resource set and the contract,
persistence, workflow, migration, and configuration-cache suites stay green with the added parity
test.
