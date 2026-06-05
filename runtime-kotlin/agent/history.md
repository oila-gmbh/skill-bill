## [2026-06-05] SKILL-68 goal-subtask-commit-sha-completion
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports
- New `FeatureTaskRuntimeGoalContinuationOutcomeSupport`: under `suppress_pr`, resolves commit SHA from `commit_push` phase payload first, then measures git HEAD once via `WorkflowGitOperations`; records `status=complete` iff non-blank SHA found, else `status=blocked` with explicit `blocked_reason` and `last_resumable_step=commit_push`. reusable
- Defense-in-depth in `GoalRunnerWorkflowStores`: `terminalOutcomeFor` falls through on `complete`-without-SHA rows so the measure branch can heal them; `persistMeasuredCompletion` backfills SHA-less complete rows idempotently; `reconcileAuthoritativeOutcomes` gains nullable `repoRoot` and measures+backfills recoverable candidates. reusable
- `GoalRunner.storedOutcome` no longer silently no-ops when `manifest.subtasks[id].workflowId` is null — falls back to manifest-workflowId-independent reconciliation. reusable
- `DecompositionManifestRuntimeStateSupport.commitShaFrom` sources SHA from `commit_push_result.commit_sha` (preferred) or `goal_continuation_outcome.commit_sha`; loud-fail on genuine mismatch. reusable
- `WorkflowGitOperations` port wired into `FeatureTaskRuntimePhaseGates` via DI; all new behavior gated on `suppress_pr` so non-goal-continuation and `STACKED_BRANCHES` runs are unaffected.
- Four AC6 regression cases proven in `FeatureTaskRuntimeRunnerTest` (payload SHA / measured SHA / blocked no-SHA / pre-existing complete-without-SHA backfill).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-06-05] SKILL-67 validation-gate-rename-sweep
Areas: runtime-cli, runtime-infra-fs/install, feature-task runtime tests
- Added regression coverage that a goal-continuation `feature-task` child reports task-runtime status and resumes a completed child without relaunching phases.
- Added install cleanup coverage for the deprecated `bill-feature-task-runtime`/`mdp-feature-task-runtime` names while canonical `bill-feature-task` remains selected. reusable
- Verified the closing gate without changing production review, audit, validation, schema, or installer behavior.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-06-05] SKILL-67.3 goal-runner-direct-runtime-coupling
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-ports
- Goal child launches now bypass prose skill prompts and spawn `skill-bill feature-task run|resume <issue_key> <spec_path>` directly, always carrying `--goal-parent-issue-key`, `--goal-subtask-id`, `--goal-branch`, `--suppress-pr`, and the resolved `--agent`. reusable
- `SkillRunGoalContinuationContext` now carries `specPath` and optional `childWorkflowId`; presence of the child workflow id selects runtime `resume`, so completed runtime phases are skipped by the runtime instead of re-entering `workflow continue`.
- Goal outcome/progress/history seams resolve the owning workflow family (`IMPLEMENT` or `TASK_RUNTIME`) before reads/writes, so task-runtime child rows support terminal gating, worker-request audit artifacts, stale reconciliation, and status/watch projections. reusable
- Prompt overrides remain the phase-agent path; only default goal-continuation child launches are converted to direct runtime commands.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-05] SKILL-67.2 skill-promotion-and-legacy-deprecation
Areas: runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-mcp
- Promoted the runtime skill to canonical `bill-feature-task` and demoted the prose orchestrator to `bill-feature-task-legacy`; added `bill-feature-task-runtime`->`bill-feature-task` to `InstallLegacySkillNames.renamedSkillPairs` (priors retained). The alias value may equal a real installed skill — safe because `legacySkillBillCleanupNames` consumes only the oldName key. reusable
- Validation-contract repointing pitfall: `RepoValidationRuntime.validateWorkflowContracts` and `FeatureSpecSkillWiringContractTest` are keyed by content-path. When the prose carrying `feature_implement_*` step-id markers ("Step id: `assess/implement/pr_description`") moves to the legacy file, both MUST be repointed there or validation/tests fail — the promoted thin trigger has none of those markers. reusable
- `WorkflowEngine.CONTINUATION_CONTENT_PATHS` is a forward map keyed by definition.skillName; both `bill-feature-task` and `feature-task-runtime` now legitimately resolve to `skills/bill-feature-task/content.md` (no per-value uniqueness assumption). Update the matching golden `mcp-feature-task-runtime-workflow.json` continuation path in the same change. reusable
- Two-parallel-arrays invariant for canonical renames: the Kotlin `renamedSkillPairs` and the bash `RENAMED_SKILL_PAIRS` in `uninstall.sh` must stay in sync.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (subtask scope)

## [2026-06-05] SKILL-67.1 canonical-runtime-cli-and-mcp-surface
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, orchestration/contracts
- Renamed experimental surface to canonical: CLI `feature-task-runtime`->`feature-task` (run/status/resume) and 10 MCP tools `feature_task_runtime_*`->`feature_task_*`; old names kept as working deprecated aliases. Stats: canonical `feature-task-stats`/`feature_task_stats`, `runtime-stats` kept as alias.
- New reusable pattern: working-deprecated-alias (first CLI/MCP precedent). CLI alias = hidden clikt subcommand emitting a stderr deprecation note via `state.liveStderr` so stdout stays byte-identical; MCP alias names re-point to the SAME TASK_RUNTIME handlers with 'deprecated' descriptions naming the canonical replacement. reusable
- Durable-identity invariant when renaming a surface: `WorkflowFamily.TASK_RUNTIME.humanName` MUST stay literally 'feature-task-runtime' and `feature_task_runtime_phase` output schema `$id`/contract_version stay unchanged; only adapter names change. `feature_implement_*` family kept functional with deprecation notes (not retired).
- Parity pitfalls: every `McpToolRegistry` tool name needs a matching `telemetry-event-schema.yaml` `$defs.<camelCase>Event` branch + top-level `oneOf` `$ref` (TelemetryEventInputSchemaParityTest is bidirectional); `McpStdioServerTest.expectedToolInventory` is an exact ordered list and `priorityStrictToolNames` + zero-arg latest list must all carry canonical+alias names. Add canonical branches without bumping contract_version.
- Scope note: `FeatureTaskRuntimePhasePromptComposer` EXPERIMENTAL wording is agent-facing prompt text, intentionally left (out of help/description AC scope). Part of decomposed SKILL-67 (1/5); later subtasks reference these canonical names.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-04] SKILL-65.1 subtask 7 goal-runner-cooperation-and-continuation
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-core, runtime-kotlin/ARCHITECTURE.md
- Feature-task-runtime now accepts explicit goal-continuation context (parent issue, subtask id, goal branch, suppress PR), reuses the supplied branch, skips decompose, omits `pr`, and treats `commit_push` as terminal. reusable
- Durable runtime goal-continuation artifacts mirror implement flow: `goal_continuation`, `goal_continuation_outcome`, and skipped `install_sync_result`; stdout stays diagnostic.
- Goal-runner reconciliation now handles task-runtime workflow families, recovers missing-`RESULT:` prefix terminal JSON into durable artifacts, and records ledger diagnostics (`missing_result_prefix`, `malformed_result_json`, `no_terminal_workflow_state`, `child_process_failed`). reusable
- Testing gotcha: keep one integration-style gate proving goal-continuation branch reuse/no PR/commit terminal and direct runtime branch creation/PR phase together; separate unit tests missed this AC6 gap.
- Open-boundary gotcha: any new public raw-map recovery seam needs `@OpenBoundaryMap`, `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST`, and `ARCHITECTURE.md` parity in the same change.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-06-04] SKILL-65.1 subtask 6 lifecycle-telemetry-and-stats
Areas: orchestration/contracts, runtime-kotlin/runtime-application, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-infra-sqlite, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-ports
- Feature-task-runtime now has additive lifecycle telemetry (`feature_task_runtime_started`/`finished`) plus stats/remote-stats surfaces; per-phase records and ledger remain the source of truth. reusable
- Runtime-owned emission lives in `FeatureTaskRuntimeLifecycleTelemetry`: started is emitted at run open, finished/error derives completion status, completed phases, and phase outcomes from durable runtime phase records, never agent self-report. reusable
- Persistence mirrors implement/verify lifecycle families through `LifecycleTelemetryService` -> `LifecycleTelemetryRepository` -> `LifecycleTelemetryStore` and idempotent SQLite save/emit support for `feature_task_runtime_sessions`. reusable
- Stats pattern: add the family to MCP registry/dispatcher, CLI stats alias, `remoteStatsWorkflows`, `TelemetrySupport.mapWorkflow`, and focused MCP/CLI/runtime tests together to avoid a half-exposed surface.
- No schema migration bump; `feature_task_runtime_sessions` remains part of idempotent base schema creation. Install sync skipped by goal-continuation rule.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-04] SKILL-65.1 subtask 5 decomposition-mode-and-planning-stop
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-contracts
- Feature-task-runtime `plan` phase can now emit a `mode: decompose` terminal outcome (typed via `FeatureTaskRuntimePlanOutcome`/`FeatureTaskRuntimePlanOutcomeDecoders`) that stops the run at planning (durable status `abandoned`, not `failed`) instead of advancing to implement. reusable
- The stop writes parent `spec.md` + ordered `spec_subtask_*.md` + `decomposition-manifest.yaml` through the SHARED `FeatureSpecPreparationWriter` path (no one-off writer) and surfaces the terminal (subtask count + "work the first subtask first" guidance) in status/--monitor. reusable
- Goal-continuation runs skip decomposition via a single shared `isGoalContinuationRun` predicate (`FeatureTaskRuntimeGoalContinuation.kt`) consumed by both the runner prompt `suppressDecomposition` flag and the stopper; keep it one source of truth to avoid drift. reusable
- Resume gotcha: the decompose determination must be resume-safe. PLAN is durably persisted `completed` before the stop runs, so a PLAN-complete-on-entry resume must re-evaluate the stop (reconstructing the recorded terminal idempotently) or the run silently falls through to implement. reusable
- Crash gotcha: guard the whole decompose parse+write path into a durable Blocked. Writer business-rule rejections (duplicate/descending subtask ids, bad depends_on) throw `InvalidFeatureSpecPreparationRequestError` which extends `SkillBillRuntimeException`, NOT `IllegalArgumentException`; catch the shared base + `IOException` or an invalid package crashes `run()`. reusable
- No phase-output schema or `FEATURE_TASK_RUNTIME_CONTRACT_VERSION` bump; the decompose package rides inside the existing open `produced_outputs` under `status: completed`.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-04] SKILL-65.1 subtask 4 size-assessment-and-ceremony-scaling
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-core
- Feature-task-runtime now resolves `feature_size` once from governed spec input, defaults omitted size to `MEDIUM`, persists run invariants in workflow artifacts, and reuses the durable value on resume. reusable
- Ceremony scaling is definition-owned: SMALL maps to light preplan/current-unit review/light audit; MEDIUM/LARGE map to full preplan/branch-diff review/full per-criterion audit. Gates remain mandatory. reusable
- Prompt/briefing/status/monitor outputs all carry `feature_size`; partial-resume tests prove a stored SMALL run launches review with `current_unit_of_work` even if the resumed request proposes LARGE.
- Gotcha: explicit malformed `feature_size` must fail instead of defaulting, and malformed durable enum values must throw `InvalidWorkflowStateSchemaError`, not generic argument errors.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-04] SKILL-65.1 subtask 3 post-validate-history-commit-pr-phases
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, orchestration/contracts
- Feature-task-runtime phase DAG now runs through `write_history -> commit_push -> pr` after validate, with `pr` as `completedTerminalSummaryArtifact` and PR-derived diff context. reusable
- Phase directives delegate boundary history, commit/push, and PR creation to the per-phase agent contract; `commit_push` is the durable suppress-PR hook for goal-continuation until subtask 7 wires policy. reusable
- Workflow-state and telemetry step-id schemas mirror the new task-runtime phase ids; no phase-output schema or `FEATURE_TASK_RUNTIME_CONTRACT_VERSION` bump.
- Gotcha: test doubles parsing phase headers must accept underscores for `write_history`/`commit_push`; keep phase-list assertions derived from the runtime definition where possible. reusable
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-04] SKILL-65.1 subtask 2 preplan-phase
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-infra-fs, orchestration/contracts
- Feature-task-runtime phase DAG now starts `preplan -> plan -> implement -> review -> audit -> validate`; `PHASE_PREPLAN` is the default initial step, non-file-mutating, has exact labels/resume action/schema enum parity, and `plan` consumes the preplan output. reusable
- Prompt composer owns a preplan directive that produces a digest (scope, affected boundaries, risks/unknowns, rollout) and forbids repo edits; plan prompt explicitly uses the upstream preplan digest. reusable
- Resume gotcha: legacy five-phase records with completed `plan` but no `preplan` must invalidate/re-run plan after preplan, not skip to implement with stale upstream context. reusable
- Schema-gate gotcha: per-phase output validation must compare emitted `phase_id` with the executing source label after schema validation; otherwise a valid envelope can be persisted under the wrong runtime phase. reusable
- MCP/CLI fixtures now model coherent preplan+plan completion before implement; test doubles read phase id from the delivered prompt so retry attempts cannot emit shifted phase ids.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-04] SKILL-65.1 subtask 1 runtime-run-setup-and-feature-branch
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-ports, runtime-kotlin/ARCHITECTURE.md
- Feature-task-runtime now establishes a non-default feature branch before every file-mutating phase, persists the resolved branch in workflow artifacts, and reports it through run/resume/status/monitor output. reusable
- Branch setup blocks remain durable as `branch-setup` records until the real phase launch overwrites them, so crash recovery does not erase blocked source-of-truth or consume phase attempts. reusable
- `WorkflowGitOperations.branchExists` distinguishes absent branches from fatal git failures; resume reattaches to the persisted branch and refuses missing, protected, or unverifiable branches loudly. reusable
- Runner revalidates/reattaches before each mutating phase so a prior agent leaving HEAD on `main` cannot make later phases edit the default branch.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-03] SKILL-65 subtask 5 comparison-harness-and-promote-kill-criteria
Areas: runtime-kotlin/runtime-application, runtime-kotlin/docs, runtime-kotlin/ARCHITECTURE.md, skills/bill-feature-task-runtime (docs only), .feature-specs/SKILL-65-*, agent/decisions.md, README.md
- AC2 guardrail tests for feature-task-runtime: runtime-owned per-phase record fields (timestamps/agent-id/status asserted over runner-persisted `FeatureTaskRuntimePhaseRecord`, NOT hand-constructed values); a no-advance-without-validated-output regression over `FeatureTaskRuntimeFixLoopPolicy` Block paths (incl. the `require(currentIteration>=1)` floor and `MAX_FIX_LOOP_ITERATIONS=3` cap); and a per-phase handoff payload byte budget. reusable
- `FeatureTaskRuntimePhaseBriefingAssembler` now ENFORCES a total per-phase briefing byte ceiling (`FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING=65536`) as a runtime guarantee, not a test-side ceiling. Pattern (reusable): render once with EMPTY bodies to measure fixed overhead (layer-1 invariants never truncated + framing + a RESERVED worst-case truncation marker per upstream), `availableForBodies = CEILING - overhead`, `require` LOUD-FAILS when layer-1 alone overflows (never silently truncate governing contract), then distribute remaining budget EQUALLY across upstream bodies → guarantee is upstream-count-independent. Modeled on SKILL-64 `COMPACT_CONTINUATION_PAYLOAD_BYTE_CEILING`. reusable
- Gotcha (review F-001→F-101/F-102): a per-payload cap is NOT a total guarantee — layer-1 and upstream cardinality stay unbounded and the documented ceiling becomes a tautology over test inputs. Bound the TOTAL serialized payload and defend the count assumption in code, or the AC "stays within budget" claim is false. Byte-truncation must be UTF-8-safe via a strict REPORT decoder (drop only the partial multi-byte tail ≤3 bytes); `trimEnd('�')` wrongly strips genuine U+FFFD content. reusable
- Comparison procedure doc `runtime-kotlin/docs/architecture/feature-task-runtime-comparison.md` (same governed spec through both `bill-feature-task` and `feature-task-runtime`; captures per-phase timings, runtime-owned vs self-reported observability, resume/state-reliability, token/session cost, output-quality method). Promote/kill rule is authoritative ONLY in parent `spec.md` with a non-duplicating pointer in root `agent/decisions.md` (forbids indefinite dual-maintenance; names deciding evidence = the comparison output). ARCHITECTURE.md + README note the EXPERIMENTAL workflow family + "must not destabilize bill-feature-task"; capability stays non-default/non-auto-routed.
- Deferred follow-up: goal-continuation skipped install-sync despite skills/docs edits — run a post-goal install refresh out-of-band so generated wrappers pick up catalog/doc deltas.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-03] SKILL-65 subtask 4 cli-mcp-surface-and-experimental-skill
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs, skills/bill-feature-task-runtime
- New `feature-task-runtime` CLI group (run/status/resume) wired via `UtilityCliCommandGroup`→`TopLevelCliCommands`, mirroring the goal commands. CLI delegates only to application services (`FeatureTaskRuntimeRunner`/`FeatureTaskRuntimeStatusService`/`WorkflowService`) — spec read goes through a NEW injected port `FeatureTaskRuntimeRunInvariantsSource` (+ `FileSystemFeatureTaskRuntimeRunInvariantsSource` adapter, DI in RuntimeComponent), never raw `Files.`/infra in CLI, so `RuntimeArchitectureTest` stays green. reusable
- 7 task-runtime MCP tools registered in `McpToolRegistry` + dispatched in `McpToolDispatcher` following the `feature_implement_workflow_*` pattern; golden `mcp-feature-task-runtime-workflow.json` + tool-name locks in `McpStdioServerTest`. Gotcha (review F-002): the `_continue` tool's content.md path must be DEFINITION-DRIVEN (TASK_RUNTIME→skills/bill-feature-task-runtime/content.md), not reuse verify prose, since TASK_RUNTIME has no continuationReferenceSections. reusable
- Gotcha (review F-001): runtime status `blocked` must be DERIVED from the append-only phase ledger via a `loadPhaseLedger` read seam, not inferred from records — symmetric with subtask-2/3 strict ledger reads. `_latest` intentionally omitted from priorityStrictToolNames; `_latest` strict-schema parity test added (F-004). reusable
- Thin experimental skill `skills/bill-feature-task-runtime/content.md` mirrors the `bill-feature-goal` thin-skill pattern: gather/confirm spec → single confirmation gate → launch `skill-bill feature-task-runtime`. Explicitly marked experimental + "not a default path"; does NOT re-implement phase orchestration in prose and does NOT reference/alter `bill-feature-task`. reusable
- Per-phase agent assignment + optional `timeout:Duration?` threaded CLI→`FeatureTaskRuntimeRunRequest`→runner; timeout validated at clikt boundary (`restrictTo min=1`). Per-phase timeout semantics are intended/documented. Deferred follow-up: run-ownership/concurrency guard (F-003, Major, stated subtask-4 non-goal). Install sync skipped (goal-continuation) despite skills/ source edit — refresh out-of-band after the goal run.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-02] SKILL-65 subtask 3 runtime-phase-loop-runner
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-core
- New `FeatureTaskRuntimeRunner` (runtime-application): deterministic loop over `FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds` (plan→implement→review→audit→validate). Reuses `GoalRunnerSubtaskLauncher` port + `WorkflowEngine` + the subtask-1/2 recorder & validator — no new process/CLI adapter, no second orchestration loop. Mirror `GoalRunner` shape (collaborators, fake-launcher tests). reusable
- Activated subtask-2's dead recorder write seam: the runner is the production caller of `recordPhaseState`/`appendLedgerEntry`, guarded by an emit→store→read test. When wiring a previously-uncalled persistence seam, add the end-to-end readback test in the same change or it silently stays dead. reusable
- Briefing must be PERSISTED, not computed-and-discarded: the launcher port carries only issueKey/repoRoot/dbPath, so the assembled three-layer handoff is persisted durably per phase (new `recordPhaseBriefing`/`loadPhaseBriefings`, `FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY`) for the agent to re-read — same "agent re-reads durable state" model as GoalRunner. New `@OpenBoundaryMap` briefing seam needs the three-place lockstep (RuntimeArchitectureTest inventory + ARCHITECTURE.md ×2). reusable
- Gotcha (review): per-phase records/briefing READ paths must use a strict shared `decodeStrictKeyedArtifactMap` (loud-fail on present-but-non-map blob / non-String key / non-map value), symmetric with the subtask-2 ledger read — best-effort silent-drop on records corrupts resume (re-runs completed phases / vanishes upstream outputs). reusable
- Gotcha (review): launch/infra failures (timedOut/spawnFailed/interrupted/non-zero exit/unsupported-launch) must reconcile to a DISTINCT infra-failure block BEFORE the schema gate (mirror `GoalRunnerOutcomeReconciler`), else they get laundered as schema-invalid output and burn the bounded fix-loop budget. Fix-loop cap `MAX_FIX_LOOP_ITERATIONS=3` (1 initial + 2 re-runs). reusable
- Missing required upstream loud-fails by comparing resolved keys vs `declaration.consumedUpstreamPhaseIds` (resolveUpstreamOutputs OMITS missing deps — never launch blind). Known deferred Minor: resume re-grants fix-loop budget after a mid-RUNNING crash because `nextIteration` ignores persisted `attemptCount`. Install sync skipped (goal-continuation); only runtime-kotlin touched.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-06-02] SKILL-65 subtask 2 runtime-phase-state-persistence
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-sqlite, runtime-kotlin/runtime-domain, orchestration/contracts
- New `TASK_RUNTIME` `WorkflowFamily`/`WorkflowFamilyKind` (bound to `FeatureTaskRuntimePhaseWorkflowDefinition.definition`) wired through `WorkflowService` save/get/list/latest; `sessionSummary` returns `emptyMap()` for the family (no session table). IMPLEMENT/VERIFY storage untouched. reusable
- `WorkflowStateRepository` split into per-family sub-interfaces and `WorkflowStateStore` into delegated implementers (`by`) to add the family without tripping detekt `TooManyFunctions` — reorganize, don't suppress. New sibling `feature_task_runtime_workflows` table is created idempotently in `createBaseSchema` + `tableNames` (NO `schema_migrations` bump; column migrations only add columns to existing tables). reusable
- Per-phase records + append-only attempt/event ledger (actions start/resume/retry/fix_loop_iteration/blocked/complete) live INSIDE `artifacts_json` (no new table), mirroring SKILL-64 GoalAttemptLedger via `appendBoundedHistoryBySequence`; effect-free runtime-domain models, timestamps/durations minted only in the application `FeatureTaskRuntimePhaseRecorder` via `Instant.now()` (never agent-reported). reusable
- Gotcha (caught in review): the ledger READ path must be strict — best-effort `as? List`/`mapNotNull` decode silently drops malformed entries AND lets a malformed `sequence_number` coerce to 0 and rewind the monotonic watermark. Decode through typed `fromArtifactMap`, loud-fail on present-but-non-list, seed the watermark from typed `sequenceNumber` fields. reusable
- Any new `workflow-state-schema.yaml` `oneOf` branch needs a schema↔definition enum parity test in `WorkflowStateSchemaContractVersionTest` (statuses + current_step_id + steps[].step_id, both directions) or drift only surfaces as a production validation rejection; also cross-pin the table `contract_version` default to `WORKFLOW_STATE_CONTRACT_VERSION`. reusable
- Recorder write seam has no production caller yet (Subtask 3 wires it) — guard with an end-to-end emit→store→read test so it isn't a dead seam. Install sync intentionally skipped (goal-continuation); only runtime-kotlin + orchestration/contracts touched, no skills/ source.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-02] SKILL-65 subtask 1 phase-workflow-definition-and-handoff-contract
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-core, orchestration/contracts
- New `FeatureTaskRuntimePhaseWorkflowDefinition` (object) in new package `skillbill.workflow.taskruntime`, fully independent of `FeatureImplementWorkflowDefinition` (untouched): reuses the shared `WorkflowDefinition` model with its own `workflowIdPrefix=wftr`, ordered stepIds plan→implement→review→audit→validate, and `requiredArtifactsByStep` encoding the phase DAG. reusable
- Three-layer handoff contract (`FeatureTaskRuntimeHandoffModels.kt` + `FeatureTaskRuntimeHandoffContract.kt`): layer-1 run-invariants are required non-null with `init{} require()` loud-fail (blank specRef / empty / blank acceptance criteria) so absence can't be silent; layer-2 declared upstream outputs resolved by `selectLatestOutputsByPhase` using `iteration >= existing` (order-independent for distinct iterations, ties keep last-appended) to support fix loops; layer-3 derived context static per-phase (`diff` for review). reusable
- Per-phase output schema validation mirrors the DecompositionManifest validator family file-for-file: domain port (`FeatureTaskRuntimePhaseOutputValidator`, String-based `validatePhaseOutputText` — deliberately NOT a raw `Map<String,Any?>` arg, to avoid the @OpenBoundaryMap scanner + ARCHITECTURE.md three-place lockstep); concrete validator in runtime-infra-fs keeping package `skillbill.contracts.workflow`; `@Inject` adapter in `skillbill.infrastructure.fs`; DI via RuntimeComponent `@Provides @JvmSynthetic internal`. reusable
- Contract plumbing: `FEATURE_TASK_RUNTIME_CONTRACT_VERSION` + `FeatureTaskRuntimePhaseOutputSchemaPaths` (Path-free) in runtime-contracts; canonical schema authored once at `orchestration/contracts/feature-task-runtime-phase-output-schema.yaml` (Draft 2020-12, strict additionalProperties, `contract_version.const`, 5 required envelope fields so `{}` fails); config-cache-safe Gradle Copy task + `*SchemaContractVersionTest` parity test. reusable
- Gotcha: a new composition-root `@Provides` port import trips `ImplementationOwnershipArchitectureTest.allowedCompositionImports` — extend that allow-list (not a suppression) the same as the sibling validator ports. New typed error `InvalidFeatureTaskRuntimePhaseOutputSchemaError` joins the ShellContentContract family. Install sync intentionally skipped (goal-continuation); no skill source touched.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-02] SKILL-64 subtask 4 validation-docs-payload-budgets
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, orchestration/workflow-contract, docs, skills/bill-feature-task
- Locks the SKILL-64 compact-payload contract with regression tests + named byte ceilings: `COMPACT_CONTINUATION_PAYLOAD_BYTE_CEILING=8192` and `COMPACT_UPDATE_ACK_PAYLOAD_BYTE_CEILING=1024` sit between compact (~2KB) and full (~20KB) shapes so a reintroduced full snapshot trips the assertion; the load-bearing guards are the byte ceiling + raw-body substring leak checks (structural assertFalse on `step_artifacts`/`artifacts` is inert because the compact view type has no such field). reusable
- Ledger/accounting coverage drives the real emit→store→read seam end-to-end through `GoalRunner.run` (the dead-seam guard), not the model in isolation: one test per `GoalAttemptLedgerAction` (child_activation/resume/retry/terminal_done_check/timeout/interruption/policy_block/final_reconciled_outcome) asserting explanatory fields (blocked_reason/stop_reason/final_reconciled_result) so the ledger explains every case with no provider JSONL scraping. reusable
- `launchFacts(interrupted=…)` test helper gained an interrupted branch (exitStatus null) to reach the INTERRUPTED stop reason; transition-only monitoring proven by raising `heartbeatChatterCount` and asserting goal_event count < heartbeat count with a distinct ≥20000 sequence space.
- Docs: PLAYBOOK.md gained the continue(mutating)-vs-show(read-only) contract, the full attempt-ledger field reference, and the cached-input-token caveat framed explicitly as NOT a Skill Bill contract; mirrored in docs/getting-started.md, README.md, and bill-feature-task/content.md. AC8 stays a caveat, never a billing/contract guarantee.
- AC10 goldens (mcp/cli workflow-show + verify) were already compact/full-distinct and intentionally named, so no regeneration was needed. Install sync intentionally skipped (goal-continuation mandate) after editing skills/* + docs; refresh local installs out-of-band after the goal run.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-06-02] SKILL-64 subtask 3 goal-runner-integration
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-cli, orchestration/contracts, skills/bill-feature-goal
- Goal-runner liveness is now deterministic from declared, durable, monotonic `GoalProgressEvent`s + process liveness; the legacy mtime/stdout idle heuristic survives only as a fallback when no declared event exists. Taxonomy `working/progressing/idle/unresponsive` lives in a pure domain classifier. reusable
- Declared progress is emitted SUPERVISOR-side: `JvmAgentRunProcessRunner` ProcessLifecycleEmitter emits operation_started/heartbeat(gated to status-heartbeat cadence, not per-250ms-tick)/completed from the child process lifecycle (no phase-agent self-report), persisted via `GoalRunnerProgressEventEmitter`→`recordProgressEvent` and read back next tick by `declaredProgressProbe`. reusable
- Dead-seam trap: a write seam with no production caller passes unit tests but is dead in prod — completeness audit caught `recordProgressEvent` having zero `src/main` callers; guard wiring with an end-to-end test that drives emit→store→probe in ONE run. reusable
- Patterns reused: distinct watermark-seeded sequence space per stream (progress vs observability vs ledger vs accounting, seeded from persisted max so resumes stay monotonic); schema validator wired at the durable write seam via a runtime-domain port + DI (mirror GoalObservabilityEventValidator); single-source `appendBoundedHistoryBySequence` retention; best-effort writes log-but-never-fail; operation deadline anchored to FIRST operation observation (incl. heartbeat), never process start. reusable
- New `goal_event:` transition stream (stable prefix + key=value, monotonic, meaningful-change-only); invoking-agent child defaulting order (--agent-override > --agent > SKILL_BILL_AGENT > detected context > documented last-resort); new `goal-progress-event-schema.yaml` follows the 6-step contract recipe.
- Install sync intentionally skipped (goal-continuation mandate) after editing skills/* and runtime source; refresh local installs outside continuation if generated output needs updating.
Feature flag: N/A
Acceptance criteria: 25/25 implemented

## [2026-06-02] SKILL-64 subtask 2 compact-workflow-update-acks
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, orchestration/workflow-contract
- `workflow update` now returns a typed compact acknowledgement by default (status, workflow_id, workflow_name, workflow_status, current_step_id, updated_step_ids, updated_artifact_keys, db_path) instead of a full snapshot; `workflow show`/`get` stay the read-only full-state path. reusable
- The acknowledgement is produced only after `WorkflowEngine.validateUpdate` passes, the transaction saves, and the service re-reads persisted state — never from stdout or an unpersisted projection; loud-fail validation still precedes projection. reusable
- Domain owns the transport-neutral acknowledgement view (carries `workflow_name`, no presentation fields); CLI/MCP adapters render `read_only_full_state_command`/`db_path` with the resolved `--db` path, mirroring the subtask 1 compact-continue pattern. reusable
- MCP goldens (`mcp-feature-implement-workflow.json`, `mcp-feature-verify-workflow.json`) deliberately updated to the compact default; added CLI runtime + service + CLI/MCP mapper coverage asserting compact shape and unchanged read-only full-state.
- Install sync intentionally skipped during goal-continuation after editing skills/bill-feature-task/content.md; refresh local installs outside continuation if generated output needs updating.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-02] SKILL-64 subtask 1 compact-continuation-contract
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, docs, skills/bill-feature-task
- `workflow continue` remains the mutating activation/reopen path, but default CLI/MCP output now uses compact continuation fields instead of full snapshots/artifacts; `workflow show` is the read-only full-state fallback. reusable
- Compact current-step artifact summaries inline small required artifacts and omit/truncate large or non-current artifacts with metadata; prompts must reference `current_step_artifacts` and send omitted keys to `workflow show`. reusable
- CLI/MCP adapters render `read_only_full_state_command` with the resolved `--db` path; domain owns only the typed compact view and loud-fail validation still happens before projection.
- Install sync was intentionally skipped during goal-continuation; refresh local installs outside continuation if governed generated output needs updating.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-01] SKILL-63 subtask 3 add-on-skeleton-wizard
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-desktop, docs
- Normal add-on creation now creates an editable skeleton instead of asking for body text; CLI/desktop wizard payloads omit body and raw consumer dirs, and scaffold notes point users to edit the generated add-on file. reusable
- Omitted add-on consumers default to the pack baseline, then a single declared non-baseline skill dir; packs with no unambiguous default loud-fail before mutation and scripted `consumer_skill_dirs` remains the advanced deterministic path. reusable
- Explicit scripted `body` is treated as present even when blank, while omitted body renders the TODO skeleton; invalid consumer tests snapshot the whole repo tree to lock atomic rejection.
- Install sync was intentionally skipped during goal-continuation; refresh local installs outside continuation if generated runtime output needs updating.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-06-01] SKILL-63 subtask 2 full-platform-pack-contract
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-desktop, docs
- `platform-pack` scaffolding now always emits the full baseline/default-quality-check/all-approved-specialist contract; `skeleton_mode` and `specialist_areas` are retired creation selectors at CLI, MCP, raw payload, and desktop seams. reusable
- Full-pack generation reuses manifest-driven approved areas, baseline native-agent composition, declared file metadata, and loud policy errors instead of preserving partial-pack branches.
- Desktop wizard state/model/request mapping no longer carries pack skeleton or specialist selectors; docs/examples now show the full-platform-pack contract and mark old selectors as rejected.
- Install sync was intentionally skipped during goal-continuation; refresh local installs outside continuation if governed generated output needs updating.
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-06-01] SKILL-63 subtask 1 scaffold-kind-surface
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-desktop
- Partial scaffold creation kinds (`platform-override-piloted`, `code-review-area`, and wizard aliases) are now retired at creation seams through typed `RetiredScaffoldKindError`, while legacy constants/models remain for existing source compatibility. reusable
- Active creation kind lists now split from legacy supported-kind lists; adapters, CLI wizard prompts, assisted mode, desktop menus, and command palette must use active creation values only. reusable
- Existing platform override/code-review-area source discovery, render, validation, install-plan discovery, and removal paths stay intact; do not delete legacy planning/source helpers just because creation is blocked.
- Install sync was intentionally skipped during goal-continuation; future local refresh should happen outside workflow continuation.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-06-01] SKILL-62 install-sh-reuse-last-selection
Areas: install.sh, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-kotlin/runtime-core tests
- `install.sh --reuse-last-selection` now resolves the latest shared install selection before cleanup, skips agent/platform/telemetry/MCP prompts, rejects desktop-only reuse, and reports reused selections in the install summary. reusable
- Added a runtime-owned `install replay-last-selection` CLI seam so shell replay uses the shared selection parser, current agent target resolution, manifest-backed platform slug validation, and a fresh current-install MCP binary path. reusable
- `InstallService.discoverPlatformPackSlugs` exposes platform manifest discovery without pulling filesystem or planning-port internals into the CLI boundary.
- Regression coverage locks happy-path replay, missing/malformed records, stale selected platform slugs, desktop-only rejection, cleanup-before-failure behavior, and usage/delegation wiring.
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-06-01] SKILL-61 subtask 3 cli-watch-status-diff-ux
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs
- Goal foreground output now emits default `goal_observability:` lines at the existing bounded heartbeat cadence while keeping raw child stdout/stderr hidden unless `--debug-child-output` is explicit. reusable
- `goal status` and read-only `goal watch` render latest durable observability, optional diff stat, and explicit selected diff hunks as stable line-oriented records; watch refreshes status without launching child implementation runs. reusable
- Selected diff hunk support is routed through `WorkflowGitOperations` with shared hunk/line/byte budgets across staged and unstaged reads; git output is drained asynchronously and huge lines are truncated within bounds to avoid pipe stalls. reusable
- CLI tests now assert help/cost copy, diff-hunk option propagation, raw-output filtering, watch no-launch behavior, and large-output git drain regressions.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-06-01] SKILL-61 subtask 2 runtime-supervisor-worker-boundary
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-core
- GoalRunner now emits lifecycle observability for subtask start/resume, phase/progress, heartbeat, file activity, worker output summaries, block, completion, and failure while keeping workflow-store state authoritative for active subtask, step, checkpoint, and terminal status. reusable
- Added a structured worker-subtask request parser and typed outcomes (`accepted`, `queued`, `rejected`, `requires_operator_confirmation`); free-form child text remains diagnostic and never schedules work directly. reusable
- Accepted worker-requested work appends runtime-visible sibling subtasks through existing decomposition manifest state; request audit persistence is written before manifest mutation so failures do not create hidden partial state.
- Status/reset/completion paths reconcile latest observability without destructive status reads and clear or terminal-mark stale active-worker state, preserving SKILL-58 stale-running hygiene.
Feature flag: N/A
Acceptance criteria: 7/7 implemented (subtask scope)

## [2026-06-01] SKILL-61 subtask 1 goal-observability-event-contract
Areas: orchestration/contracts, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-core
- Added a schema-backed `goal_observability_latest_event` / bounded `goal_observability_run_history` contract in workflow artifacts; the event carries issue/subtask, phase, worker role, liveness, activity, timestamp, sequence, changed-file summary, diff stat, and optional heavy file fields. reusable
- Domain parsing now loud-fails malformed durable records through `GoalObservabilityEventValidator`; CLI/MCP workflow rendering must use the concrete `WorkflowService` validator, not `NoopGoalObservabilityEventValidator`, so schema-only failures are caught at read/render seams. reusable
- Goal status projection exposes a compact latest observability event beside the legacy liveness string while keeping workflow artifacts as the authoritative storage location; history retention is capped at 50 events.
- Architecture guardrails document the observability raw-map schema seams as explicit `@OpenBoundaryMap` exceptions and keep the concrete validator adapter in infra-fs via the runtime-core composition root.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-05-31] SKILL-59 subtask 2 spec-writing-runtime
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-application, runtime-kotlin/runtime-core
- Added typed feature-spec write models (`FeatureSpecWriteRequest`, `FeatureSpecWriteResult`, `FeatureSpecSubtaskPreparation`) so single-spec/decomposed persistence can be shared by future `bill-feature-spec`, `bill-feature-task`, and `bill-goal` callers through one runtime contract. reusable
- Added typed loud-fail `FeatureSpecPreparationModeConflictError`; `single_spec` now fails immediately when a decomposition manifest already exists for the same issue/feature directory instead of silently mixing modes. reusable
- Added `FeatureSpecPreparationWriter` as the shared file-writing seam: `single_spec` writes/updates `.feature-specs/<issue>-<feature>/spec.md` and returns the `bill-feature-task` path without creating `decomposition-manifest.yaml`; `decomposed` writes parent + ordered subtask specs and reuses `DecompositionManifestWriter`/`DecompositionManifestValidator` seams for manifest serialization/validation. reusable
- Regression coverage now locks single-spec write/no-manifest behavior, conflict loud-fail, decomposed subtask spec content contract, schema-valid manifest emission, and goal-status import readability from checked-in decomposition projection (`FeatureSpecPreparationWriterTest`, `FeatureSpecPreparationWriterValidationTest`).
Feature flag: N/A
Acceptance criteria: 8/8 implemented (subtask scope)

## [2026-05-31] SKILL-58 subtask 4 runtime-consistency-and-contract-validation
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-core, install.sh
- Goal startup now emits runtime provenance (`executable`, `version`, `build_id`) through a dedicated `RuntimeProvenanceService` and `RuntimeProvenanceContract`, keeping path/system probing out of presenter code. reusable
- Installer delegation now exports `SKILL_BILL_RUNTIME_EXECUTABLE` when invoking runtime-cli, so installed-path runs report explicit provenance without changing goal heartbeat/completion semantics. reusable
- Added dual-path parity guard (`GoalRuntimeDelegationParityTest`) that normalizes path variance and fails loudly if repo-local vs installed runtime diverge on goal status/progress semantics. reusable
- Extended regression coverage for status/progress reconciliation and provenance output (`GoalRunnerTest`, `WorkflowServiceTest`, `CliGoalRuntimeTest`, runtime-contract tests).
Feature flag: N/A
Acceptance criteria: 5/5 implemented (subtask scope)

## [2026-05-31] SKILL-58 subtask 3 operator-progress-ux-and-completion-confirmation
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-cli tests, runtime-kotlin/runtime-application tests
- Goal foreground default output now emits structured heartbeat lines (`issue_key`, `subtask`, `step`, `liveness`) while hiding raw child stdout/stderr unless `--debug-child-output` is explicitly enabled. reusable
- Added lightweight liveness classification at the CLI boundary with the contract values `durable_progress`, `file_activity`, `output_only`, `idle`, derived from workflow-progress/status-heartbeat streams and raw-output observation. reusable
- Goal completion reporting now carries authoritative summary fields (`subtasks_completed`, `subtasks_pending`, `subtasks_blocked`, `pull_request_status`) from `GoalRunnerRunReport.Completed` through CLI payload/text output, and the live terminal line is an explicit single completion confirmation. reusable
- Regression coverage now asserts default-mode heartbeat formatting + raw-output suppression and verifies completion summary fields and PR status wiring.
Feature flag: N/A
Acceptance criteria: 3/3 implemented (subtask scope)

## [2026-05-30] SKILL-58 subtask 2 durable-reconciliation-and-stale-running-hygiene
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application tests, runtime-kotlin/runtime-cli tests
- Added `GoalRunnerWorkflowOutcomeStore.reconcileAuthoritativeOutcomes(...)` with explicit inactive-row gating (`allowInactiveReconciliation`) so goal-run finalization can close stale inactive `running` children while status reads avoid destructive inactive cleanup. reusable
- `GoalRunnerStatusService.status` now reconciles against refreshed manifest snapshots before save and protects active retries from being overwritten by non-complete sibling outcomes; complete sibling outcomes may still authoritatively supersede stale blocked/in-progress projection. reusable
- Workflow-store authoritative selection now uses deterministic ordering by snapshot recency/workflow id and complete-first semantics for sibling continuations, avoiding unstable iteration-order behavior. reusable
- Added regression coverage for: active child not blocked without terminal outcome, stale-running reconciliation with authoritative complete sibling, active retry preserved when blocked sibling exists, and CLI status parity for authoritative complete vs stale-running child. reusable
Feature flag: N/A
Acceptance criteria: 2/2 implemented (subtask scope)

## [2026-05-30] SKILL-57 subtask 2 feature-implement-phase-heartbeats
Areas: skills/bill-feature-task authored workflow contract, runtime-kotlin/runtime-domain continuation definitions, runtime-kotlin/runtime-mcp golden continuation payloads, runtime-kotlin/runtime-infra-fs workflow/launcher tests
- Added a governed `Durable Progress Write Contract` to `bill-feature-task` and threaded it through heavy phase (`preplan`, `plan`, `implement`, `audit`, `validate`, `pr_description`) subagent briefings with required phase/task/heartbeat/phase-complete write points and explicit `workflow_id`/`step_id`/`attempt_count` context. reusable
- Progress writes are now explicitly best-effort-but-visible in authored contracts: each heavy phase result includes `progress_write_failures`, and briefings require stopping for orchestrator-level blocking when reliable writes cannot continue. reusable
- Continuation payload guidance now includes durable progress instructions for heavy phases by adding `content.md :: Durable Progress Write Contract` reference sections and step directives that call out resumed-attempt progress writes per step. reusable
- Updated continuation golden payload and runtime tests to lock the new progress-guidance wording; tightened launcher live-output assertion to tolerate chunked stream writes without weakening behavior coverage. reusable
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-05-30] SKILL-57 subtask 1 continuation-prompt-and-parent-lineage-hardening
Areas: runtime-kotlin/runtime-application (decomposition workflow lookup), runtime-kotlin/runtime-infra-fs launcher prompt contract, related runtime tests
- Goal-continuation child workflows now opt out of decomposed-parent lookup even when their artifacts still include `plan.mode=decompose`; parent issue-key continuation selects true parent lineage instead of accidentally reselecting a child workflow record. reusable
- The launcher continuation prompt contract now hard-requires JSON workflow continuation (`skill-bill ... workflow continue ... --format json`) and explicitly forbids using workflow-update as a synthetic blocked marker; durable `continue_status=blocked|done` is authoritative and must terminate the run. reusable
- Regression tests lock both behaviors: workflow-service coverage for child-vs-parent lineage selection and launcher command/prompt assertions for `--format json` plus the non-forced-blocking instruction text.
Feature flag: N/A
Acceptance criteria: 3/3 implemented (subtask continuation fix scope)

## [2026-05-30] SKILL-56 subtask 4 cli-and-bill-goal-skill
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-core, skills/bill-goal
- Added the foreground `skill-bill goal` command and read-only `goal status` surface over the subtask-3 `GoalRunner`/status projection; CLI remains a thin adapter with text/payload mapping only. reusable
- Live child stdout/stderr teeing is a narrow `AgentRunOutputSink` carried through request models into infra-fs `JvmAgentRunProcessRunner`; captured bounded output remains intact and process IO stays outside application/domain. reusable
- `GoalRunnerRunEvent` gives the CLI readable per-subtask progress without parsing workflow internals or changing loop semantics; stop reports still use workflow-store outcomes as authority.
- Added canonical `skills/bill-goal/content.md` plus README catalog row; source remains content.md-only and install/render sync produced agent links without committing generated wrappers.
- E2E evidence uses fake `codex`/`gh` executables to exercise the real foreground driver, fresh process per subtask, manifest advancement, final PR call, and forced-failure stop report; it does not claim hosted-agent/GitHub coverage.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-05-30] SKILL-56 subtask 3 goal-runner-service
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-core
- Added `GoalRunner` as the parent loop over decomposed manifests: select dependency-ready subtasks, launch one fresh agent process per subtask, reconcile process facts with workflow-store outcomes, and advance manifest runtime state. reusable
- Goal-runner success is workflow-store authoritative: completed PR-suppressed subtask workflows must expose `commit_push_result.commit_sha`; stdout remains diagnostic only and missing terminal state blocks the parent.
- Added typed goal-runner ports for manifest state, workflow outcome reads, subtask launching, and final goal PR creation; domain policy stays pure while application adapts to launcher/workflow/PR ports. reusable
- Added status projection for complete/pending/blocked counts, current subtask/step, and active agent so the CLI front can render status without re-parsing workflow internals. reusable
- Known limitation: CLI/bill-goal entrypoint and human progress rendering remain subtask 4; this subtask wires service/composition and the gh-backed PR port only.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-05-30] SKILL-56 subtask 2 agent-agnostic-launcher
Areas: runtime-kotlin/runtime-ports, runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-core
- Added `AgentRunLauncher` as a technology-neutral port with typed launch request/outcome models; launcher outcomes are process facts only and never infer subtask success from stdout. reusable
- Added `AgentRunService` to resolve configured override before invoked `bill-goal` agent via `InstallAgent`; unknown agents fail before launch and supported agents without headless paths return explicit unsupported outcomes.
- Infra-fs owns fresh-process spawning, command construction, environment inheritance, bounded UTF-8 stdout/stderr capture, timeout destroy+wait, and spawn-failure mapping; keep all process/env work out of application/domain/ports. reusable
- Headless adapters currently cover Claude, Codex, and OpenCode; Copilot and Junie are deliberately unsupported until a proven headless skill-run path exists.
- RuntimeComponent exposes the service and architecture/surface tests lock the composition-only binding plus launcher operation surface.
Feature flag: N/A
Acceptance criteria: 7/7 implemented (subtask scope)

## [2026-05-29] SKILL-56 subtask 1 headless-continuation-contract
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, orchestration/contracts, skills/bill-feature-task
- Added issue-key goal continuation for decomposed feature-implement parents with optional `subtask_id` constraint; domain selector now has a terminal-subtask outcome so retrying a completed requested subtask is idempotent while later work remains. reusable
- New subtask workflows start at `preplan` with `assessment`/`branch`/`goal_continuation` artifacts already persisted; interactive feature-implement still owns confirmation and PR creation.
- Goal-continuation outcomes are typed in application results and mapped to stable CLI/MCP wire fields: `issue_key`, `subtask_id`, `status`, `commit_sha`, `workflow_id`, `blocked_reason`, `last_resumable_step`. reusable
- PR-suppressed completion treats completed `commit_push` plus nonblank `commit_push_result.commit_sha` as terminal success; missing commit SHA blocks loudly instead of duplicating later commit advancement.
- MCP `feature_implement_workflow_continue` now accepts strict integer `subtask_id`; telemetry event schema mirrors the registry input schema to keep parity tests green.
- Headless exercise recorded in the subtask spec: installed runtime must be refreshed before launcher adapters depend on new options; durable workflow state is authoritative, not stdout or git-tracked manifest projections.
Feature flag: N/A
Acceptance criteria: 7/7 implemented (subtask scope)

## [2026-05-29] SKILL-55 subtask 2 desktop-app-installers
Areas: runtime-kotlin/runtime-desktop, runtime-kotlin/build-logic/convention, runtime-kotlin/agent/decisions.md, runtime-kotlin/README.md
- Turned the existing Compose `nativeDistributions` (`packageDmg/Msi/Deb/Rpm`) into canonically-named, checksummed per-OS installers. jpackage AND the Compose Dmg validator (eager at config time) reject qualified/non-numeric versions, and macOS requires MAJOR>=1: derive a jpackage-legal `MAJOR.MINOR.PATCH` from `project.version` via the new pure `dev.skillbill.runtime.buildlogic.toJpackageVersion` (strips `-SNAPSHOT`/qualifier, pads, leading-digit run per component), plus a macOS-only `toMacAppVersion` that bumps a zero major to 1 (`0.1.0`->`1.1.0`). reusable
- The canonical artifact FILENAME (`SkillBill-<full project.version>-<os>-<arch>.<ext>`) uses the un-stripped version uniformly across all OSes and is the SINGLE source of truth for subtask 3/4 resolution; the macOS *embedded* `--app-version` deliberately diverges (`1.1.0`). Recorded as a dated decision in `agent/decisions.md` — resolve on the filename, never embedded metadata.
- The SHA-256 sidecar contract (`<hex>  <name>\n`, 64KiB streaming buffer) is now ONE shared build-logic helper `Sha256Sidecar.kt` consumed by BOTH `RuntimeImageConventionPlugin` (image zips) and `runtime-desktop` (installers); hoisted from copy-paste during code review (F-001) so the format stays byte-identical across artifact kinds. reusable
- Installer naming reuses subtask 1's `resolveHostRuntimeToken` token contract (no duplicate os/arch detection); the rename+sha256 Gradle tasks mirror subtask 1's pattern: per-task `notCompatibleWithConfigurationCache`, all paths resolved to Strings OUTSIDE `doLast`, null host token = config-time lifecycle log + execution-time `GradleException` (never config-time `error()`). reusable
- build-logic is a SEPARATE included build: its first test source set needs an explicit `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` (not added implicitly) and `compileTestKotlin`+`Test` opted out of the config cache (Kotlin 2.4.0-Beta2 + Gradle 9.3 serializes the Kotlin daemon error-file/profile as a File where a Property is expected) — main-source compile is unaffected, global config cache stays warm. reusable
- Pitfall: jpackage cannot cross-compile — locally only the Linux host's `.deb`/`.rpm` are reproducible, and a host without `dpkg-deb`/`rpmbuild` cannot build them at all (verified app-image + bundled JRE under `lib/runtime` + 5 staged `skill-bill-runtime/*` subdirs instead; GUI launch not exercised headless); `.dmg`/`.msi` build on CI (subtask 3). From-source `prepareDesktopAppDistributable` / `install.sh --with-desktop-app` path preserved unchanged.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-05-29] SKILL-55 subtask 1 self-contained-runtime-images
Areas: runtime-kotlin/build-logic/convention, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/gradle/libs.versions.toml, runtime-kotlin/README.md
- Self-contained, no-JDK per-OS images of `runtime-cli`/`runtime-mcp` via the Badass **Runtime** plugin (`org.beryx.runtime` 2.0.1), NOT Badass JLink (`org.beryx.jlink`): JLink hard-requires a `module-info.java` and cannot link these non-modular Kotlin apps. Badass Runtime wraps the existing `application` installDist, so installDist + the desktop bundling consumer (`runtime-desktop:95`) are untouched and `bin/runtime-cli`/`bin/runtime-mcp` launchers are preserved (the symlink targets subtask 4 consumes). reusable
- All image wiring lives in ONE class-based convention plugin `skillbill.runtime-image` (`RuntimeImageConventionPlugin`) with a typed `RuntimeImageExtension` (`imageBaseName`, `runtimeTargetTokens`, `hostRuntimeToken: String?`); consumers just `apply` it and set `imageBaseName`. This replaced verbatim per-module duplication and a stringly-typed `apply(from=)+extra[...]` script. Pattern: hoist cross-cutting Gradle image/packaging wiring into a build-logic convention plugin + typed extension rather than duplicating `runtime{}` blocks; subtasks 3/4 consume the typed token contract. reusable
- Non-modular jlink needs an explicit `additive` module set (jdeps can't resolve kotlin-inject/kotlinx.serialization automatic modules). `java.net.http` MUST be included — the telemetry HTTP client (`runtime-infra-http`) uses it and the `version`/stdio smoke test never exercises that path, so a missing module would only crash at first telemetry call. reusable
- Badass Runtime tasks are NOT configuration-cache compatible: opt every `org.beryx.runtime.BaseTask` (and the plain sha256 sidecar task) out via `notCompatibleWithConfigurationCache`; the entry is discarded for image builds while `check`/`installDist` keep a warm config cache. Keep the link toolchain a lazy `Provider` (don't `.get()` at config time) so unrelated builds don't provision JDK17. reusable
- Unsupported-arch hosts (e.g. arm64 Linux) are an OPTIONAL known-gap target: `resolveHostRuntimeToken()` returns null and logs, NOT `error()` at config time — a config-time throw would block `./gradlew check`/IDE sync on that arch. Image tasks fail loudly only when actually invoked on an unsupported host. reusable
- Archive names derive from `project.version` + a canonical `<os>-<arch>` token (`{macos-arm64,macos-x64,windows-x64,linux-x64}`) with a `.sha256` sidecar; build outputs stay under `build/`. Cross-OS jlink can't cross-compile: only the host (linux-x64) image is locally reproducible; macOS/Windows are CI builds (subtask 3). Verified locally: clean-env (`env -i`, no JDK) `runtime-cli version` runs, `java.net.http` present in image, MCP telemetry schema bundled in the image jar.
Feature flag: N/A
Acceptance criteria: 7/7 implemented (subtask scope)

## [2026-05-29] SKILL-52.3 subtask-5 enforcement-hardening-and-final-lock
Areas: runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md, runtime-kotlin/agent/decisions.md
- Added a source-TEXT inline-FQN ban (complements the import-only guard) over runtime-application/-domain/-ports for adapter/infra/composition prefixes: catches `skillbill.infrastructure.fs.Foo()` written with NO import. Scanner skips `import`/`package` lines and matches each prefix as a dotted token `\bprefix\.`; cross-layer prefixes (skillbill.application/ports) are deliberately excluded to avoid same-layer false positives. Synthetic-fixture positive control included. reusable
- AC2: added `runtime-desktop/core/data/src/jvmMain/kotlin` + `feature/skillbill/src/jvmMain/kotlin` to `RuntimeArchitectureTest.sourceRoots` so the central import/raw-map scanners walk the desktop gateway source (both verified clean: only skillbill.*.model[.command]/application/ports/error/di/model imports). reusable
- AC3: the `*SchemaValidator`/`*CoherenceValidator` import ban predicate now lives in `RuntimeImplementationImportRules.kt` as `isSchemaOrCoherenceValidatorImport` (simple-name endsWith), self-tested in `RuntimeImplementationImportRulesTest`; new guard applies it across runtime-domain install/+workflow/ AND runtime-application main. Validator PORTS (InstallPlanWireValidator/DecompositionManifestValidator/WorkflowSnapshotValidator) are allowed — they are the sanctioned reach. reusable
- AC4: `runtime-contracts` purity LOCK — new test bans networknt/Jackson/`java.nio.file.Files` over BOTH parsed imports and source text (reuses the `Files.` regex in `containsBannedReference`), with a synthetic fixture asserting each banned reference fires. reusable
- Verified (no new test needed): all six `*SchemaContractVersionTest` map their `*_CONTRACT_VERSION` to `properties.contract_version.const`; `TELEMETRY_PROXY_CONTRACT_VERSION` + `INSTALL_SELECTION_CONTRACT_VERSION` have NO canonical schema file so are correctly out of scope.
- Docs: added Boundary Rule 5 contracts-purity clause; recorded the external-schema source-of-truth decision (canonical `../orchestration/contracts/*.yaml`, build-time Copy into infra-fs ×5 + mcp ×1, config-cache-friendly doFirst loud-fail, parity guarantee) + superseded the stale 2026-05-19 dual-seam mechanics. The open-boundary three-place lockstep (allow-list block + RAW_MAP_OPEN_BOUNDARY_ALLOWLIST + SKILL-52.2 inventory) and RuntimeModule/fenced lists were already reconciled by subtasks 1-4 — no code retired this subtask, so any reduction would break parity (verified no-op). reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-05-29] SKILL-52.3 subtask-4 application-wire-seam-and-open-boundary-reconciliation
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports (workflow), runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md
- Removed the last direct Jackson edge from `runtime-application`: decomposition-manifest YAML serialization moved out of `DecompositionManifestFileWrites` behind a new `DecompositionManifestFileStore.encodeManifestYaml(wireMap)` port method, implemented in infra-fs `FileSystemDecompositionManifestFileStore` (owns `YAMLMapper`). Mirrors the subtask-1 decode port (`DecompositionManifestValidator`) — encode+decode now both cross the same port seam. reusable
- Ordering invariant to preserve: build validated wireMap via `encodeDecompositionManifestMap` (validate) -> `fileStore.encodeManifestYaml` (serialize) -> `validator.validateYamlText` (revalidate). Keep `encodeDecompositionManifestMap` in runtime-application — `DecompositionManifestArchitectureTest` asserts the application seam owns it and NO LONGER names `YAMLMapper`, while the infra-store seam does. reusable
- The new port method takes `Map<String,Any?>`, so it tripped the raw-map scanner (which walks runtime-ports, NOT infra-fs); resolved by `@OpenBoundaryMap` + a documented allow-list row, accepted as the symmetric mirror of the decode port rather than a typed `writeManifest(model)`. reusable
- Reconciliation: typed `SystemService.doctor`/`version` to return `DoctorContract`/`VersionContract` (.toPayload() pushed into CLI+MCP adapters, byte-equivalent); relabeled 5 lifecycle payload helpers + 7 `LifecycleTelemetryService` methods as `@OpenBoundaryMap` accepted permanent open boundaries. Deleted all four future-tense "Subtask N will remove" allow-list literals. reusable
- Pitfall reaffirmed (see subtask-3): the THREE-place lockstep — `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` constant + ARCHITECTURE.md open-boundary marker block + SKILL-52.2 inventory ledger — must move in strict-set parity; open_extension inventory entries carry NO `[subtask N]` tag, gated categories must. reusable
- Known limitation: `jackson.dataformat.yaml` stays as `testImplementation` in runtime-application/build.gradle.kts (a pre-existing subtask-1 decode test fake + the new encode test fake both construct `YAMLMapper`); production edge is gone. Subtask 5 owns a jackson-import-ban enforcement test and may tighten AC1 wording to "no production Jackson dependency".
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-29] SKILL-52.3 subtask-3 scaffold-typed-result-closure
Areas: runtime-kotlin/runtime-ports (scaffold {catalog,repo,source}.model), runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-desktop/core/data, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md
- Closed the 8 `Scaffold*Result` port DTOs (List/Show/Explain/Validate/Upgrade/Fill/SaveExactContent/EditWithBodyFile) that SKILL-52.1 left dual-representation: dropped the `payload: Map<String,Any?>` field + the `init{}` desync `require` blocks + `@OpenBoundaryMap`, lifting every payload key to a typed field. This is the closure SKILL-52.1 (2026-05-24) explicitly deferred. reusable
- Wire-shape (JSON) emission now lives ONLY in adapter mappers: `runtime-cli/ScaffoldCliResultMappers.kt` (+ extracted `ScaffoldCliWireMaps.kt` `internal` `toWireMap` helpers, split to dodge detekt `TooManyFunctions`) and desktop `service/mapper/{ScaffoldListResultMapper,ValidationSummaryMapper}.kt`; infra-fs producers return typed records via new `scaffold/AuthoringResults.kt`. New port model `scaffold/model/ScaffoldSkillStatus.kt`. `ScaffoldService` (runtime-application) stays a pure pass-through — do NOT map wire there (triplication pitfall). reusable
- Pitfall: byte-equivalence risk is wire-map key ORDER, not just the key set. The adapter mapper must rebuild the `LinkedHashMap` in EXACT producer insertion order (highest risk: validate selected-mode inserts `skill_names` after `mode` + `suggested_commands` at tail; edit keeps bookkeeping keys before status keys). No scaffold byte-golden exists — order is now locked by `runtime-cli/.../ScaffoldCliResultMappersTest.kt` asserting `.keys.toList()`. reusable
- Pitfall: ARCHITECTURE.md carries TWO allow-list blocks (the open-boundary marker block AND the SKILL-52.2 inventory ledger) in strict-set parity with `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` in `RuntimeArchitectureTest`; the 8 rows must be removed from all THREE in lockstep or the parity test fails. reusable
- `RuntimeDesktopGatewayPolicyTest` tightened: the mapper-file exemption is gone, so `.payload[` is now forbidden across the entire desktop jvmMain service tree (mappers consume typed fields). reusable
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-29] SKILL-52.3 subtask-2 domain-effect-purity
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-infra-http, runtime-kotlin/runtime-core architecture tests
- Made `runtime-domain` effect-free (no entropy/clock/JVM-logging). General pattern: when a pure-layer function reads an injected concern, push the read to the adapter that already owns it rather than inventing a new port — pick the smallest behavior-preserving move. reusable
- install-id: `defaultLocalTelemetryConfig(installId: String)` is now pure; the random fallback is minted in `FileTelemetryConfigStore.ensureTelemetryConfigFile` (infra-fs, which already reads env + Files). `normalizedInstallId` still prefers any persisted value, so a fresh UUID is written only on first install with no `SKILL_BILL_INSTALL_ID` env — persisted config stays byte-equivalent. reusable
- clock: dropped the `today = LocalDate.now(ZoneOffset.UTC)` default from BOTH `parseRemoteStatsWindow` overloads (object + top-level — keep signatures aligned); sole caller `HttpTelemetryClient.fetchRemoteStats` (infra-http) reads the clock once and passes `today`. reusable
- logging: moved SkillRemove begin/failure/success `log.info` into adapter `SkillRemoveJvmFileSystem.applyCascade` (already holds a `Logger`); duplicated the path-sanitized `describeTargetForLog` (emits skill:/platform:/addon: slugs, never absolute paths). Behavior subtlety: the removed domain inner `catch(Throwable)` was log-then-rethrow only, so the adapter's `catch(Exception)` failure-log no longer fires for JVM `Error` — intentional, matches the F-ERROR-PROPAGATE design; not observable via the typed `SkillRemovalResult`. reusable
- enforcement: new `RuntimeArchitectureTest` ban scans `runtime-domain/src/main/kotlin/` ONLY via `assertNoBannedSourceReferences` (source-text scan catches inline FQNs, not just imports) for UUID.randomUUID/LocalDate.now/Instant.now/System.currentTimeMillis/System.nanoTime/Clock.system/java.util.logging. Do NOT widen to infra-fs/http — they legitimately use these. reusable
- Pitfall: a new standalone helper fn in `FileTelemetryConfigStore` tripped detekt `TooManyFunctions` (file already at the 10-fn limit); fold the logic into an existing function instead of suppressing.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-28] SKILL-52.3 subtask-1 schema-validator-extraction
Areas: runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-core, ARCHITECTURE.md
- The three JSON-Schema validators (`InstallPlanSchemaValidator`, `WorkflowStateSchemaValidator`/`CanonicalWorkflowStateSchemaValidator`, `DecompositionManifestSchemaValidator`) + `DecompositionManifestCoherenceValidator` moved out of the `runtime-contracts` pure leaf into `runtime-infra-fs`; reached only through two new domain-neutral ports `InstallPlanWireValidator` (skillbill.install.model) and `DecompositionManifestValidator` (skillbill.workflow), built on the SKILL-52.2 `WorkflowSnapshotValidator` template (which is the canonical pattern for inverting an infra concern out of a pure layer). reusable
- Port→adapter delegate impls MUST live in `runtime-infra-fs`, NOT `runtime-application`: `RuntimeGradleModuleLayeringTest` forbids application→infra-fs, so the old application-side `WorkflowSnapshotValidatorAdapter` only worked while the validator lived in contracts. `WorkflowSnapshotValidator` was retrofitted into `RuntimeComponent` DI for uniformity (`WorkflowSnapshotValidatorInfraAdapter`). reusable
- CLI cannot inject a domain validator port (leaks onto the CLI compile graph + runtime-core public ABI); route CLI install-plan validation through a thin application method (`InstallService.validateInstallPlanWire`) instead. reusable
- Moved validator classes intentionally KEEP package `skillbill.contracts.*` while compiling into `runtime-infra-fs`, preserving classpath schema-resource paths + import compatibility (see agent/decisions.md 2026-05-28). The 3 schema Copy tasks + networknt/jackson deps moved contracts→infra-fs with identical generated output dir + unchanged `../orchestration/contracts` source. reusable
- Pitfall: threading new ports through `@Inject` services + the decomposition continuation web trips detekt `LongParameterList`; bundle related ports into an `@Inject` holder (`InstallPlanningPorts`) and prefer `WorkflowEngine` extension functions over extra params. reusable
- Known limitation: `DecompositionManifestFileWrites` still uses Jackson `YAMLMapper` for serialization (deliberately deferred to subtask 4); subtask 5 owns the external-schema source-of-truth decision.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
## [2026-05-25] SKILL-52.2 adapter-dependency-and-desktop-convergence
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-desktop/core/data, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md
- Per-adapter Gradle dependency allow-lists pinned by new `RuntimeAdapterDependencyAllowlistTest`: `runtime-cli`/`runtime-mcp`/`runtime-desktop:core:data` jvmMain drop `runtime-infra-fs` and `runtime-infra-http` from their main-source `project(...)` set; infrastructure adapters resolve through `RuntimeComponent` (kotlin-inject) instead. `runtime-desktop:core:data` jvmMain gains an explicit `runtime-contracts` edge so its direct `skillbill.error.*` imports stop relying on transitive runtime-application API. reusable
- Three remaining service-level raw-map `.payload` reads in `RuntimeRepoBrowserService.kt` (saveExactContent, validate, list) are lifted to typed runtime-application result consumption. `saveExactContent`'s `authoringSaver` now returns the typed `ScaffoldSaveExactContentResult` (the value is discarded — success is signalled by absence-of-exception); `validate` consumes `ScaffoldValidateResult.status` for the pass/fail decision; `list` consumes a typed `List<AuthoredSkillEntry>` projected by `service/mapper/ScaffoldListResultMapper.kt`. Remaining raw-shape decoding (legacy issue strings, structural list entries) is contained in `service/mapper/` extension files that take the typed result as the receiver, so the boundary inventory can locate the seam. Pattern: when a typed result still carries an `@OpenBoundaryMap payload` field, extract any service-level `.payload` indexing into a dedicated adapter-side mapper that receives the typed result. reusable
- New `RuntimeDesktopGatewayPolicyTest` scans `runtime-desktop:core:data` jvmMain service code for `.payload[`, `.payload.toSelected`, and `.payload as? Map` patterns and fails on any regression; mapper files in the explicit whitelist are exempt because they receive the typed result and only contain the legacy-shape translation step. reusable
- `RuntimeCoreCompositionOnlyTest` pins `runtime-core`'s `api(project(...))` set to `runtime-application + runtime-ports` and the `implementation(project(...))` set to `runtime-domain + runtime-contracts + runtime-infra-{fs,http,sqlite}`; the test fails if any infrastructure or entrypoint module ever appears as `api(...)`, locking the composition-only contract documented in ARCHITECTURE.md §Gradle Modules. reusable
- ARCHITECTURE.md §Gradle Modules adapter paragraphs and the SKILL-52.1 §Deferred desktop debt note are reconciled with the new tests; the historical "third reader" footnote at L314-326 now marks the desktop debt closed and points readers at `RuntimeDesktopGatewayPolicyTest`. The SKILL-52.2 inventory marker block needs no edits — the three lifted reads were inside the desktop adapter, never on the application/domain/ports raw-map allow-list. reusable
- No retained-exception edges were needed: every dropped Gradle dep was unused outside test sources, and every concrete-import allow-list entry is justified by a direct `skillbill.*` import in main code. `runtime-desktop:feature:skillbill` already declared no upstream runtime-application/domain/ports/contracts edges and stays that way.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-25] SKILL-52.2 workflow-schema-ownership
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md
- `WorkflowEngine` no longer imports `skillbill.contracts.workflow.*SchemaValidator`; schema validation goes through a domain-owned `WorkflowSnapshotValidator` port and an application-side `WorkflowSnapshotValidatorAdapter` that wraps `CanonicalWorkflowStateSchemaValidator`. The port carries `@OpenBoundaryMap` because it gates the canonical map envelope. reusable
- `WorkflowEngine` moved from `object` to `class WorkflowEngine(schemaValidator)`; the stateless helpers (`validateOpen`, `validateUpdate`, `snapshotMap`, `summaryMap`, `resumeMap`, `continueMap`) stay on the companion object so `WorkflowEngine::summaryMap` method references in `runtime-cli`/`runtime-mcp` workflow result mappers keep working unchanged. Pattern: when refactoring a runtime singleton to inject a port, keep stateless wire-shape helpers on the companion to preserve adapter-side method references and CLI/MCP byte-equivalence. reusable
- All 8 loud-fail `InvalidWorkflowStateSchemaError` throw sites are byte-identical (openRecord/updateRecord via validatedSnapshotMap; snapshotView/summaryView/resumeView; decodeSteps/decodeObject/parseDurableJson; snapshotViewFromMap attempt_count exactness). The SKILL-48 2a invariant ("`toSnapshot` deliberately does not validate; the next read seam loud-fails") is preserved verbatim.
- Internal helpers (`continueExistingWorkflow`, `alignSubtaskResumeStep`, `persistParentDecompositionRuntime`, `blockedBranchStartResult`) became extension functions on `WorkflowEngine` to keep `detekt` `LongParameterList=6` honored without suppressions when the engine had to be threaded through.
- `ARCHITECTURE.md` narrows the `runtime-domain -> runtime-contracts` edge to non-validator helpers/constants/errors and adds `WorkflowSnapshotValidator.validate` to the open-boundary allowlist. New architecture test forbids `skillbill.contracts.workflow.*SchemaValidator*` and `skillbill.contracts.*Mapper` imports under `runtime-domain/.../skillbill/workflow/` source.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-25] SKILL-52.2 review-telemetry-typed-boundaries
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-infra-{sqlite,http,fs}, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md
- ReviewService and ReviewRepository now expose typed review/stat/triage models; CLI/MCP map payloads are rebuilt only in adapter mapper files. reusable
- TelemetryClient, TelemetryConfigStore, and TelemetryService now expose typed capability/status/sync/stats models, with `TelemetryConfigDocument` as the explicit open config document type. reusable
- Review-finished JSON projection lives at the port telemetry boundary and `runtime-ports` publishes `runtime-contracts` as an API dependency because `JsonPayloadContract` is in the public port ABI.
- Remote `/stats` responses preserve explicit `capabilities: null`; default proxy capabilities are inserted only when the key is absent, with CLI/MCP regression coverage.
- Telemetry sync uses short database sessions around outbox reads/writes instead of holding a transaction across remote I/O.
- ARCHITECTURE.md inventory and architecture tests remove retired review/telemetry raw-map public APIs and keep lifecycle telemetry raw-map cleanup postponed to subtask 4.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-25] SKILL-52.2 scaffold-typed-command-boundary
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-desktop/core/data, runtime-kotlin/runtime-core architecture tests
- `ScaffoldGateway.scaffold` and `ScaffoldService.scaffold` are typed-only now: `scaffold(request: ScaffoldCommandRequest, dryRun: Boolean)`. Sealed model lives at `runtime-domain/skillbill.scaffold.model.command` (the `.model.*` package keeps it adapter-importable under `RuntimeImplementationImportRules`). reusable
- Per-adapter raw-map parsers in `runtime-cli/skillbill.cli.scaffold` and `runtime-mcp/skillbill.mcp.scaffold` (split into 3 files each for detekt thresholds); desktop maps sealed `ScaffoldPayload -> ScaffoldCommandRequest` with no `Map` round-trip. Wire-mappers stay out of `runtime-application` (the recurring triplication pitfall). reusable
- Generic raw-map extraction primitives in `runtime-contracts/skillbill.contracts.scaffold.wire.ScaffoldPayloadParseSupport` — the architecture raw-map scanner only walks application/domain/ports, so contracts-side helpers do NOT need allow-list entries. reusable
- Internal typed->raw bridge `runtime-infra-fs/.../scaffold/ScaffoldCommandRequestRawPayload.kt` (split into 5 per-kind appenders) re-materializes the typed request for the existing orchestrator path; preserves AC4 byte-equivalence trivially. Phase-5 elimination of this round-trip is deferred (still inside infra, never crosses a public boundary). reusable
- 9 raw-map policy helpers RELOCATED from `runtime-domain.scaffold.policy` to `runtime-infra-fs.scaffold.ScaffoldPayloadMapPolicy*` as `internal` (no rewrite required because the scanner does not walk infra); 11 scaffold entries removed from `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` and ARCHITECTURE.md inventory in lockstep.
- Wire-error invariants enforced AT THE ADAPTER BOUNDARY: `routing_signals.strong/tie_breakers` loud-fail on present-but-non-list; empty `baseline_layers: []` loud-fails with the exact `failBaselineLayersEmpty` wording; desktop `toRuntimeBaselineLayer(index)` throws `InvalidScaffoldPayloadError` (a `SkillBillRuntimeException`) so the gateway reports `rollbackComplete = true`. Drop `op` from contracts-side helper error messages to keep CLI/MCP/legacy error strings byte-equivalent.
- `ScaffoldStandaloneEntrypoint` retained for in-module tests only; the adapter-import rule (`skillbill.scaffold.*` outside `.model.*` is forbidden in CLI/MCP/Desktop) already quarantines it.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-25] SKILL-52.2 boundary-inventory-and-contract-targets
Areas: runtime-kotlin/ARCHITECTURE.md, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/runtime-domain (SkillRemoveFileSystem KDoc)
- New `<!-- skill-52-2-inventory:start/end -->` section in ARCHITECTURE.md classifies every public raw-map FQN into four retirement categories (must_type_now, open_extension, private_serializer, postponed_with_reason) and tags must-type/postponed entries with their SKILL-52.2 subtask owner (2..5). reusable
- `RuntimeArchitectureTest` now parses the new marker block and enforces strict-set parity with `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST`, no-duplicate FQNs, `@OpenBoundaryMap`-annotated declarations placed in `open_extension`, and a subtask-id range check; a synthetic-fixture test guards the inventory parser per the SKILL-52.1 F-007 pattern. reusable
- WorkflowEngine snapshotMap/summaryMap/resumeMap/continueMap and WorkflowFamily.sessionSummary are `@OpenBoundaryMap`-annotated so they belong in `open_extension`, not postponed; continueDecision (unannotated) stays postponed.
- `SkillRemoveFileSystem` KDoc now points at `runtime-infra-fs/.../SkillRemoveJvmFileSystem.kt` instead of the stale `runtime-core` location.
- Any future allow-list edit MUST update the inventory in the same change; strict-set parity is enforced both ways.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-24] SKILL-53 validation-contract-lock
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-desktop, runtime-kotlin/runtime-core architecture tests
- Final SKILL-53 coverage locks CLI install persistence, detected/manual agent replay, MCP opt-out, desktop legacy preference migration, and install.sh runtime delegation without adding desktop dependencies. reusable
- `FileSystemInstallSelectionPersistence` now validates write payloads through the same parser and UTF-8 size guard as reads, preserving the prior durable record on invalid or oversized writes. reusable
- Desktop replay preserves `PlatformSelectionMode.ALL`; completed desktop preferences normalize live and durable state back to desktop-owned fields only.
- Decomposition manifests must not carry review/audit/validation payloads; workflow artifacts own those results while git-tracked manifests keep runtime projection fields only.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-05-24] SKILL-53 desktop-adapter-migration
Areas: runtime-kotlin/runtime-desktop/core/domain, runtime-kotlin/runtime-desktop/core/data, runtime-kotlin/runtime-desktop/core/datastore, runtime-kotlin/runtime-desktop/feature/skillbill
- Desktop first-run and post-publish reinstall replay now flow through `DesktopFirstRunGateway.latestReusableSetupRequest`, backed by the shared `InstallSelectionPersistencePort`; no CLI or shell dependency was added. reusable
- `LocalDesktopPreferenceStore` keeps desktop-owned completion/recent-repo state, removes reusable install-choice keys on new writes, and still loads legacy `firstRun.*` keys as a migration fallback. reusable
- Desktop request models now preserve platform pack selection mode (`NONE`, `SELECTED`, `ALL`) so shared install-selection replay can round-trip all-pack installs.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-24] SKILL-53 cli-shell-persistence
Areas: runtime-kotlin/runtime-application install service, runtime-kotlin/runtime-cli install apply, install.sh runtime delegation
- `InstallService.applyInstall` now persists `SharedInstallSelection` through `InstallSelectionPersistencePort` only after non-failure typed apply results, keeping CLI/shell writes outside desktop modules. reusable
- Persisted agents prefer `InstallApplyResult.resolvedInstalledAgents` and fall back to planned agents only when the apply result has no stronger evidence. reusable
- CLI coverage now asserts manual/detected selections, platform mode, telemetry level, MCP opt-out, and failure non-persistence through the canonical `install-selection.json` record.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-24] SKILL-53 shared-install-selection-foundation
Areas: runtime-kotlin/runtime-domain install model, runtime-kotlin/runtime-ports install selection port, runtime-kotlin/runtime-infra-fs install-selection persistence, runtime-kotlin/runtime-contracts errors, runtime-kotlin/runtime-core DI
- Shared install selection now lives outside desktop as `SharedInstallSelection` plus `InstallSelectionPersistencePort`; requests carry explicit `installHome` so CLI/Desktop callers can persist the intended runtime install state. reusable
- `FileSystemInstallSelectionPersistence` stores canonical v1 JSON at `<installHome>/.skill-bill/install-selection.json` with atomic temp-file replacement, a 64 KiB read guard, and typed missing/unreadable/malformed errors. reusable
- Parser locks install-selection invariants: selected platform slugs must match mode, slugs and MCP bin paths cannot be blank, and canonical JSON shape has fixture coverage.
- `InstallApplyResult.resolvedInstalledAgents` is computed from status+skill links only: empty on failure, CREATED/SKIPPED agents on success/warning, keeping future persistence from storing stale requested agents. reusable
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-24] SKILL-52.1 final-validation-and-contract-lock
Areas: runtime-kotlin/ARCHITECTURE.md, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-desktop/core/data
- SKILL-52.1 closes with architecture documentation and tests aligned on raw-map open-boundary parity, inert `Path` handling outside adapters/composition, install-policy ownership with dual install-plan validation, and the narrowed runtime-core public ABI edge. reusable
- The runtime-core shrink rule is now explicit: generated Kotlin-Inject ABI edges are the only retained public runtime-core surface, and architecture tests must reject growth into infrastructure or entrypoint modules. reusable
- Final validation passed with `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, and `(cd runtime-kotlin && ./gradlew check)`.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-24] SKILL-52.1 path-policy-and-core-shrink
Areas: runtime-kotlin/ARCHITECTURE.md, runtime-kotlin/runtime-core, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-infra-http, runtime-kotlin/runtime-infra-sqlite, runtime-kotlin/runtime-desktop/core/data
- Path policy is now explicit: application/domain/ports may carry `java.nio.file.Path` only as inert data; Files IO, home expansion, `System.getenv`, and `System.getProperty` belong at adapter/composition seams. reusable
- `RuntimeContext` defaults are pure sentinels; `RuntimeComponent` and public concrete adapters resolve process defaults locally, with `HttpTelemetryClient` also resolving `UnconfiguredHttpRequester` to `JdkHttpRequester`. reusable
- Review and telemetry `~/...` expansion moved out of domain helpers into fs adapters, with adapter tests for context-bound review home and telemetry env-path expansion.
- `runtime-core` now publishes only generated Kotlin-Inject ABI edges (`runtime-application`, `runtime-ports` direct; documented transitive closure through domain/contracts) and architecture tests reject closure growth into infra/entrypoint modules. reusable
- Boundary tests now ban broader JDBC/HTTP/Clikt/framework APIs, process/home lookup, direct file IO, runtime-core implementation packages, and adapter imports of low-level implementation packages.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-24] SKILL-52.1 install-contract-validation
Areas: runtime-kotlin/runtime-domain install wire maps, runtime-kotlin/runtime-infra-fs install builder, runtime-kotlin/runtime-cli install JSON, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md
- `validateInstallPlanWireSnapshot(plan)` is now the shared install-plan wire validation helper used at both approved seams: builder return and CLI JSON emission; keep the dual seam per the 2026-05-19 decision. reusable
- CLI install plan/apply byte-equivalence coverage now compares stdout against static golden payload maps built from fixture paths, not decoded actual output; use this pattern when guarding JSON order/shape. reusable
- Adapter ownership coverage rejects install planner/validator policy via direct FQN, alias import, wildcard import, and unapproved validation-helper usage; relativized paths are normalized to `/` for cross-platform allow-list checks.
- `install.sh` and MCP install envelopes were intentionally left unchanged; full Gradle validation passed after focused review fixes.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-24] SKILL-52.1 install-capability-ports-and-adapters
Areas: runtime-kotlin/runtime-ports install ports, runtime-kotlin/runtime-application InstallService, runtime-kotlin/runtime-infra-fs filesystem install adapters, runtime-kotlin/runtime-core DI + architecture tests
- The retired monolithic install gateway was split into capability ports for planning facts/materialization/staging/apply/link/agent/native-agent/MCP, each with one `*Request` input and `*Result` output model; mirror this shape for remaining runtime seams. reusable
- `InstallService` now orchestrates InstallPlanPolicy plus capability ports directly while infra-fs still owns filesystem snapshots, staging, symlinks, MCP config mutation, native-agent links, Windows preflight, and rollback mechanics. reusable
- Planning carries one platform manifest snapshot through materialization and staging; do not rediscover packs mid-plan or carry duplicate snapshot representations that can drift under concurrent repo changes.
- Architecture coverage now rejects retired install gateway names, raw public install port maps, and port functions that lack typed request/result signatures; keep fixture-strength scanners when extending this guard.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-24] SKILL-52.1 install-policy-foundation
Areas: runtime-kotlin/runtime-domain install policy/model, runtime-kotlin/runtime-infra-fs install builder, runtime-kotlin/runtime-core architecture tests, runtime-kotlin/ARCHITECTURE.md
- Install request validation and pure plan construction now live in `skillbill.install.policy` over typed snapshots (`InstallPolicyInput`, `InstallPlatformPackSnapshot`, detected/default agent targets) and produce `InstallPlanDraft`; no public raw-map policy API was added. reusable
- `runtime-infra-fs` still owns discovery, platform schema parsing, agent/path probing, pointer realpath checks, content hashing, staging paths, symlink/native-agent/MCP/apply mechanics, Windows preflight, and rollback; it converts those facts into snapshots before policy execution. reusable
- Builder and CLI install-plan schema validation remain dual-seam: builder still validates `buildInstallPlanWireMap(plan)` through `InstallPlanSchemaValidator`, and CLI emission still revalidates the same wire helper.
- Architecture coverage now blocks install policy from importing filesystem/process or infra-fs install implementation mechanics; follow this pattern when extracting the remaining install seams.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-24] SKILL-52.1 scaffold-raw-map-elimination
Areas: runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-core/architecture tests + DI, runtime-kotlin/ARCHITECTURE.md, runtime-kotlin/runtime-desktop/core/data
- Eight `ScaffoldGateway` raw-map producers (`list`, `show`, `explain`, `validate`, `upgrade`, `fill`, `saveExactContent`, `editWithBodyFile`) now return typed `Scaffold*Result` models under `runtime-ports/.../scaffold/<capability>/model/`, each lifting stable top-level scalars plus a single `@OpenBoundaryMap`-annotated `payload: Map<String, Any?>` field; `init { require(...) }` invariants enforce typed/payload consistency at construction. Mirror this triad shape for any future raw-map seam. reusable
- `FileSystemScaffoldGateway` lifts via `requireScalar<T>(op, key)` / `requireInt(op, key)` helpers throwing `InvalidScaffoldPayloadError` with op + key + expected/got type; `requireInt` tolerates `Number` widening for JSON round-trips. Never replace these with raw `as` casts. reusable
- New `FileSystemScaffoldOrchestrator` (`@Inject` DI-bound) replaces the prior file-static `FileSystemScaffold*` singletons inside `skillbill.scaffold.ScaffoldService.kt`; carved IO-coupled validators (`validateBaselineLayerPayloadReferences`, `validateScaffold`, `plannedAuthoringTarget`, `resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`, `optionalBaselineLayers`) live as `internal fun` on the existing capability adapters. Orchestrator injects concrete adapters (NOT port interfaces) because these validators are internal-on-adapter, not port-level. Test-only `ScaffoldStandaloneEntrypoint.scaffold(...)` wrapper keeps the legacy in-tree test call shape without singletons.
- 16 of 18 scaffold raw-map allow-list entries removed from `RuntimeArchitectureTest.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` + ARCHITECTURE.md `<!-- open-boundary-allowlist:start/end -->` markers; 2 retained for `scaffold(...)` INPUT (subtask 4). 8 new typed-model `payload` FQN entries added. New `LEGACY_FORBIDDEN_TOP_LEVEL_REGEX` (modifier-agnostic — catches `private/internal/bare/public fun` and ktfmt-wrapped multilines) replaces brittle substring checks. Gateway raw-map producer regex now `DOT_MATCHES_ALL` with wrapped-signature fixture, replicating the subtask-1 F-007 fixture-based-scanner pattern.
- CLI mapper (`ScaffoldCliResultMappers.kt`) lives in `runtime-cli` adapter only; `runtime-application/ScaffoldService.kt` is a pure pass-through with no wire-mapping. `ScaffoldMcpResultMappers.kt` was created then deleted because `McpScaffoldRuntime` only exposes typed `newSkillScaffold(...)` today — when MCP gains raw-map endpoints, re-introduce the mapper alongside that wiring with a smoke test. ARCHITECTURE.md "Typed-Result-Model Open-Boundary Pattern" section documents both the doctrine and the MCP deletion rationale.
- Deferred to subtask 4 (with doc note in ARCHITECTURE.md): `RuntimeRepoBrowserService` desktop adapter still reads `.payload` directly — third reader of the open-boundary map, must be migrated when typed list/show/validate structural fields are lifted; `ScaffoldShowResult.completion_status` typed lift; `ScaffoldValidateStatus` sealed/enum (CLI exit-code branches on raw string today); generalized `scaffoldApplicationServiceFileNames` filename allow-list; `scaffold(...)` INPUT raw-map allow-list entries.
- Pitfalls to avoid: (a) wire-mapper triplication keeps re-appearing — adapter modules only, never `runtime-application`; (b) do NOT relax the typed-result `init` invariants to a softer check; (c) do NOT replace `requireScalar`/`requireInt` with raw `as` casts; (d) keep `ScaffoldStandaloneEntrypoint.scaffold(...)` test-only (F-016 residual — tighten to `internal` once spotless compat verified); (e) gateway raw-map regex companion-object extraction would let fixture and prod assertion share a single constant (F-018 residual — apply when next subtask touches that file).
Feature flag: N/A
Acceptance criteria: 8/8 implemented (AC5 carries an accepted spec-literal-vs-intent interpretation: MCP mapper file deletion accepted because MCP currently has no raw-map endpoints to map)

## [2026-05-24] SKILL-52.1 scaffold-ports-and-pure-policy
Areas: runtime-kotlin/runtime-ports, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-application, runtime-kotlin/runtime-core/di + architecture tests, runtime-kotlin/ARCHITECTURE.md
- Five capability-named scaffold ports replace the prior monolithic `ScaffoldGateways.kt`: `ScaffoldSourceLoaderPort`, `ScaffoldManifestPersistencePort`, `ScaffoldGeneratedStagingPort`, `ScaffoldInstallLinkPort`, `ScaffoldRepoValidationPort`, each with typed request/result data classes under `ports/scaffold/<capability>/model/` (TelemetryLevelMutator shape). `FileSystem<Capability>` adapters in `runtime-infra-fs` delegate into existing `skillbill.scaffold.AuthoringOperations` / `scaffold(...)` without splitting the 1358-LOC `ScaffoldService.kt`. reusable
- Eleven pure-policy functions moved into `runtime-domain/skillbill/scaffold/policy/` (`ScaffoldPayloadPolicy`, `ScaffoldSubagentPolicy`, `PlatformPackPolicy`, `PlatformPackManifestPolicy`, `ScaffoldPolicyConstants`, `ScaffoldPolicySupport`); shrank `runtime-infra-fs/ScaffoldService.kt` by 363 LOC and `ScaffoldManifestEdits.kt` by 117 LOC. IO-coupled validators (`validateBaselineLayerPayloadReferences`, `validateScaffold`, `resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`, `plannedAuthoringTarget`, `optionalBaselineLayers`) intentionally stay in infra-fs — deferred to subtask 3 alongside `ScaffoldGateway` raw-map elimination and removal of the 18 legacy scaffold allow-list entries. reusable
- New `ImplementationOwnershipArchitectureTest.scaffoldPolicyPackagesMustNotImportInfraFs` scans `runtime-domain/scaffold/policy/*` + scaffold-related application services and forbids `skillbill.infrastructure.fs.*` or `skillbill.scaffold.{ScaffoldService|FileSystem.*}` imports. Paired with a fixture-based negative regex test (replicating subtask-1 F-007 pattern) so a future regex regression loud-fails instead of silently passing. reusable
- `SHELL_CONTRACT_VERSION` in infra-fs is now a `get()` alias of `PLATFORM_PACK_SHELL_CONTRACT_VERSION` in `runtime-domain/scaffold/policy/PlatformPackManifestPolicy.kt`, same pattern used earlier for `SCAFFOLD_PAYLOAD_VERSION`. Single source of truth across the alias chain.
- Pitfalls to avoid in subtask 3: ScaffoldManifestPersistencePort currently mixes pure rendering (`renderPlatformPackManifest`, `renderGovernedAddonRegistrationPreview`) with IO persistence on one capability surface — defer reshape until consumers exist; loud-fail at parse seams when threading typed requests so existing `InvalidScaffoldPayloadError`/`ScaffoldPayloadVersionMismatchError`/`UnknownSkillKindError` throws are preserved; detekt `ThrowsCount`/`LongMethod` are fixed at root cause via private `failXxx(...): Nothing` helpers and test-helper extraction, never with suppressions.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-24] SKILL-52.1 typed-boundary-foundation
Areas: runtime-kotlin/ARCHITECTURE.md, runtime-kotlin/runtime-core/architecture tests, runtime-kotlin/runtime-application, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp
- New raw-map architecture-test guard fails loudly on any public `Map<String, Any?>` / `Map<String, *>` / `MutableMap<String, Any?>` declaration in runtime-application/domain/ports unless allow-listed by FQN or marked `@OpenBoundaryMap`; pair this with a fixture-based negative test so the scanner is itself regression-proof. reusable
- `@OpenBoundaryMap` lives in a neutral `skillbill.boundary` package inside runtime-domain so application and ports can both import it without circular deps. reusable
- ARCHITECTURE.md is the source of truth: HTML-comment markers (`<!-- allow-list:start -->` … `:end -->`) wrap the Open-Boundary Allow-List section so a two-direction parity test parses bullets out of the doc instead of self-referential hardcoded lists. reusable
- WorkflowService public methods now return typed sealed `WorkflowXxxResult` models from `skillbill.application.model.WorkflowResults`; WorkflowEngine exposes typed `snapshotView`/`summaryView`/`resumeView`/`continueDecision` plus open-boundary `*Map` serializers consumed by parallel `WorkflowCliResultMappers` and `WorkflowMcpResultMappers`. `WorkflowEngine.validatedSnapshotMap` stays private to preserve the `InvalidWorkflowStateSchemaError` loud-fail seam. reusable
- Legacy raw-map allow-list entries are grouped by which follow-up subtask (2 scaffold / 3 install / 4 telemetry+review) will retire them, so cleanup PRs are mechanical.
- Pitfalls to avoid in subtasks 2-4: short-name allow-list lookup, blanket `*.model` package skip in source scanners, wire-mapper triplication (the test-only mapper invites drift — assert on typed result fields instead), and silent envelope-field additions on error paths (cover both error variants with explicit wire-shape regression tests).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-24] SKILL-52 architecture-enforcement-validation
Areas: runtime-kotlin/ARCHITECTURE.md, runtime-kotlin/runtime-core/architecture tests, runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-infra-sqlite
- `ARCHITECTURE.md`, `RuntimeModule`, Gradle settings, and architecture tests now pin the final hexagonal graph with runtime-core as composition only and runtime-contracts owning schema validators at parse seams. reusable
- Decomposition manifest file writes and review input loading moved behind explicit ports, with filesystem adapters in infra-fs; SQLite review runtime no longer owns file input helpers. reusable
- Architecture coverage now rejects non-composition runtime-core packages, forbidden layer imports, adapter bypasses, public app/domain/port models outside `model`, and direct file IO in app/domain/ports. reusable
- Full validation passed after clearing generated Gradle configuration cache: `./gradlew check`, `skill-bill validate`, `scripts/validate_agent_configs`, and `npx --yes agnix --strict .`.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-23] SKILL-52 adapter-composition-wiring
Areas: runtime-kotlin/runtime-core, runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-desktop/core/data
- Runtime install, scaffold, repo-validation, MCP-registration, native-agent, and skill-remove entry points now flow through application services plus port interfaces; `runtime-core` remains the composition root that binds concrete adapters. reusable
- Filesystem implementations live behind explicit infra-fs gateways, with typed port models for install, scaffold catalog/render, and repo validation instead of aggregate adapter bags or raw maps. reusable
- CLI, MCP, and Desktop declare honest direct Gradle dependencies instead of relying on runtime-core as a broad API umbrella; architecture tests guard runtime-core contents and infra module dependency direction. reusable
- Desktop first-run and skill-remove seams preserve caller-provided home binding through injected application services, including telemetry config and symlink preview behavior.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-23] SKILL-52 implementation-ownership
Areas: runtime-kotlin/runtime-core, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-application, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-desktop/core/data
- Install, scaffold, native-agent, launcher, skill-remove, and workflow runtime-surface implementation ownership moved out of `runtime-core`; `runtime-core` now stays a compatibility umbrella/DI composition layer through Gradle `api` edges. reusable
- Concrete filesystem implementations live in `runtime-infra-fs`; architecture coverage rejects moved infra packages depending on runtime-core, CLI, MCP, Desktop, HTTP, or SQLite sibling adapters. reusable
- Install telemetry apply now crosses a `TelemetryLevelMutator` port to application-owned mutation, preserving config validation and transactional outbox clearing while CLI rebinding honors parsed `--home`/`--db`. reusable
- Source/generated boundaries remain guarded after the move: rendered `SKILL.md`, support pointers, provider-native outputs, install staging, and desktop packaging artifacts stay generated.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-23] SKILL-52 domain-contract-foundation
Areas: runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-core/architecture, orchestration/contracts
- Runtime contract/schema validators and classpath schema resources for install-plan, workflow-state, and decomposition-manifest now live in `runtime-contracts`; domain code consumes typed validated seams rather than owning schema convenience APIs. reusable
- `DecompositionManifestCodec` is pure model/wire-map conversion; application seams own YAML/file/artifact validation through `load/decode/encodeDecompositionManifest*`, including validated `decomposition_runtime` artifact emission. reusable
- `WorkflowEngine` read seams loud-fail malformed durable JSON, wrong top-level shapes, blank persisted workflow contract fields, and non-exact/oversized integers with typed workflow schema errors.
- Same-branch decomposition still creates subtask commits before advancing, but `GitWorkflowGitOperations.createCommit` leaves live `decomposition-manifest.yaml` projections uncommitted so parent bookkeeping can continue across the full decomposition run. reusable
- Architecture coverage now bans domain workflow schema/YAML seams and raw application `decomposition_runtime` wire emission; the remaining application `Files` projection is documented as a temporary SKILL-52 blocker for later storage-port work.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-24] SKILL-53 decomposition-manifest-commit-projection
Areas: runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, skills/bill-feature-task
- Same-branch subtask commits now include staged `decomposition-manifest.yaml` status/current-intent projections; the previous infra Git adapter behavior that unstaged manifest projections before committing was removed. reusable
- Git-tracked decomposition manifests intentionally project `commit_sha: null`; subtask commit SHAs remain durable workflow runtime state because a commit cannot contain its own final SHA without changing that SHA. reusable
Feature flag: N/A
Acceptance criteria: internal defect fix

## [2026-05-23] SKILL-51 decomposition-workflow-state-validation-projection
Areas: runtime-kotlin/runtime-application/workflow, runtime-kotlin/runtime-domain/workflow, runtime-kotlin/runtime-core application tests, skills/bill-feature-task
- Parent decomposition projection now updates the parent spec status in addition to subtask frontmatter, and Markdown `## Status` sections are projected when present. reusable
- Final all-subtasks completion coverage asserts parent/subtask manifest state, commit advancement, and human-readable spec projection in one application-level flow.
- `bill-feature-task` provider-neutral planning agents now carry the same decomposition manifest/execution-model guidance as governed `content.md`; source install was refreshed with `./install.sh`.
Feature flag: N/A
Acceptance criteria: 16/16 implemented

## [2026-05-23] SKILL-51 decomposition-workflow-state-continuation-branching
Areas: runtime-kotlin/runtime-application/workflow, runtime-kotlin/runtime-domain/workflow, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-infra-fs
- Parent issue-key continuation now resolves decomposed feature parents from durable `artifacts.decomposition_runtime`, then resumes an in-progress subtask or starts the first dependency-complete pending subtask without requiring a subtask path. reusable
- `DecompositionContinuationSelector` centralizes dependency/blocked/optional-skipped selection semantics; blocked dependencies stop continuation with the stored reason unless the dependency is explicitly optional+skipped. reusable
- `WorkflowGitOperations` is the application port for branch checkout, commit creation, and stacked branch base validation; production DI uses `GitWorkflowGitOperations`, while CLI/MCP tests inject explicit fakes.
- Same-branch mode records an individual subtask commit before advancing; branch/commit failures persist the subtask and parent as blocked with `blocked_reason` so resume decisions stay durable.
- CLI and MCP continue paths accept either `workflow_id` or issue key for implement workflows, preserving existing single-workflow continuation behavior.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-05-23] SKILL-51 decomposition-workflow-state-runtime-state
Areas: orchestration/contracts, runtime-kotlin/runtime-domain/workflow, runtime-kotlin/runtime-application/workflow, runtime-kotlin/runtime-core/application tests
- `decomposition-manifest-schema.yaml` now carries parent/subtask runtime state: parent `status`, and per-subtask `branch`, `commit_sha`, `workflow_id`, `blocked_reason`, and `last_resumable_step`. Review/audit/validation result payloads stay in workflow telemetry/artifacts, not the decomposition manifest. reusable
- `WorkflowService.update` persists `artifacts.decomposition_runtime` first, then writes `decomposition-manifest.yaml` / subtask frontmatter as a post-save human-readable projection; ordinary single-spec workflows still do not create decomposition runtime or manifests. reusable
- Execution-model changes are allowed while every subtask is still pending. Once any subtask has recorded runtime state, `DecompositionManifestWriter` rejects an `execution_model` change with `InvalidDecompositionManifestSchemaError` and a manual-migration/reset message. reusable
- Subtask status projection covers implementation/review/audit/validation/PR-description blocked/skipped/complete events, but explicit unmatched `assessment.spec_path` no-ops rather than falling back to current intent; continuation selection and branch/commit advancement remain deferred to SKILL-51 subtasks 3/4.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-23] SKILL-51 decomposition-workflow-state-foundation
Areas: orchestration/contracts, runtime-kotlin/runtime-domain/workflow, runtime-kotlin/runtime-application/workflow, runtime-kotlin/runtime-contracts/error, skills/bill-feature-task
- New runtime contract `orchestration/contracts/decomposition-manifest-schema.yaml` defines the parent manifest for decomposed feature work; `DECOMPOSITION_MANIFEST_CONTRACT_VERSION` and classpath copy wiring mirror the workflow/install-plan schema pattern. reusable
- `DecompositionManifestCodec`, `DecompositionManifestCoherenceValidator`, and `DecompositionManifestWireMap` provide schema-backed YAML load/write mapping plus Kotlin cross-field checks for dependency order, same-branch defaults, stacked branch opt-in, and current subtask intent. reusable
- `WorkflowService` now writes a validated `decomposition-manifest.yaml` only for implement workflow updates whose plan artifact has `mode=decompose`; ordinary single-spec `mode=implement` updates remain manifest-free.
- Authored `bill-feature-task/content.md` now makes decomposition manifest creation part of the terminal planning-mode contract; generated skill installs were refreshed with `./install.sh`.
- Known limitation: branch checkout/commits, parent continuation, stack advancement, and subtask status transitions remain deferred to later SKILL-51 subtasks.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-22] SKILL-50 render-runtime-composition-instructions
Areas: runtime-kotlin/runtime-core/scaffold, runtime-kotlin/runtime-domain/scaffold/model, platform-packs/kmp, platform-packs/kotlin
- Generated `SKILL.md` render output now carries manifest-declared code-review composition before authored execution guidance; `AuthoringTarget` receives `PlatformManifest.codeReviewComposition` only for pack baseline skills. reusable
- KMP rendered snapshots pin the generated Review Composition section and required baseline metadata; a fixture pack without composition pins omission so empty/noisy sections do not regress.
- KMP/Kotlin review content now treats `kmp-baseline` as manifest-declared mode, not a caller-name exception; the KMP authored body focuses on local specialist behavior after generated baseline instructions.
- Render/install source hygiene remains enforced by render-output tests: no source-tree `SKILL.md` wrappers or generated pointer files are written during render.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-21] SKILL-50 schema-loader-contract
Areas: orchestration/contracts, runtime-kotlin/runtime-core/scaffold, runtime-kotlin/runtime-domain/scaffold/model, platform-packs/kmp
- `platform-pack-schema.yaml` now has anchored `code_review_composition.baseline_layers` for code-review composition; nested layer objects stay strict, `scope` is `"same-review-scope"`, and `required` is explicit. reusable: runtime-consumed top-level pack fields still start in schema with `x-runtime-anchored: true`.
- `PlatformManifest` now carries typed `CodeReviewComposition` / `CodeReviewBaselineLayer` values; `code_review_composition` is filtered out of `customFields` like other anchored fields.
- `ShellContentLoader` parses composition in `buildPack` and validates references after manifests are loaded; collection discovery validates all packs, and `loadPlatformPack` validates the reachable composition closure so single-pack callers cannot bypass missing-target, duplicate, self-reference, or cycle failures. reusable
- `kmp-baseline` mode is intentionally narrow: accepted only for `kotlin/bill-kotlin-code-review`, not for same-named skills in other packs or Kotlin specialist skills.
- KMP's manifest declares the Kotlin baseline layer; this only records the contract source of truth and does not generate runtime `SKILL.md` instructions yet.
- Regression coverage lives in `PlatformPackCompositionTest`, plus anchored bijection/customFields tests; cleanup test now skips migrated source paths that no longer exist.
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-05-19] SKILL-48 subtask-3 per-repo-schema-customization
Areas: orchestration/contracts, runtime-kotlin/runtime-core/scaffold, runtime-kotlin/runtime-domain/scaffold/model, AGENTS.md
- `orchestration/contracts/platform-pack-schema.yaml` now treats the TOP-LEVEL mapping as the per-repo extension surface: `additionalProperties: true` at root. Every TOP-LEVEL field the Kotlin runtime consumes by name carries `x-runtime-anchored: true` (10 total: `platform`, `contract_version`, `display_name`, `notes`, `routing_signals`, `declared_code_review_areas`, `declared_files`, `area_metadata`, `declared_quality_check_file`, `pointers`). Nested objects (`routing_signals`, `declared_files`, `area_metadata.<area>`, `$defs.codeReviewArea`, `pointers` entries) stay strict (`additionalProperties: false`) — per-repo extensions only relax the top-level mapping. reusable: the `x-runtime-anchored` marker is exclusive to platform-pack-schema; other runtime contracts (telemetry/workflow/install/native-agent) stay runtime-anchored end-to-end.
- New file-level helper in `PlatformPackSchemaValidator.kt`: `internal fun anchoredTopLevelFieldNames(): Set<String>` delegating to a file-private `ANCHORED_TOP_LEVEL_FIELD_NAMES: Set<String>` `by lazy { ... }`. Single parse of the bundled YAML; lazy init throws `InvalidManifestSchemaError` if the schema is missing the top-level `properties` node. Schema is the single source of truth — Kotlin never hardcodes the anchored set. reusable: any future schema that needs a "list of anchored property names" can mirror this lazy + JsonNode.asBoolean(false) pattern.
- `PlatformManifest` (`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/scaffold/model/ScaffoldModels.kt`) gains `customFields: Map<String, Any?> = emptyMap()`. Default keeps the only construction site (`ShellContentLoader.buildPack`) and every named-arg consumer compiling unchanged. Intentionally untyped per the parent spec — pack authors get raw SnakeYAML-parsed values, no code-gen.
- `validateAgainstCanonicalSchema` in `ShellContentLoader.kt` now RETURNS the validated `Map<String, Any?>` (the typed manifest map). `buildPack` reuses it to derive `customFields = typedManifest.filterKeys { it !in anchoredKeys }`. This avoids re-walking the raw `Map<*, *>` and re-doing the non-string-key shape check.
- A5(b) is enforced in Kotlin, not the schema. Required anchored fields (`platform`, `contract_version`, `routing_signals`, `declared_code_review_areas`) catch typos via JSON Schema `required`; OPTIONAL anchored fields (`display_name`, `notes`, `declared_files`, `declared_quality_check_file`, `area_metadata`, `pointers`) would otherwise slip silently into `customFields` because top-level `additionalProperties` is now `true`. File-private `guardAgainstAnchoredFieldTypos` in `ShellContentLoader.kt` walks every customFields key against the anchored set and loud-fails with `InvalidManifestSchemaError("...top-level field '<key>' that looks like a typo of the anchored field '<anchored>' (did you mean '<anchored>'?)...")`. Edit-distance computed by file-private `levenshtein1` split into `substitutionMatches` + `insertionOrDeletionMatches` helpers to satisfy detekt `NestedBlockDepth`/`ReturnCount` at root (no suppressions). reusable: typo-guard pattern fits any future "additive open object" schema where named keys remain reserved.
- Bijection test `PlatformPackSchemaAnchoredBijectionTest` (mirrors `TelemetryEventInputSchemaParityTest` two-direction shape) curates the Kotlin side via an `expectedAnchoredFields` constant rather than reflecting `PlatformManifest` properties — YAML snake_case vs Kotlin camelCase + derived properties (e.g. `routedSkillName`) make 1:1 reflection brittle. Failure message names the asymmetric difference (which side is missing what). reusable: curated-constant + two-direction parity is the right fit when a mechanical mapping is non-trivial.
- A5(b) coverage: TWO tests in `PlatformPackSchemaViolationsTest.kt` — `SKILL-48 nested anchored block typo fails loudly` (typo `baselin` under `declared_files`; fires via nested `additionalProperties: false`) and `SKILL-48 Subtask 3 typo on anchored top-level field fails loudly with field path` (top-level `declared_filez:`; fires via the Kotlin Levenshtein guard, message asserts BOTH the offending key AND the suggested anchored field). Defense in depth: keep both.
- `PlatformPackCustomFieldsRoundTripTest` covers A5(a). Fork-specific keys `custom_thing` (nested map) and `another_custom` (string) round-trip via `customFields` verbatim. The boring path (stock manifest → empty `customFields`) is also pinned to guard against accidental anchored leak.
- AGENTS.md "Governed platform packs" section now documents the per-repo customization contract inline (single bullet — covers top-level extension, x-runtime-anchored, customFields, loud-fail on optional-anchored typos, nested strictness, intentional untyped Map). Pairs with subtask-2's "every schema under orchestration/contracts/" rule in the same section.
- Reviewer-deferred Nits (none blocking): F-004 (`PlatformPackCustomFieldsRoundTripTest.newTempPackRoot` doesn't register cleanup — switch to `@TempDir`), F-005 (`PlatformPackSchemaAnchoredBijectionTest.expectedAnchoredFields` could be derived by grepping `buildPack` source). Reviewer triage record: rvw-20260519-174330-s48c.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-19] SKILL-48 runtime-contracts-2d telemetry-event-schema
Areas: orchestration/contracts, runtime-kotlin/runtime-mcp/mcp, runtime-kotlin/runtime-contracts/error, runtime-kotlin/runtime-mcp build, runtime-kotlin/runtime-mcp tests
- FIFTH runtime contract under the same architecture as 2a/2b/2c: `orchestration/contracts/telemetry-event-schema.yaml` (Draft 2020-12) is the SSOT for every MCP `tools/call` envelope. `TELEMETRY_EVENT_CONTRACT_VERSION` ("1.0.0") + `TelemetryEventSchemaPaths` (`runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/TelemetryEventSchemaPaths.kt`) pin the const; `TelemetryEventSchemaContractVersionTest` pins the constant ↔ top-level YAML `contract_version` ↔ EXPECTED_SCHEMA_ID ↔ every per-branch `contract_version.const`. reusable
- Validator placement DEVIATES from 2a/2b/2c: `TelemetryEventSchemaValidator` (`runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/TelemetryEventSchemaValidator.kt`) lives in `runtime-mcp` because `McpToolRegistry.toolNames` + `inputSchemas` is the source-of-truth for event names and the parity test must co-locate. Gradle `copyTelemetryEventSchema` therefore lives in `runtime-mcp/build.gradle.kts`. Side effect: outbound emit seams in `runtime-application`/`runtime-infra-*` cannot reuse this validator without inverting the module graph — schema validates *MCP envelopes*, not *outbound telemetry*. Worth a follow-up to lift validator + Paths into `runtime-contracts` (typed error already lives there) if a non-MCP emitter ever needs to validate.
- 37-event discriminated-union pattern: top-level `oneOf` + `$defs/<event>Event` keyed on `event_name.const` (mirrors 2a's workflow_name-keyed precedent). Strict events mirror `McpToolRegistry.inputSchemas` exactly; events that fall through to `openObjectSchema()` get a branch with `additionalProperties: true`; passthrough events declare properties+required even though additionalProperties is open. Top-level `additionalProperties: false` is INTENTIONALLY OMITTED: per-branch consts + oneOf+discriminator enforce shape, and a top-level closed-object would double-reject payload fields already accepted by a branch. Documented in `x-coherence-checks`. reusable
- Parity test (`TelemetryEventInputSchemaParityTest`) walks `McpToolRegistry.tools` DYNAMICALLY (no hard-coded count) in BOTH directions — Kotlin→YAML (every tool has a branch) and YAML→Kotlin (every branch maps to a known tool). Enforces event_name const + additionalProperties policy + property keys + required keys. Per-property TYPE equivalence is NOT enforced (documented tradeoff); the `TelemetryEventSchemaValidatesAllEventsTest` representative-payload check catches type drift incidentally via networknt validation.
- Classpath-shadow guard CANNOT extend `PlatformPackSchemaCleanupTest.kt` (the runtime-core canonical home) because runtime-core does not depend on runtime-mcp and importing the validator would invert the module graph. Resolution: sibling `TelemetryEventSchemaCleanupTest.kt` in `runtime-mcp/src/test`. The pattern now lives in two places — runtime-core's cleanup test for schemas in `runtime-core`/`runtime-domain`; runtime-mcp's cleanup test for schemas owned by `runtime-mcp`. reusable: cleanup-test placement follows the validator's home module.
- McpStdioServer catch ladder WIDENED uniformly: removed the narrow `InvalidTelemetryEventSchemaError` arm; added `catch (ShellContentContractException)` parent arm (subsumes the four typed schema errors — install-plan, workflow-state, native-agent-composition, telemetry-event) + final defensive `catch (Exception)`. `Throwable` was rejected via review F-101: java.lang.Error subtypes (OOM, NoClassDefFoundError, AssertionError) and future coroutine `CancellationException` must propagate to crash the process. reusable: this is now the canonical catch ladder for the MCP stdio loop.
- Unified argument-shape error contract: `validateStrictArguments` failures (formerly JSON-RPC `-32602 INVALID_PARAMS`) now route through MCP `isError=true` (200-OK with error payload), matching the schema validator's surface. Both paths produce the same transport shape for any "request structurally invalid for the named tool" fault. Documented in `x-coherence-checks.argument-shape-failures-surface`; `-32602` is now reserved for transport-level protocol violations only. reusable
- F-002 audit (committed as comment near `TelemetryEventSchemaValidator.validate(...)` in `McpToolDispatcher.kt`): every in-tree native emitter supplies all required schema fields. The dispatcher has exactly ONE production caller (`McpStdioServer.callToolResult`) which threads JSON-RPC arguments straight through; handler-level defaults (`arguments.int(name, 0)`, `arguments.string(name) -> ""`, `arguments.boolean(name) -> false`) construct typed models AFTER schema validation already accepted the payload — they don't mask missing required keys from real emitters. Loud-fail at the dispatcher seam is safe without a WARN-and-pass phase.
- `loadSchema()` wraps any non-typed cause (`JsonParseException`, `IOException`, networknt compile failure) in `InvalidTelemetryEventSchemaError(fieldPath="<root>", eventName=null, reason=..., cause=original)` so a corrupted classpath YAML surfaces as a typed error through the widened catch ladder rather than dying on the stdio loop. Detekt rule `InstanceOfCheckForException` forced splitting the `try { ... } catch (Throwable) { if (is InvalidTelemetryEventSchemaError) ...}` into two typed catch blocks (typed rethrow first, generic wrap second). reusable: any future schema validator should mirror this two-arm wrapping pattern.
- RuntimeArchitectureTest enforces NO `java.nio.file.Files` usage in `runtime-mcp` adapters: the validator relies solely on the classpath resource bundled by `copyTelemetryEventSchema`; the on-disk schema-walk fallback was removed. KDoc that incidentally mentioned the banned FQN was rephrased because the architecture test scans source text including comments. reusable: rule applies to any future MCP-adapter-owned validator.
- Reviewer-deferred Minor/Nit follow-ups (none blocking): F-005 (telemetryEnvelope strips reserved keys from envelope but not arguments — asymmetric stripping), F-006 (parity test short-circuits for passthrough additionalProperties:true), F-007 (per-field enum sets not compared; validates-all picks enum.first()), F-008 (no end-to-end dispatcher-seam negative test), F-009 (formatValidationReason iterates violations uncapped — exception message can balloon), F-010 (unknown-event_name assertion uses near-tautological "schema" signal), F-011 (open-event additionalProperties:true never exercised in a test), F-012 (assertIdentity doesn't check per-branch contract_version.const), F-013 (validator not warmed at process start), F-014 (validator placement vs outbound emit seams), F-015 (cleanup-test pattern split across two files), F-016 (OpenAPI-3 `discriminator` keyword is ignored by networknt — move to `x-discriminator` or document), F-017 (open events lack documentation hints), F-018 (InvalidTelemetryEventSchemaError message embeds free-form networknt reason; not version-stable), F-019 (loadSchema YAML→JSON-string round-trip), F-020 (Regex compiled per-segment on failure path), F-021 (cleanup test does not exercise real loadSchema classpath path), F-022 (x-coherence-checks named rules not contract-tested), F-102 (lazy SEVERE log claim of boot-time signal is inaccurate — fires on first validate). Reviewer triage record: rvw-20260519-162500-a2d4.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-05-19] SKILL-48 runtime-contracts-2c native-agent-composition-schema
Areas: orchestration/contracts, runtime-kotlin/runtime-core/nativeagent, runtime-kotlin/runtime-contracts/error, runtime-kotlin/runtime-core build, runtime-kotlin/runtime-core scaffold tests
- FOURTH runtime contract under the same architecture as 2a/2b: `orchestration/contracts/native-agent-composition-schema.yaml` (Draft 2020-12) is the SSOT for the native-agent envelope. `NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION` ("0.1") + `NativeAgentCompositionSchemaPaths` (`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/NativeAgentCompositionSchemaPaths.kt`) pin the const; parity test pins schema const ↔ Kotlin constant ↔ EXPECTED_SCHEMA_ID. reusable
- Validator placement DEVIATES from 2a/2b: `NativeAgentCompositionSchemaValidator` (`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/NativeAgentCompositionSchemaValidator.kt`) lives in `runtime-core` (NOT `runtime-domain`) because its only callers are `parseNativeAgentBundle` / `parseNativeAgentSourceFile` already in runtime-core; matches the in-module `PlatformPackSchemaValidator` precedent. Gradle `copyNativeAgentCompositionSchema` therefore lives in `runtime-core/build.gradle.kts` next to `copyPlatformPackSchema` — Copy-task placement rule: live in the same module as the validator that loads the classpath resource. reusable
- DUAL-SEAM rationale DIVERGES from 2b: 2b validated at builder + CLI emission (two emission seams); 2c validates at two SOURCE-format seams — bundle YAML (`parseNativeAgentBundle`) and single-md frontmatter (`parseNativeAgentSourceFile` → `parseNativeAgentSourceText`). Two on-disk wire formats, one schema, two parse paths.
- Schema dual-shape via top-level `oneOf` + `$defs/agentEntry`: Branch A = bundle `{agents: [agentEntry, ...] (minItems 1), contract_version?}`, Branch B = single-md frontmatter (inlined agentEntry + optional contract_version). Dispatch is reliable: both branches use `additionalProperties: false`; bundle envelope can only match A, single-md can only match B, ambiguous documents fail "matches 0 of 2".
- CRITICAL DESIGN DECISION (Option a): top-level `contract_version` is OPTIONAL on every envelope (not in `required`); the `const` only enforces value when the key is present. Existing fixtures across `skills/**/native-agents/` and `platform-packs/**/native-agents/` carry NO `contract_version` today and validate clean. `parseSimpleFrontmatter` whitelist in `NativeAgentSource.kt` was expanded to accept `contract_version` so future writes including it don't get rejected by the parser. Schema describes what is emitted today. reusable: "optional-pin, const-when-present" pattern is the right fit for migrating an existing on-disk format toward a runtime contract without forcing a fleet rewrite.
- VALIDATOR-RUNS-AFTER-MANUAL-CHECKS deviation: schema validation fires AFTER existing `require(...)` checks in both seams (defense-in-depth backstop), NOT before. Rationale: preserves 12 existing parser tests that pin `IllegalArgumentException` messages naming the offending key. Today the typed `InvalidNativeAgentCompositionSchemaError` is observable in practice only on `contract_version` const drift (every other envelope violation trips the manual `require` first). Dedicated `NativeAgentCompositionSchemaViolationsTest` bypasses the manual checks and calls the validator directly so AC4/AC5 typed-error contracts are still proven. Open question: should AC4 expect the typed error on every envelope violation, or is "schema-layer = contract_version-only backstop" the intended semantics? Worth a one-line AGENTS.md policy.
- SINGLE-MD seam asymmetry: bundle seam passes raw YAML text to the validator; single-md seam validates a hand-built `JsonNode` constructed from the parsed frontmatter map via `validateParsedNode`. Necessary because the simple line-by-line frontmatter parser tolerates descriptions containing colons that Jackson's general YAML parser would reject as ambiguous. Side effect: schema's `additionalProperties: false` on Branch B can never catch an unknown frontmatter key the schema doesn't already know about — the parser's manual whitelist is the only defense. Two parallel sources of truth for "allowed single-md keys" — keep them in lockstep. reusable: when a custom parser deliberately accepts looser input than a general YAML parser would, the schema layer cannot police what the parser never surfaces.
- Error class `InvalidNativeAgentCompositionSchemaError` (`runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/ShellContentContractErrors.kt`) mirrors `InvalidInstallPlanSchemaError`'s shape (`sourceLabel`/`fieldPath` + `reason` + cause) — typed properties, not just message.
- Gradle `copyNativeAgentCompositionSchema` reuses the F-101 String-capture + `inputs.file` + `doFirst { require(File(path).exists()) }` pattern from 2a/2b; configuration cache reused on re-run. Schema auto-listed in desktop "Contracts" tree via 2a's `Files.list(orchestration/contracts/*.yaml)` — no desktop code edit needed.
- Classpath-shadow guard block added to `PlatformPackSchemaCleanupTest.kt` (NOT a new file) — that file is now the canonical home for per-schema shadow assertions across all four runtime contracts. reusable: extend, don't duplicate.
- Schema enum strings quoted (`- "governed-content"`) per 2b's SnakeYAML YAML-1.1 boolean-coercion pitfall. Continues to apply to any new enum in `orchestration/contracts/*.yaml`.
- Reviewer-deferred Minor/Nit follow-ups (none blocking): F-001 (single-md JsonNode bypass risk vs `additionalProperties: false`), F-002 (AC4 typed-error coverage = contract_version-only in practice — needs AGENTS.md policy), F-003 (Branch A/B inline property parity test), F-004 (per-violation tests for `name` regex / `description` minLength / `agents` minItems), F-005 (direct-validator pass in `NativeAgentCompositionValidatesExistingBundlesTest` to disambiguate parser vs schema failures), F-006 (single-md missing-name violation case), F-007 (extract shared `SchemaLoader<E>` helper to deduplicate across the three validators), F-008/F-009 (document Copy-task placement and validator-ordering policies once centrally), F-010 (strengthen `unknown_top_level_property` assertion), F-011 (require fixtures to quote `contract_version: "0.1"` to avoid cross-envelope Double-vs-string divergence).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-19] SKILL-48 runtime-contracts-2b install-plan-schema
Areas: orchestration/contracts, runtime-kotlin/runtime-domain/install, runtime-kotlin/runtime-contracts/error, runtime-kotlin/runtime-cli/install, runtime-kotlin/runtime-core/install, runtime-kotlin/runtime-domain build
- THIRD runtime contract landed under the same architecture as 2a: `orchestration/contracts/install-plan-schema.yaml` (Draft 2020-12) is the SSOT for the install-plan wire payload. `INSTALL_PLAN_CONTRACT_VERSION` + `InstallPlanSchemaPaths` (`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaPaths.kt`) pin the const; parity test pins schema const ↔ Kotlin constant. reusable
- Validator placement mirrors 2a: `InstallPlanSchemaValidator` (`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaValidator.kt`) lives in `runtime-domain` next to `InstallModels.kt`. Module-public `assertIdentity(yamlText)` enables cross-module shadow-guard tests; classpath-shadow guard rejects mismatched `$id` or `contract_version.const`. Bounded WARN log + SEVERE on schema-load failure preserved.
- Shared wire-map helper `buildInstallPlanWireMap` (`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanWireMap.kt`) is the single source of the install-plan wire shape. DELIBERATELY DIVERGES from 2a single-seam pattern: validates at BOTH `buildInstallPlan` (`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/install/InstallPlanBuilder.kt`) AND CLI emission (`installPlanPayload` in `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/InstallCliPayloads.kt`) per AC4 — decision recorded in `runtime-kotlin/agent/decisions.md`. Adds `contract_version` to the wire shape — observably the only key change. reusable
- Schema YAML pitfall: SnakeYAML follows YAML 1.1 and coerces bare `off`/`on`/`yes`/`no` to booleans, so an enum authored as `- off` advertises `[..., false]` while the runtime emits the string `"off"` — silent contract drift that loud-fails only at validation. ALWAYS quote enum string values in `orchestration/contracts/*.yaml` (`- "off"`). reusable
- Error class `InvalidInstallPlanSchemaError` (`runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/ShellContentContractErrors.kt`) carries `fieldPath` + `reason` as typed properties (not just message), so tests can assert the offending location without parsing strings.
- Gradle `copyInstallPlanSchema` Copy task in `runtime-kotlin/runtime-domain/build.gradle.kts` reuses the configuration-cache-friendly `inputs.file + doFirst` pattern from 2a; both `processResources` and `processTestResources` now depend on it. Schema auto-listed in desktop "Contracts" tree via 2a's `Files.list(orchestration/contracts/*.yaml)` — no desktop code edit needed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-19] SKILL-48 runtime-contracts-2a workflow-state-and-auto-listing
Areas: orchestration/contracts, runtime-kotlin/runtime-domain/workflow, runtime-kotlin/runtime-contracts/error, runtime-kotlin/runtime-application/workflow, runtime-kotlin/runtime-desktop/core/data, runtime-kotlin/runtime-domain build
- Extended the platform-pack schema architecture to a SECOND runtime contract: `orchestration/contracts/workflow-state-schema.yaml` (Draft 2020-12) is now the SSOT for `WorkflowStateSnapshot`; `WORKFLOW_STATE_CONTRACT_VERSION` pins it; parity test pins schema const ↔ Kotlin constant ↔ `FeatureImplement/VerifyWorkflowDefinition.contractVersion` (three sources). reusable
- Per-skill enum divergence handled via JSON Schema `oneOf` keyed on `workflow_name` const, not flat enum. FeatureImplement (12 steps / 6 statuses incl. `blocked`) and FeatureVerify (8 steps / 5 statuses) each get a `$defs` branch. Adding a third workflow family later requires editing three coupled sites (top enum + new $defs branch + new oneOf entry). reusable
- Validator placement DEVIATES from the platform-pack mirror: `WorkflowStateSchemaValidator` lives in `runtime-domain/workflow` (not `runtime-core/scaffold`) because `WorkflowEngine` is its primary consumer and `runtime-core` already api-depends on `runtime-domain`. Tests still live in `runtime-core/src/test/kotlin/skillbill/scaffold/` via existing `testFixtures` capability. `assertWorkflowStateSchemaIdentity` is module-public (not internal) for cross-module shadow-guard test access.
- F-101 follow-up adopted day one: `runtime-domain/build.gradle.kts` Copy task uses `inputs.file(schemaPath)` + `doFirst { require(file.exists()) {...} }` — NO configure-time `File.exists()` reads. Configuration cache reused on every run. reusable: this is now the canonical pattern for any future `orchestration/contracts/*.yaml` Copy task.
- Desktop auto-listing: `RuntimeRepoBrowserService.loadContracts` switched from a hard-coded single leaf to `Files.list(orchestration/contracts/*.yaml)` sorted alphabetically, with label derivation by suffix-strip + dash-replace + title-case of first character. Wrapped in `runCatching` so an IO failure on the contracts dir degrades ONLY the Contracts group, not the whole repo tree. New YAMLs surface automatically with no code change. reusable
- Observability: validator emits a bounded WARN log on validation failure (slug + first 1-2 dotted field paths + offending values + violation count) before throwing `InvalidWorkflowStateSchemaError`, plus SEVERE on schema-load failure naming the classpath resource. Loud-fail preserved; payload bodies never logged.
- Backward-compat: read-seam loud-fail intentionally rejects pre-existing durable rows whose `workflow_status`/`current_step_id` no longer match the new per-skill enums; operators recover by deleting/migrating affected rows. AGENTS.md D2 B-half rule paragraph + `WorkflowRecordMapping.toSnapshot` KDoc document the recipe.
- Pre-existing bug surfaced and fixed in `FeatureVerifyWorkflowRuntimeTest`: it was constructing `workflow_status="blocked"` for the verify family which has no such status. Rewritten to `workflow_status="running"` with `step.status="blocked"` (the actual scenario the test exercises — continueDecision reopening a blocked step). No production code path produces the invalid combination; the validator now prevents future regressions.
- `WorkflowRecordMapping.toSnapshot` is intentionally non-validating; every consumer in `WorkflowService` (open/update/get/list/latest/resume/continue) funnels through `WorkflowEngine.{full,summary,resume}Payload` which validates BEFORE returning. KDoc documents the seam-coverage invariant.
- Reviewer-deferred Minor/Nit follow-ups: F-001 (drop two internal const-val aliases mirroring PlatformPackSchemaValidator anti-pattern), F-002 (architecture test pinning seam-coverage invariant), F-004 (parity test pinning workflow_name enum ⊇ oneOf branch consts), F-204 (assertContains dotted path on more violation tests), F-302 narrow runCatching catch from Throwable to Exception, F-501 E2E unreadable-dir test for Contracts group degradation, F-502 narrow loadSchema catch from Throwable to Exception.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-19] SKILL-48 skill47-cleanup
Areas: runtime-kotlin/runtime-core/scaffold, runtime-kotlin/runtime-domain/scaffold, runtime-kotlin/runtime-desktop (core/data, feature/skillbill), orchestration/contracts, runtime-kotlin/runtime-core build
- Cross-boundary validator signature tightened: `PlatformPackSchemaValidator.validate(parsedYaml: Map<String, Any?>, slug: String)` replaces the legacy `Any?` form; `ShellContentLoader.validateAgainstCanonicalSchema` now loud-fails (reusing `InvalidManifestSchemaError`) on non-String top-level YAML keys before reaching the validator. Pattern: push shape/type invariants into the SHAPE-check helper at the boundary, not into the next layer.
- New classpath-shadow guard: `assertSchemaIdentity` (called from the lazy schema load) verifies the loaded YAML's `$id` matches `PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID` AND its `contract_version.const` matches the Kotlin `SHELL_CONTRACT_VERSION`. Either mismatch throws `InvalidManifestSchemaError` naming both values. Downstream JARs shipping a stale classpath copy now loud-fail at first validator use. reusable: same `EXPECTED_*` + `$id`+`const` pattern transfers to any future schema bundled via classpath.
- Build-time guard: `runtime-core/build.gradle.kts` configure-time `require(canonicalPlatformPackSchema.exists())` makes a misconfigured build fail before any task runs (caveat: this is a configuration-cache-unfriendly `File.exists()` read; a follow-up should move it to `copyPlatformPackSchema.doFirst {}` or declare it as a tracked `inputs.file` — see F-101 / SKILL-48 follow-ups).
- Schema-vs-Kotlin SSOT split clarified (C1): deleted the duplicated Kotlin `content.md`-suffix and pointer-name `.md`/`..`/`/` checks; kept `requireSafePointerSubpath` and `requireSafePointerTarget` because the schema does NOT express absolute-vs-relative or full path/`..`-segment semantics. Each kept guard now carries a comment explaining the duplication rationale. reusable: shape stays in YAML, semantic invariants stay in Kotlin with documented "why-kept" comments.
- `declared_code_review_areas` gets `uniqueItems: true`; duplicate areas are now rejected at the schema layer rather than silently deduped downstream via `toSet()`.
- Shared test helper consolidated via `java-test-fixtures` on runtime-core: `skillbill.testing.repoRootFromTest()` (in `src/testFixtures/kotlin/skillbill/testing/RepoRoot.kt`) replaces five local copies. runtime-desktop KMP leaf modules (core/data, feature/skillbill) consume it through the `dev.skillbill:runtime-core-test-fixtures` capability dependency — the `testFixtures(project(...))` shorthand is unavailable under the KMP DSL. reusable: capability-dep pattern for any future test-support shared across runtime-core ↔ KMP-leaf modules.
- KDoc on `PlatformManifest.routedSkillName` and `DeclaredFiles.baseline` documents the intentional nullable contract: nullable means absent/not-applicable at the manifest layer; consumers MUST NOT re-narrow (no `!!`, no platform-side defaulting hiding absence).
- Reviewer-deferred follow-ups (all Minor; not blocking): F-101 (move configure-time `require` to `doFirst`); F-103 (distinguish missing / non-text / empty in `assertSchemaIdentity` error message); F-205 (replace C8 hardcoded scan list with a glob walk over `runtime-kotlin/**/src/{test,jvmTest}/**/*.kt`).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-19] SKILL-47 platform-pack-schema-source-of-truth
Areas: runtime-kotlin/runtime-core/scaffold, runtime-kotlin/runtime-domain/scaffold, orchestration/contracts, runtime-kotlin/runtime-core build
- `orchestration/contracts/platform-pack-schema.yaml` is the single canonical JSON Schema (Draft 2020-12, authored in YAML) describing `platform-packs/<slug>/platform.yaml`. New fields land in the schema first; the parser consumes it. reusable
- `ShellContentLoader.buildPack` delegates shape validation to `CanonicalPlatformPackSchemaValidator` (lazy singleton, JsonSchema cached). SnakeYAML → Jackson `JsonNode` bridge; networknt 1.5.x runs the validation. Errors are reformatted to typed `InvalidManifestSchemaError` messages that NAME the offending field path (AC8). Contract-version `const` violation surfaces directly as `ContractVersionMismatchError` from the validator — loud-fail uniform across `loadPlatformManifest` and `loadPlatformPack`.
- Five cross-field rules stay in Kotlin as named coherence checks, listed verbatim in the schema's `x-coherence-checks` prose block: `slug-parity`, `areas-require-baseline`, `areas-equal-declared` (bijection both directions), `area-metadata-keys-subset-declared`, `pointers-unique-name-per-dir`. Schema-only rules (type, enum, `content.md` suffix pattern, pointer name/target pattern) live exclusively in the YAML.
- Code-review area enum is declared once under `$defs/codeReviewArea` and `$ref`'d from `declared_code_review_areas.items`, `declared_files.areas.propertyNames`, `area_metadata.propertyNames`. `PlatformPackSchemaContractVersionTest` asserts BOTH `contract_version` parity vs `SHELL_CONTRACT_VERSION` AND `$defs/codeReviewArea/enum` parity vs `APPROVED_CODE_REVIEW_AREAS` (both directions) — neither can drift.
- `PlatformPackSchemaPaths { REPO_RELATIVE_PATH; CLASSPATH_RESOURCE }` is the single source of truth for the schema location. Build-time Gradle Copy task lands the canonical YAML into runtime-core resources under `skillbill/contracts/`; runtime loader tries classpath first, falls back to walking up from a path hint. Desktop, validator, and tests all reference the constant — no hardcoded path strings.
- `DeclaredFiles.baseline` and `PlatformManifest.routedSkillName` widened to nullable; the in-progress branch relaxations (`baseline` / `declared_files` / `area_metadata` all optional) are preserved by schema + parser. `parseDeclaredFiles` returns `null` for fully-omitted blocks; `areas-require-baseline` coherence still gates non-empty `areas`. Every consumer was updated to null-check.
- Tests: `PlatformPackSchemaValidatesExistingPacksTest` proves the schema validates both shipped packs as-is; `PlatformPackSchemaViolationsTest` has one discrete test per documented violation including the 5 named coherence rules and split pointer cases (name-without-.md, name-with-.., target-with-..); `PlatformPackSchemaContractVersionTest` pins both parity invariants.
- Reusable: schema-first validation pattern (`x-coherence-checks` prose + Kotlin enforcement); single `PlatformPackSchemaPaths` object pattern when a shared path constant needs to cross module boundaries; build-time `Copy` task to expose a repo-root contract on the runtime classpath; networknt + Jackson + SnakeYAML stack for validating YAML via JSON Schema.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-17] final-integration-docs-validation
Areas: runtime-kotlin runtime-core install, runtime-cli install, runtime-desktop packaging/runtime lookup, docs
- Final SKILL-45 integration kept the runtime install contract unchanged: CLI, install.sh, and desktop all route through typed install plan/apply instead of parsing shell output. reusable
- Documentation now names package tasks, app-resource runtime bundle lookup, `~/.skill-bill/installed-skills` staging, telemetry anonymous/full/off, MCP intent, and Windows Developer Mode/elevated-shell guidance.
- Coverage audit found existing focused tests for plan/apply, shell delegation argv, dynamic platform selection, telemetry/MCP, Windows symlink outcomes, desktop gateway state, runtime asset lookup, and package task wiring; no new test files were needed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-17] desktop-packaging-runtime-bundling
Areas: runtime-kotlin/runtime-desktop packaging, runtime-desktop/core/data first-run gateway, runtime-cli installDist, runtime-mcp installDist
- Desktop packages now stage a loose `skill-bill-runtime` app-resource bundle from authored `skills`, dynamic `platform-packs`, `orchestration`, and packaged runtime-cli/runtime-mcp installDist outputs. reusable
- `JvmRuntimeAssetLocator` resolves runtime assets from dev checkouts, explicit `skillbill.runtime.assets.dir` / `SKILL_BILL_RUNTIME_ASSETS`, or Compose installed resources before the gateway builds shared install plans. reusable
- First-run install planning now feeds resolved `skillsRoot`, `platformPacksRoot`, runtime distribution dirs, and packaged runtime-mcp bin into the existing typed install model while preserving `~/.skill-bill/installed-skills` staging and Windows symlink outcomes.
- Packaging task wiring pins DMG/MSI/Deb/RPM targets and makes `prepareAppResources` plus package tasks depend on runtime bundle staging so native packages cannot race an empty app resource directory.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-05-17] install-migration-validation
Areas: runtime-kotlin/runtime-cli install tests, runtime-kotlin/runtime-core install/architecture tests, runtime-domain install model
- Added final validation coverage for install migration contracts: CLI plan/apply payload mapping, manual/detected agents, telemetry anonymous/full/off, MCP intent, Windows symlink messages, and staging-cache boundaries.
- Future CLI install tests should keep driving `CliRuntime` JSON payloads instead of adding filesystem behavior to CLI adapters. reusable
- Dynamic platform coverage now includes a newly discovered `python` pack in plan-builder and install.sh delegation tests so selected-platform behavior is not tied to the built-in pack set.
- Staging assertions now pin rendered `SKILL.md` and support pointers under `~/.skill-bill/installed-skills`, preserving the generated-output boundary.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-17] install-sh-runtime-delegation
Areas: install.sh, runtime-kotlin/runtime-cli install commands, runtime-kotlin/runtime-core install apply/native-agent cleanup
- `install.sh` now owns prompting/runtime distribution only and delegates typed install apply to the durable runtime CLI, including manual/detected agents, platform modes, telemetry, MCP, runtime dirs, and replacement cleanup. reusable
- Runtime apply gained `replaceExistingSkillBillLinks` cleanup for current and legacy Skill Bill links so base-only or selected reinstall removes stale platform and renamed skill entries before relinking staged skills. reusable
- Native-agent replacement cleanup now unlinks manifest-declared deselected platform agents from both current staged output and the legacy generated cache before linking selected provider artifacts.
- Regression coverage moved shell argv execution and replacement cleanup into focused tests; future install.sh changes should assert the single `install apply` argv rather than reintroducing shell-owned install loops.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-17] runtime-cli-install-plan-apply
Areas: runtime-kotlin/runtime-cli install commands, runtime-kotlin/runtime-core install plan/apply contract
- Added stable `skill-bill install plan` and `skill-bill install apply` entrypoints that build `InstallPlanRequest`, call `InstallOperations.planInstall` / `applyInstall`, and keep CLI handlers as thin parse/render adapters. reusable
- CLI inputs now cover detected/manual `copilot`, `claude`, `codex`, `opencode`, `junie`, platform pack none/selected/all, telemetry anonymous/full/off, MCP registration choices, and Windows symlink preflight state.
- Plan/apply JSON/text payloads are rendered from structured runtime outcomes, including base-skill inclusion, dynamic platform discovery, MCP/telemetry intent, warnings/failures, and Windows symlink guidance.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-16] validation-contract-coverage
Areas: runtime-kotlin/runtime-core install plan/apply tests, runtime-kotlin/runtime-cli install tests, runtime-domain install model
- Added final regression coverage for shared install plan/apply contracts: plan inputs/outputs, dynamic platform discovery, base-skill inclusion, staging-cache/source immutability, telemetry levels, MCP intent/outcomes, and Windows symlink guidance.
- New focused `InstallPlanContractCoverageTest` keeps plan assertions at the typed contract boundary without applying side effects; future install changes should extend this instead of parsing CLI text. reusable
- Apply coverage now asserts telemetry/MCP no-side-effect behavior on preflight failure and selected all-agent behavior distinguishes Copilot skill links from Claude/Codex/OpenCode/Junie native-agent providers.
- Validation loop fixed Spotless formatting after review/audit and confirmed the Kotlin Gradle gate passes; remaining known limitation is Minor follow-up to strengthen all-agent MCP config-file assertions.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-16] telemetry-mcp-adapter-wiring
Areas: runtime-kotlin/runtime-domain install model, runtime-kotlin/runtime-core install apply, runtime-kotlin/runtime-core launcher MCP registration, runtime-kotlin/runtime-application telemetry
- Shared `InstallOperations.applyInstall` now executes typed telemetry intent through existing anonymous/full/off semantics and returns `InstallTelemetryApplyOutcome` instead of requiring shell-output parsing. reusable
- Apply executes or skips typed MCP registration intent per supported `InstallAgent`, returns per-agent `McpRegistrationApplyOutcome`, and keeps MCP/telemetry setup failures as structured non-fatal warnings like `install.sh`. reusable
- Apply side effects use the plan-owned home with an empty environment so ambient telemetry env vars cannot redirect the shared install contract; legacy launcher `glm` support remains outside `InstallAgent.supportedIds`.
- Focused tests cover telemetry full/off/success/skip/failure, MCP success/skip/failure, and warning aggregation; CLI command migration and desktop setup UI remain deferred.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-16] apply-staging-agent-links
Areas: runtime-kotlin/runtime-domain install model, runtime-kotlin/runtime-core install, runtime-kotlin/runtime-core nativeagent, runtime-kotlin/runtime-cli install
- Added typed `InstallOperations.applyInstall` and structured apply outcomes for staging, skill links, native-agent links, Windows symlink states, telemetry/MCP intent carry-through.
- Apply validates planned staging hash/dir before linking and fails stale plans instead of installing changed source; native-agent apply renders only selected planned skill roots. reusable
- Native-agent apply reuses render/link operations with request/override objects, materializes apply-time provider artifacts under `~/.skill-bill/installed-skills`, and preserves legacy generated-cache links only for replacement. reusable
- Symlink replacement uses temp-link then non-overwriting move, preserves user-owned files/symlinks, and reports structured failures with Developer Mode/elevated shell guidance.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-05-16] shared-install-plan-contract-builder
Areas: runtime-core install plan/build, runtime-domain install model, runtime-core contract/tests
- SKILL-45 subtask 1 added a pure typed install-plan API (`InstallPlanRequest` -> `InstallPlan`) plus `InstallOperations.planInstall`; it models agent/platform/telemetry/MCP/runtime/target/staging/Windows-preflight intent without applying symlinks, rendering staging output, or registering MCP. reusable
- Supported install agents are locked to `copilot`, `claude`, `codex`, `opencode`, `junie`; the plan builder asserts runtime primitive drift so legacy GLM cannot re-enter through install planning. reusable
- Platform packs are discovered through governed `discoverPlatformPacks`, not raw manifest loading; bad contract versions, missing declared content, duplicate skill names/manifest slots, escaped declared content paths, and pointer targets escaping through symlinked parents must loud-fail during planning. reusable
- Base skills must be represented and must fail if the skills root is missing, empty, or contains `bill-*` directories without `content.md`; future apply/staging work must preserve content-hash parity with the plan, especially for custom `skillsRoot` support pointers.
- Test coverage lives in `InstallPlanBuilderTest` for selected/all/unknown packs, multi-area packs, manual and supplied/detected targets, no home/source mutation, staging root under `~/.skill-bill/installed-skills`, Windows decision/message, pointer target escapes, and base-skill loud-fails.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-11] state-repo-controls-tree-polish
Areas: runtime-desktop core data/domain/testing, feature skillbill UI/state
- Split repo browser state into typed path text, current session/tree, selected item, expanded groups, busy operation, and status-bar model so UI reads one coherent source of truth. reusable
- Open/refresh now rebuild through runtime services, preserve selection only for same-repo existing ids, clear stale invalid state, and keep generated artifacts from shared discovery read-only with `RO`.
- Desktop chooser is a common/JVM boundary that feeds the same open flow as typed paths; open/refresh load off the UI thread and apply token-checked results to prevent stale completions overwriting newer state. reusable
- Tree interactions now support expand/collapse and keyboard movement over visible rows while busy state disables conflicting repo/tree actions and renders progress.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-05-11] runtime-desktop-repo-browser-readonly-tree
Areas: runtime-kotlin runtime-core scaffold/nativeagent, runtime-desktop core data/domain/testing, feature skillbill UI/state, desktop app docs
- Added the Iteration 02 repo browser path: desktop repo selection validates a local Skill Bill checkout, builds a read-only tree from `AuthoringOperations`, `RepoValidationRuntime`, governed add-on discovery, generated-artifact guard discovery, and provider-neutral native-agent source parsing. reusable
- `RuntimeRepoBrowserService` now keeps desktop as an adapter over shared runtime contracts: do not reintroduce UI-local guesses for add-ons, generated wrappers, pointer files, or provider-native agent output. reusable
- Selection IDs are repo-scoped, invalid/blank paths return error state instead of throwing, malformed native-agent sources remain visible as invalid read-only items, source reads tolerate file races, and git branch lookup has a timeout.
- `SkillBillFrame` preserves the existing shell but renders tree, editor, inspector, status, and refresh from state; editing, scaffolding, git diff/commit, and PR flows remain deferred.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-10] runtime-desktop-navigation-room3
Areas: runtime-kotlin runtime-desktop core navigation, core database, app shell, architecture guardrails
- Added `runtime-desktop/core/navigation` with typed desktop routes, explicit back-stack state, and a navigator that supports push, root replacement, reset, and guarded back navigation. reusable
- Added `runtime-desktop/core/database` using the KMPComposeStarter Room3 pattern: `androidx.room3` plugin/runtime/compiler, exported schema, generated database constructor, JVM SQLite driver, app-scoped database provider, and DAO tests for recent repository rows. reusable
- Wired the app shell through navigation state instead of a hard-coded destination enum, and exposed the Room3 provider from the application DI component so the app graph proves database construction. reusable
Feature flag: N/A
Acceptance criteria: desktop navigation and Room3 foundation

## [2026-05-10] runtime-desktop-kmp-starter-base
Areas: runtime-kotlin Gradle build-logic, runtime-desktop app/core/feature modules, runtime architecture guardrails, desktop Skill Bill app docs
- Reworked `runtime-desktop` from a thin Compose app into a KMPComposeStarter-style desktop-only graph: app module plus nested `runtime-desktop/core/common`, `runtime-desktop/core/domain`, `runtime-desktop/core/data`, `runtime-desktop/core/designsystem`, `runtime-desktop/core/ui`, `runtime-desktop/core/testing`, and `runtime-desktop/feature/skillbill` modules. reusable
- Added repo-local KMP convention plugins for library, Compose, application, and kotlin-inject/KSP/Anvil wiring; keep external plugin classes out of build-logic compile classpath to avoid Kotlin metadata mismatch. reusable
- Desktop DI is now starter-shaped and compile-time generated: `@MergeComponent(AppScope)`, contributed `UserScope` and `ScreenScope` subcomponents, `UserComponentManager`, and screen-scoped Skill Bill app state; placeholder services remain no-write adapters for iteration 01.
- Architecture guards now understand nested Gradle paths and pin desktop module dependency direction separately from shared runtime modules.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-10] runtime-desktop-starter-shell-completion
Areas: runtime-kotlin runtime-desktop app shell, desktop core modules, architecture guardrails, packaging
- Completed the desktop-only KMPComposeStarter architecture pass by adding `runtime-desktop/core/datastore`, persisted recent-repo preferences, native distribution metadata, app-shell state, and composition-local user/screen component wiring. reusable
- `SkillBillRoute` now resolves its screen component from the app-provided screen factory instead of taking a feature factory directly, matching the starter host boundary and keeping screen creation behind the user component. reusable
- Desktop module catalogs and architecture tests now include `core:datastore` so the nested app/core/feature graph remains explicit.
Feature flag: N/A
Acceptance criteria: desktop shell architecture completion

## [2026-05-10] runtime-desktop-skillbill-theme
Areas: runtime-kotlin runtime-desktop design system, desktop app shell
- Replaced the thin Skill Bill palette with a KMPComposeStarter-style Material 3 theme stack: color scheme, extended colors, content alpha, typography, shapes, background, gradients, and text-field colors. reusable
- Desktop design tokens now derive from `docs/assets/readme-hero-preview.html` (`ink`, `panel`, `line`, `yellow`, `muted`, `steel`, `green`) and are pinned by a design-system test. reusable
- User-facing desktop app naming is `Skill Bill`; no authored runtime desktop source uses the old interim naming.
Feature flag: N/A
Acceptance criteria: Skill Bill desktop theme uses README hero palette

## [2026-05-10] runtime-desktop-shell
Areas: runtime-kotlin Gradle build, runtime-desktop Compose shell, runtime architecture guardrails, desktop Skill Bill app docs
- Added optional `runtime-desktop` Compose Multiplatform JVM module with repo-local `skillbill.kmp-compose-application` convention plugin, `commonMain`/`jvmMain` source sets, and `:runtime-desktop:run`, mirroring KMPComposeStarter app/core/feature layering while staying isolated from shared runtime modules. reusable
- Skill Bill shell owns only presentation and in-memory state: app entrypoint, design system, repo toolbar, tree/editor/status placeholders, placeholder repo/tree/authoring/git gateways, and no repo-content writes on launch/close.
- Desktop boundary guardrails now live in `RuntimeDesktopBoundaryTest`: non-desktop runtime modules must not import desktop/Compose APIs or apply Compose dependencies. reusable
- Review pitfall: do not mark `List`-backed Compose state as `@Immutable`; snapshot service-returned tree items at the state boundary instead.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-09] native-agent-bundles
Areas: runtime-core nativeagent parser/discovery/validation/install, runtime-core scaffold, Kotlin/KMP platform-pack native agents, shell-content-contract docs
- Native-agent discovery now treats `native-agents/agents.yaml` and `native-agents/*.md` as source files, expanding bundles into logical `NativeAgentSource` entries only after selection filters so targeted regeneration stays cheap. reusable
- Bundle validation keeps platform composition manifest-bound and horizontal composition frontmatter-bound, with duplicate-name diagnostics including `agents.yaml` entry names and custom-body parity against markdown sources.
- Multi-specialist scaffolds emit thin `compose: governed-content` bundle entries in `agents.yaml`; standalone markdown stub rendering remains the custom-body path for authored worker prompts. reusable
- Kotlin and KMP header-only specialist sources moved from one markdown file per agent into pack-owned bundle files without changing provider-native install output.
Feature flag: N/A
Acceptance criteria: 16/16 implemented

## [2026-05-09] native-agent-composition-validation-final-coverage
Areas: runtime-core nativeagent validation, runtime-core scaffold repo validation, runtime-cli validation, scripts/validate_agent_configs
- Final validation coverage pins native-agent source preservation through `RepoValidationRuntime.validateRepo`; validation must report issues without rewriting or deleting `native-agents/*.md`. reusable
- Composition rejection coverage now includes malformed directives, missing platform manifests, contract-version drift, missing governed content, undeclared local markdown sidecars, and install-render refusal before provider output is staged.
- `validate-agent-configs` CLI coverage now surfaces native-agent composition failures, matching the `scripts/validate_agent_configs` Kotlin-backed path used by repository gates. reusable
- `skillbill.nativeagent` docs now list optional `compose: governed-content`, manifest/sibling target resolution, and self-contained provider-native install output.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-05-09] native-agent-composition-rendering
Areas: runtime-core nativeagent rendering/validation, scaffold authored-content rendering, Kotlin/KMP specialist native-agent sources
- Composed native agents now expand `compose: governed-content` before provider rendering/install; `NativeAgentProvider` still owns Claude/Codex/Opencode/Junie shapes while render inputs are self-contained. reusable
- `AuthoredContentRendering` centralizes frontmatter stripping, title stripping, heading demotion, and LF normalization so wrapper rendering and native-agent composition share the authored `content.md` body contract. reusable
- `NativeAgentSidecarInlining` rewrites local markdown links to plain labels and appends referenced sibling or manifest-pointer sidecars recursively; unresolved local links fail validation instead of leaking repo-local runtime dependencies.
- Direct Kotlin/KMP specialist native-agent source files remain under `native-agents/*.md` but now declare `compose: governed-content` and drop duplicated long specialist prose.
- Regression coverage pins manifest-driven composition, arbitrary platform slugs, install-cache self-containment, source preservation, generated-artifact rejection, and KMP UI source snapshots.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-05-09] native-agent-composition-foundation
Areas: runtime-core nativeagent parser/validation, shell-content-contract docs, native-agent tests
- Native-agent source frontmatter now supports explicit `compose: governed-content`; parser round-trips the directive while provider renderers still use the source body only until rendering migration lands.
- Composition target resolution is manifest-bound for platform packs: `platform.yaml` declared content paths are authoritative, and undeclared sibling `content.md` files must not be used as fallback. reusable
- Repo validation aggregates malformed compose directives, missing declared content targets, unresolved targets, and manifest loader failures as native-agent validation issues alongside existing provider-agnostic/render checks.
- Regression coverage pins parser acceptance/rejection, manifest-driven target resolution, missing target failure, no undeclared platform fallback, discovery preservation, and composition round-trip.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-05-09] authored-content-scaffold-commands
Areas: runtime-core scaffold/authoring/render validation, runtime-cli authoring tests, shell-content-contract docs
- Scaffold now threads `content_body` into governed `content.md` for code-review areas and quality-check overrides, validates horizontal scaffolds against the planned `content.md` target after staging, and rolls back generated-wrapper headings byte-identically. reusable
- `edit --section` rejects generated wrapper sections (`Descriptor`, `Execution`, `Ceremony`) before mutation with a diagnostic pointing authors to authored `content.md` sections or manifest/frontmatter metadata. reusable
- Regression coverage pins clean authored scaffold bodies, wrapper-heading rejection, quality-check override rollback, horizontal name-collision validation, deterministic render wrapper sections, and fill/edit authored-content mutation behavior.
- Validation note: full Gradle check also required formatting an unrelated pre-existing Spotless issue in `InstallOperations.kt`; keep formatter-only unblock changes isolated from scaffold behavior.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-09] generated-skill-artifact-source-contract
Areas: runtime-core scaffold loader/render/validation/install, runtime-cli validation, runtime-core tests
- `ShellContentLoader` now treats platform.yaml-declared governed files as exact `content.md` paths with governed frontmatter plus Descriptor/Execution/Ceremony H2s; generated wrapper body-shape validation was removed from the declared-source path. reusable
- `GeneratedArtifactGuard` no longer grandfather-lists committed wrappers/pointers, and `GovernedSkillDriftValidation` compares deterministic in-memory render output instead of requiring source `SKILL.md` files. reusable
- Install staging excludes stale source generated artifacts on both rebuild and cache-hit paths, then renders `SKILL.md`/pointers into the install cache without mutating source. reusable
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-09] render-snapshots-final-validation
Areas: runtime-core scaffold render snapshot tests, runtime-core test-support, runtime Gradle test wiring
- Added resource-backed render snapshots for a standalone governed skill (`bill-pr-description`), `bill-kotlin-code-review`, and `bill-kmp-code-review-ui`; snapshots exercise `renderAuthoringTarget` output instead of duplicating wrapper/pointer formatting logic. reusable
- `AuthoringRenderSnapshotTest` asserts repeated in-memory renders are byte-identical and derives expected platform pointer headers from `platform.yaml`, so pointer block order remains manifest-declaration order even though pointer write paths sort independently. reusable
- Added `SnapshotAssertions` with deterministic `-Pupdate-snapshots` support, LF normalization, fixture-path failure messages, and containment checks that reject escaped `../` paths before read/write. reusable
- KMP UI native-agent coverage snapshots the provider-neutral source at `platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/bill-kmp-code-review-ui.md`, not the specialist directory, matching the governed native-agent source layout.
- Validation note: stale Gradle configuration cache can false-fail this area; clearing cache and rerunning the gate produced a clean pass with no source edits.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-05-09] drift-and-agent-config-guards
Areas: runtime-core scaffold validation/rendering, runtime-cli validation task, runtime Gradle lifecycle, agent-config validation
- `RepoValidationRuntime.validateRepo` now runs `validateGovernedSkillDrift`: discovers content-managed targets once, renders each `AuthoringTarget` twice in memory, compares emitted SKILL.md bytes to disk, and fails on unresolved platform.yaml pointer targets. reusable
- `renderAuthoringTarget(repoRoot, target)` overload avoids per-skill rediscovery during repeated-render checks; keep render stdout deterministic (`SKILL.md` block first, pointer blocks in manifest order) when adding future render surfaces. reusable
- `GeneratedArtifactGuard` grandfather-lists the current committed generated SKILL.md wrappers and pointer files, uses `git ls-files` when available so untracked local fixtures stay ignored, and fails newly tracked governed SKILL.md/platform pointer outputs. reusable
- `runtime-cli:check` depends on `validateAgentConfigs`, so `(cd runtime-kotlin && ./gradlew check)` exercises the same Kotlin-backed path as `scripts/validate_agent_configs`; tests pin repo-validation, CLI, Gradle wiring, git-index behavior, platform-pack wrapper coverage, and stale-wrapper drift.
- Known limit: this is a guard for future deletion policy, not cleanup; current committed wrappers/pointers remain allowed until the retirement subtask removes them.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-09] render-cli-output
Areas: runtime-cli scaffold commands, runtime-core scaffold rendering, runtime CLI/core tests
- `skill-bill render <skill-id>` is now a read-only stdout surface, separate from `upgrade`; `--dry-run` is accepted as the same no-write preview path.
- Runtime-core owns `renderAuthoringTarget`, which emits `SKILL.md` from `renderWrapper` plus pointer blocks from `renderPointer`; platform-pack pointer blocks preserve `platform.yaml` declaration order instead of `PointerOperations.regenerate` write-path sorting. reusable
- Separator headers are deterministic LF text (`SKILL.md` first, then pointer files), while source `SKILL.md` and pointer files remain untouched.
- Known test limit: CLI coverage proves no-write/dry-run equivalence on a horizontal fixture; platform pointer order is pinned at the core renderer boundary.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-05-09] install-staging-pipeline
Areas: runtime-core install (new InstallStaging.kt, InstallStagingIO.kt, InstallStagingIdentity.kt; rewired InstallPrimitives.installSkill), runtime-core scaffold (ScaffoldService.performInstall threads repoRoot + hoists discoverPlatformPackManifests), runtime-domain install/model (added RenderedSkill DTO)
- Content-managed install now stages into `~/.skill-bill/installed-skills/<slug>-<16hex>/` (cache root outside repo, mirroring SKILL-38 native-agent shape) and symlinks the staging dir; source tree is never written to. Authored files copied verbatim, SKILL.md from `renderWrapper(target)`, pointer files from `renderPointer(repoRoot, packRoot, spec)`. Atomic-move promotion via `Files.createTempDirectory` + `Files.move(ATOMIC_MOVE/REPLACE_EXISTING)` with `AtomicMoveNotSupportedException` swap-and-cleanup fallback. reusable
- Cleanup ownership: `promoted: Boolean` flag (NOT `finalExistedBefore` TOCTOU) so failure cleanup only deletes finalStagingDir we promoted in this attempt — concurrent installs cannot destroy each other's good output. Catch envelope is `Throwable` so any failure (NPE/ISE/Error) cleans up tempDir + finalStagingDir-if-promoted; cleanup paths log-and-suppress secondary IOExceptions. reusable
- Content hash: SHA-256 first 8 bytes lowercase hex over sorted `<rel-fwd-slash>\n<bytes>\n` for authored files plus sorted `<skillRelativeDir>|<name>|<target>\n` for applicable pointer specs; SKILL-39 determinism rules (`Files.walk(...).sorted()`, explicit byte-newline, `trimEnd('\n','\r')`, `Path.relativize` + forward-slash) followed throughout.
- Stale staging-dir pruning uses STRICT regex `^<slug>-[0-9a-f]{16}$` (not prefix match) — pitfall: prefix match collides on shared-slug-prefixes (e.g. installing `bill-kotlin-code-review` would have wiped `bill-kotlin-code-review-security-<hash>`). Regression test in `InstallStagingTest` plants a sibling cache dir with non-hex suffix and asserts it survives. Slug normalization re-trims `-` after `take(32)` so a 32nd-char `-` doesn't collide with the `<slug>-<hash>` separator.
- Install-pipeline boundary rule: `installSkill(repoRoot=null, ...)` OR `!isContentManagedSkill(sourceSkillDir)` falls through to legacy direct-symlink (preserves manual `link-skill` CLI flow with no `content.md`). Content-managed installs always go through `ScaffoldService.performInstall` which threads repoRoot + manifests via the new `InstallContext` data class so `discoverPlatformPackManifests` runs ONCE per scaffold (not per skill). reusable
- Defense-in-depth: `requireWithinSource` post-filters every walked authored path with `toRealPath().startsWith(resolvedSource)`; every staging write asserts `dest.startsWith(tempDir)`/`startsWith(cacheRoot)`. `isReusableInstallStaging` requires both `.content-hash` marker match AND `SKILL.md` regular-file existence (partial-write residue → cache miss → rebuild).
- Pitfall: `applicablePointers` lookup uses `installPath.toAbsolutePath().normalize().startsWith(packRoot)`; for skills under `skills/` (non-pack), no manifest matches and pointer list is empty — that path skips renderPointer entirely. Future packs that nest skill dirs deeper than one level under packRoot need `skillRelativeDir` rules to match.
- Logging via `java.util.logging.Logger` (no SLF4J/log4j dep — runtime-core has none): INFO on stage entry (skill, hash, finalDir, reuse=true|false), WARN on cleanup (promoted flag + error class), SEVERE on each failure path with full context.
- Subtask 3 owns the snapshot tests + CI drift check that pin "staged bytes == committed bytes" — this subtask trusts SKILL-39's renderers to produce the same output structurally.
Feature flag: N/A
Acceptance criteria: 9/9 implemented (subtask 2 of SKILL-40; AC #8 byte-identity invariant structurally preserved by reusing SKILL-39 renderers, but no direct snapshot test here — explicitly scoped to subtask 3)

## [2026-05-08] skill-discovery-marker-content-md
Areas: runtime-core scaffold (AuthoringDiscovery, AuthoringTarget, AuthoringMutation, AuthoringRender, AuthoringStatus, AuthoringContentMutation, ScaffoldTemplateRendering, RepoValidationRuntime, SkillMdShapeValidator, ShellContentLoader), runtime-domain workflow definitions/engine, runtime-cli authoring-parity tests, runtime-mcp golden fixtures, all 26 governed content.md files
- Skill-discovery marker switched from SKILL.md to content.md across the three tree-walk filter callsites (AuthoringDiscovery + RepoValidationRuntime). content.md is now the canonical authoring surface; SKILL.md remains on disk as the rendered wrapper through subtasks 2-3, retired in subtask 4.
- Frontmatter migrated SKILL.md -> content.md for all 26 governed skills as a one-shot in-scope expansion (originally not in the subtask spec but required for AC #4 to be honest about content.md owning the frontmatter). renderWrapper now sources frontmatter from target.contentFile so the wrapper tracks content.md byte-for-byte. reusable
- SkillMdShapeValidator gained `validateBodyShape: Boolean = false` (frontmatter-only by default; wrapper-only body-shape rules behind the flag); error messages parameterized on Path.fileName so the same validator serves both content.md and SKILL.md callers cleanly until the wrapper is retired. reusable
- hasGenerationDrift removed end-to-end: definition + AuthoringStatus property + all consumers (AuthoringMutation, AuthoringOperations recommended-commands). Drift detection is replaced by renderWrapper sourcing-from-content.md (wrapper can no longer drift); subtask 3 adds the CI drift check that replaces the deleted scaffold-managed render drift signal.
- Pitfall: pack-manifest `baseline:` strings in `platform-packs/{kmp,kotlin}/platform.yaml` plus the `ShellContentLoader.validateGovernedSkill` body-shape branch CANNOT flip to content.md until subtask 4 retires the wrapper — `validateGovernedSkill` enforces wrapper-shape (Descriptor/Execution/Ceremony H2 + exact rendered match) which authored content.md does not satisfy. Originally listed in subtask 1 AC #8 but explicitly deferred to subtask 4 (subtask 1 spec amended; subtask 4 spec gained an AC line for the residual).
- ScaffoldTemplateRendering.renderContentBody and AuthoringContentMutation.coerceFullContentText now fail loudly (SkillBillRuntimeException with file path + recovery command) when frontmatter is missing or stacked, rather than silently writing a malformed content.md that the validator only catches after rollback. reusable
- Workflow-label sweep at WorkflowEngine.kt + FeatureImplementWorkflowDefinition.kt + FeatureVerifyWorkflowDefinition.kt is label-only — step IDs preserved verbatim. continuationReferenceSections is in-memory only, so NO DatabaseMigrations entry was needed; future SKILL.md path-string changes that touch state keys must add migrations.
- Breaking change for CLI consumers of `--format json`: `generation_drift` boolean removed from skill-bill list/show/validate output. Pinned by strict key-set equality test in AuthoringOperationsTest.kt so future contract drift is caught.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (subtask 1 of SKILL-40; AC #8 pack-manifest portion deferred to subtask 4 by spec amendment)

## [2026-05-03] repo-script-validation-migration
Areas: runtime-core repo validation, runtime-cli validation commands, scripts/, workflows, docs, feature specs
- Moved repo validation and release-ref checks off standalone Python scripts into Kotlin CLI/runtime commands, with shell wrappers kept at `scripts/validate_agent_configs` and `scripts/validate_release_ref`. reusable
- Retired one-off `migrate_to_content_md.py` and `skill_repo_contracts.py` as markdown notes; current docs/workflows now call Kotlin-backed validation and no maintainer workflow requires Python scripts.
- Strengthened Kotlin repo validation for manifest-loaded platform packs, pack-owned add-ons, sibling supporting sidecar symlinks, README/catalog references, workflow markers, telemetry contract drift, plugin metadata, and SemVer release refs.
- Regression coverage now lives in Kotlin CLI/core tests plus the remaining self-contained Python contract test; no current test imports the retired script modules.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-03] installer-runtime-cutover
Areas: install.sh, uninstall.sh, runtime-cli install commands, runtime-core install primitives, runtime-core launcher MCP config mutation, pyproject.toml, installer tests
- Moved installer/uninstaller runtime ownership off Python: shell scripts now build/use packaged Kotlin `installDist` bin shims and call `skill-bill install ...` commands for agent paths, link/unlink, cleanup, MCP registration/removal, and telemetry level mutation. reusable
- Added Kotlin install parity for Codex/OpenCode native agent path/link/unlink, selected-platform discovery (base skills plus selected pack roots), user-file-preserving target handling, symlinked-source rejection, and cleanup operations that propagate filesystem delete failures. reusable
- Added Kotlin MCP config mutation for Claude/Copilot JSON, Codex TOML, and OpenCode JSONC using packaged `runtime-mcp` commands and atomic writes; invalid OpenCode JSONC now loud-fails without overwriting user config. reusable
- Pitfall: Bash `${array[@]:-}` under `set -u` can inject an empty platform slug; use explicit length guards before iterating selected/required platform arrays.
- `pyproject.toml` remains for remaining Python maintainer tooling, but Python console scripts are gone; Python package deletion remains a later SKILL-36 subtask.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-03] kotlin-contract-parity
Areas: runtime-core scaffold/shell-content loader, runtime-cli scaffold commands, runtime-contracts errors, governed skill wrappers
- Ported remaining scaffold, shell-content-contract loading, render/fill/upgrade, and authoring validation behavior onto Kotlin-owned APIs/CLI while keeping `skill_bill/` as reference-only for later subtasks.
- Added canonical SKILL.md shape validation, render-drift validation, regular-file sidecar validation, feature-verify `audit-rubrics.md` ceremony support, and shared display-name derivation from manifest/fallback sources. reusable
- Implemented Kotlin scaffold parity for `subagent_specialists` / `no_subagents`, Codex/OpenCode stub emission, create-and-fill multi-artifact rejection, quality-check manifest registration, and byte-identical rollback tests. reusable
- Guardrail: feature-verify sidecars need both `requiredSupportingFilesForSkill` and `supportingFileTargets` entries; missing the target breaks newly scaffolded platform verify overrides even if existing wrappers render correctly.
- Validation gate passed: focused scaffold/CLI tests, `runtime-kotlin ./gradlew check`, Python unittest suite, `agnix --strict`, and agent-config validation.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-05-01] adoption-docs-and-external-author-dry-run
Areas: runtime-cli external-author tests, runtime-core scaffold manifest paths, docs adoption guides
- Rewrote adoption docs around packaged Kotlin-only CLI/MCP behavior, fail-closed vs degraded boundaries, strict contract guarantees, and model-mediated review/planning limits.
- Added a Kotlin CLI external-author dry run that scaffolds a temporary platform pack through `new --payload`, validates it, links a generated skill into a temp agent path, removes it, and validates cleanup. reusable
- Fixed scaffold manifest writes to use pack-root-relative declared file paths for new platform packs, code-review-area edits, and quality-check overrides. reusable
- Reusable guardrail: external-author validation should exercise CLI payload parsing plus manifest-loader resolution, not call the scaffold core directly.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-01] python-runtime-retirement
Areas: skill_bill.launcher, runtime-core launcher contract, runtime-mcp launch path, runtime docs
- Removed the TODO(3c) launcher surface entries for `python-fallback` and `mcp-python-fallback`; the contract now locks only packaged Kotlin CLI/MCP selection. reusable
- The 3b bridge teardown is now complete at the outer launcher boundary: retired runtime env vars no longer select Python and the Python MCP bootstrap script is gone.
- Future runtime rollback guidance should install the previous release instead of preserving Python runtime ownership.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-05-01] port-or-retire-python-backed-cli-closeout
Areas: runtime-cli authoring tests, SKILL-32 3b specs, runtime validation gate
- Closed the broad 3b parent spec after 3b_1 through 3b_4 landed by marking stale 3b/3b_2/3b_3 specs complete and adding parent close-out evidence.
- Added missing Kotlin CLI coverage for `upgrade`, `render`, `edit --body-file`, and `fill` using isolated temp authoring repos so mutating commands never touch the real workspace. reusable
- Reusable guardrail: broad close-out should audit every ported command for a concrete Kotlin CLI test, not only source-level Python-bridge removal.
- Validation gate passed after extracting authoring test helpers to satisfy Detekt `LongMethod` without suppressions.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-01] bridge-teardown-and-arch-ban
Areas: runtime-cli bridge teardown, runtime architecture tests, runtime scaffold authoring helpers
- Deleted the remaining Kotlin CLI Python bridge helpers after 3b_1/3b_2/3b_3 ported their callers; native payload reads now avoid `java.nio.file.Files` in runtime-cli main sources.
- Promoted the deferred runtime-cli FS/HTTP/SQL architecture bans and removed the temporary Python bridge allowlist. reusable
- Split `AuthoringOperations` helper groups into focused sibling files to satisfy the validation gate without Detekt suppressions. reusable
- Python CLI/scaffold tests remain intentionally for 3c/fallback while `skill_bill/cli.py` and `skill_bill/scaffold` still exist.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-30] scaffold-payload-ports
Areas: runtime-cli scaffold commands, runtime scaffold adapter, CLI golden fixtures
- Ported payload-mode `new-skill`, `new`, `new-addon`, and `create-and-fill` CLI paths to the native scaffold runtime while leaving interactive prompt modes on the Python bridge.
- Reusable pattern: keep bridge helpers present during staged retirement, but source-test ported command blocks and their native helper so payload paths cannot silently shell back to Python.
- Locked `new-skill --dry-run --format json` with a golden fixture using dynamic session/path normalization, and split scaffold CLI tests out of the broad runtime test class.
- Known limitation: editor-backed `create-and-fill` and broader inspection/authoring command ports remain deferred to later 3b subtasks.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-30] install-and-doctor-ports
Areas: runtime-cli install commands, runtime-core install facade, runtime-cli doctor subject routing, CLI source-reference tests
- Ported `install agent-path`, `install detect-agents`, and `install link-skill` from Python bridge calls to a public `InstallOperations` facade over internal install primitives. reusable
- Retired `doctor skill` on the Kotlin CLI with a stable replacement message while leaving subjectless `doctor` on the native `SystemService` path.
- Reusable guardrail: when bridge helpers must remain for deferred commands, add targeted source-reference tests around the newly ported command blocks instead of promoting broad architecture bans early.
- Known limitation: scaffold/authoring commands and launcher Python fallback remain owned by later 3b/3c subtasks.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-30] kotlin-native-contract-tests
Areas: runtime-cli golden fixtures, runtime-mcp golden fixtures, runtime architecture tests, runtime surface contracts
- Added golden JSON contract coverage for Kotlin-native CLI/MCP surfaces while explicitly deferring Python-backed scaffold/authoring/install and `doctor <subject>` work to 3b.
- Reusable pattern: normalize dynamic workflow ids/timestamps and scaffold paths only after asserting shape, prefixes, timestamp format, and path suffixes.
- Strengthened architecture guardrails with source-reference bans for Python bridge markers and runtime-mcp FS/HTTP/SQL dependencies, with only the current `McpScaffoldRuntime` repo-root lookup exception.
- Added active runtime surface locks for launcher, install, scaffold, feature-implement workflow, and feature-verify workflow; launcher Python fallback entries remain a TODO for 3c.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-30] packaging-schema-integration-validation
Areas: skill_bill.launcher, install.sh, runtime-mcp schemas, repository validation gate
- Confirmed the packaged Kotlin CLI/MCP installDist launch path and strict MCP schema boundary coexist through the full repository gate plus installer/stdio smoke checks. reusable
- Reusable validation pattern: run the four-command repo gate, force `McpStdioServerTest` when schema behavior matters, then smoke `skill-bill doctor` and MCP `initialize`/`tools/list` with Kotlin runtime overrides unset.
- Known limitation: `install.sh` is interactive and resets `~/.skill-bill` during reinstall, so workflow telemetry/state opened before an installer rehearsal is intentionally discarded.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (Python rollback criterion superseded by Kotlin-only runtime direction)

## [2026-04-30] runtime-mcp-strict-priority-schemas
Areas: runtime-mcp tool registry, MCP stdio argument boundary, MCP schema tests
- Published strict root input schemas for priority telemetry, review, learning, scaffold, and workflow MCP tools; zero-argument workflow tools now advertise empty strict object schemas instead of open objects. reusable
- Added stdio boundary validation that rejects undeclared top-level arguments for tools whose published schema sets `additionalProperties=false`, before handler dispatch.
- Expanded `McpStdioServerTest` to lock strictness, required argument publication, enum publication, unknown argument rejection, and zero-argument strict schemas.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-04-30] packaged-runtime-installer
Areas: skill_bill.launcher, install.sh, runtime-cli installDist, runtime-mcp installDist, launcher/installer tests
- Switched default Kotlin CLI/MCP launch commands from Gradle `run` tasks to packaged application `installDist` bin scripts; missing distributions now loud-fail with installDist guidance. reusable
- `install.sh` now builds runtime-cli/runtime-mcp distributions and verifies both packaged bin scripts before registering MCP shims.
- Kept Kotlin development override env vars for local commands; the Python launcher shim is transitional and should not shape new runtime acceptance gates.
- Regression coverage locks packaged CLI/MCP path resolution, missing-distribution messaging, installer build/location behavior, and no-Gradle MCP registration.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (Python rollback criteria superseded by Kotlin-only runtime direction)

## [2026-04-30] runtime-green-gate-and-python-ownership
Areas: runtime-application lifecycle validation, telemetry sync, SKILL-27 cutover checklist
- Split lifecycle telemetry validation into feature-implement, feature-verify, quality-check, and shared validator files so each lifecycle family owns its validation rules and the runtime-application Detekt `TooManyFunctions` gate stays green. reusable
- Collapsed `TelemetryService.autoSync` into a single guard predicate to satisfy the return-count rule without changing sync behavior.
- Added the Python retirement ownership inventory to the SKILL-27 cutover checklist, classifying CLI commands, MCP tools, scaffold/authoring commands, install primitives, validation scripts, and release/support scripts before any Python deletion starts.
Feature flag: N/A
Acceptance criteria: runtime green gate restored, Python ownership inventory published

## [2026-04-30] runtime-mcp-test-telemetry-loopback
Areas: runtime-mcp tests, lifecycle telemetry fixtures, MCP stdio tests
- PostHog inspection found `test-install-id` lifecycle/review events from Kotlin MCP tests because enabled test configs left `proxy_url` blank, which correctly resolves to the hosted relay in production settings.
- Added a loopback telemetry proxy override to enabled MCP test environments, including the user-home config coverage path and stdio tool-call fixture, so auto-sync cannot emit test events to production telemetry. reusable
- Reusable pattern: tests that enable telemetry should either inject a fake requester or set an explicit test-only proxy; do not rely on blank proxy config when the runtime treats blank as hosted relay.
Feature flag: N/A
Acceptance criteria: test telemetry cannot target hosted relay, runtime-mcp check passes

## [2026-04-30] runtime-mcp-feature-implement-lifecycle-schemas
Areas: runtime-mcp tool registry, MCP stdio tools/list contract, lifecycle telemetry tools
- Added explicit MCP input schemas for `feature_implement_started` and `feature_implement_finished`; the previous open schema made Codex expose these mandatory lifecycle tools as no-argument tools even though handlers required fields. reusable
- Locked the regression with an MCP stdio tools/list test that asserts required lifecycle fields such as `feature_size`, `issue_key`, `session_id`, and `completion_status` are advertised.
- Tightened feature-implement lifecycle validation so missing/defaulted started fields (`spec_input_types=[]`, zero criteria, zero text spec word count, `issue_key_type=none` with an issue key) and impossible completed metrics (`review_iterations=0`, skipped audit/validation) fail instead of emitting low-value events. reusable
- Operational symptom: a completed SKILL-32 feature-implement run committed successfully but initially missed `skillbill_feature_implement_started` and `skillbill_feature_implement_finished`; the missing pair was backfilled through the Kotlin MCP stdio server as session `fis-20260430-074408-b27m`.
- Known limit: the backfilled session has `duration_seconds=0` because started/finished were emitted together after the fact; future valid runs must call started at assessment time and finished at finalization time.
Feature flag: N/A
Acceptance criteria: MCP lifecycle schemas exposed, incomplete telemetry rejected, focused runtime-mcp check passes, telemetry pair backfilled

## [2026-04-25] runtime-mcp-lifecycle-native
Areas: runtime-application lifecycle telemetry service, runtime-infra-sqlite lifecycle telemetry store, runtime-mcp dispatcher, docs/migrations/SKILL-27-cutover-checklist.md
- Ported the remaining MCP telemetry lifecycle tools to Kotlin-native services and SQLite persistence while preserving standalone session/outbox behavior and orchestrated child telemetry payloads.
- Removed the temporary Kotlin-to-Python MCP bridge and `skill_bill.mcp_tool_bridge`; Python remains only as the explicit CLI/MCP fallback path.
- Reusable pattern: once the process boundary is cut over, retire compatibility bridges by adding a narrow application service plus persistence port, then keep MCP argument coercion at the adapter edge.
Feature flag: N/A
Acceptance criteria: native lifecycle MCP handlers, bridge removal, standalone/orchestrated telemetry parity, runtime validation

## [2026-04-25] runtime-mcp-stdio-cutover
Areas: runtime-mcp application entrypoint, skill_bill.launcher, installer MCP registration, docs/migrations/SKILL-27-cutover-checklist.md
- Added a Kotlin stdio MCP server that speaks line-delimited JSON-RPC, exposes the Python-compatible tool inventory, and dispatches ported MCP tools through Kotlin runtime services.
- Switched `skill-bill-mcp` to default to the Kotlin MCP server through the launcher, with `SKILL_BILL_MCP_RUNTIME=python` as the explicit rollback path.
- Kept a narrow Python bridge for telemetry lifecycle tools that are still Python-owned, so MCP callers keep stable tool names and payload shapes during the Kotlin server cutover.
- Reusable pattern: executable cutover can move the process boundary first while preserving unported leaf behavior behind a named compatibility bridge; document the bridge as transitional, not retired architecture.
Feature flag: N/A
Acceptance criteria: Kotlin stdio MCP packaging, launcher default switch, Python fallback, tool inventory compatibility

## [2026-04-25] runtime-cli-final-cutover
Areas: skill_bill.launcher, pyproject.toml, runtime-cli application entrypoint, docs/migrations/SKILL-27-cutover-checklist.md, docs/getting-started.md
- Switched the installed `skill-bill` script to a launcher that defaults to the Kotlin CLI and keeps `SKILL_BILL_RUNTIME=python` as the explicit rollback path.
- Added a Kotlin CLI `main` plus Gradle application run path, including stdin forwarding so payload-backed commands such as `new-skill --payload - --dry-run` work through the Kotlin-default launcher.
- Kept `skill-bill-mcp` Python-backed and made Kotlin MCP selection loud-fail until a real Kotlin stdio MCP server is packaged.
- Reusable pattern: cut over one executable surface only when it has an executable runtime path; leave unsupported sibling surfaces on documented fallback instead of pretending parity exists.
Feature flag: N/A
Acceptance criteria: Kotlin-default CLI launcher, Python fallback, MCP fallback boundary

## [2026-04-25] runtime-cutover-preparation
Areas: docs/migrations/SKILL-27-cutover-checklist.md, runtime-kotlin/ARCHITECTURE.md, runtime surface contracts, runtime smoke tests
- Added a maintained Kotlin runtime cutover checklist that names the current Python default, Kotlin parity gates, Phase 9 switch plan, and rollback path.
- Updated runtime surface contracts so scaffold and install are active Kotlin-owned surfaces after the completed scaffold-loader-install bridge, while launcher remains the reserved default-runtime switch surface.
- Reusable pattern: do not flip entrypoints in the same step that documents cutover; keep Phase 8 as handoff preparation and Phase 9 as the intentional default-runtime switch.
Feature flag: N/A
Acceptance criteria: cutover checklist, active surface metadata, launcher boundary preserved

## [2026-04-25] runtime-scaffold-loader-install-adapter
Areas: skillbill.scaffold, skillbill.install, skillbill.cli, skillbill.mcp, runtime-core, runtime-cli, runtime-mcp, runtime-contracts
- Added Kotlin scaffold-loader/install primitives and wired the CLI/MCP scaffold paths onto the Kotlin core while keeping the Python runtime as the broader oracle for the remaining surface.
- Preserved the governed loader and scaffold payload contract in the adapter layer, including explicit repo-root injection, typed started/finished payloads, and install symlink handling.
- Reusable pattern: boundary adapters should normalize payload quirks at the edge, then let the shared Kotlin core own the actual filesystem and manifest behavior.
- Runtime-kotlin `./gradlew check` passes with the new scaffold-loader-install bridge in place.
Feature flag: N/A
Acceptance criteria: scaffold loader/install bridge, adapter payload parity, full runtime-kotlin check

## [2026-04-25] runtime-model-package-ownership
Areas: runtime-domain model packages, runtime-ports model packages, runtime-application model packages, RuntimeContext, architecture tests
- Moved public data/enum model declarations out of service, runtime, and port interface files into explicit `model` packages.
- `LearningResolution`, `TelemetryOutboxRecord`, `WorkflowStateRecord`, and `HttpResponse` now live under port-owned model packages; learning/review/telemetry/domain DTOs live under area model packages.
- Moved `RuntimeContext` to `skillbill.model` and updated runtime composition/adapters to import it from the shared model package.
- Moved SQLite-owned tests from `runtime-core` to `runtime-infra-sqlite` so internal migration/schema details remain encapsulated in their owning module.
- Reusable guardrail: architecture tests now fail if a public data/enum/sealed model declaration appears in application/domain/port modules outside a `model` package.
Feature flag: N/A
Acceptance criteria: public runtime model types have explicit model package ownership

## [2026-04-25] runtime-deeper-gradle-module-split
Areas: settings.gradle.kts, runtime-contracts, runtime-domain, runtime-ports, runtime-application, runtime-infra-*, runtime-core, architecture tests
- Extracted the cleaned runtime boundaries into physical Gradle modules: contracts, domain, ports, application, SQLite infra, HTTP infra, filesystem infra, core composition, CLI, and MCP.
- Kept package names stable while moving ownership: `RuntimeContext` and ports now live in `runtime-ports`; Kotlin-Inject wiring and reserved surfaces remain in `runtime-core`.
- Reusable pattern: `runtime-core` re-exports shared modules for current CLI/MCP composition, while future cleanup can reduce those API re-exports once adapters declare direct dependencies.
- Architecture docs and guardrails now scan all module source roots and assert the implemented deeper split.
Feature flag: N/A
Acceptance criteria: deeper physical split implemented

## [2026-04-25] runtime-split-blocker-cleanup
Areas: skillbill.contracts, skillbill.application, skillbill.infrastructure.http, skillbill.infrastructure.sqlite.review, skillbill.telemetry, RuntimeContext, architecture tests
- Moved application/domain/port-to-contract mapping out of `skillbill.contracts`; contracts now stay DTO/serializer-only for future `runtime-contracts` extraction.
- Moved telemetry proxy batch mapping beside the HTTP adapter, leaving contract DTOs in `contracts.telemetry`.
- Removed the HTTP infrastructure default from `RuntimeContext`; CLI/MCP contexts own the JDK requester while core defaults to `UnconfiguredHttpRequester`.
- Moved SQL-backed review persistence, stats, feedback, and review telemetry helpers into `skillbill.infrastructure.sqlite.review`; `skillbill.review` is now persistence-free.
- Made telemetry compatibility facades port-backed so they no longer construct filesystem, SQLite, or HTTP adapters.
- Reusable guardrail: architecture tests now ban upward runtime imports from `skillbill.contracts`, infrastructure imports from `RuntimeContext` and telemetry facades, and persistence imports from `skillbill.review`.
Feature flag: N/A
Acceptance criteria: 4/4 deeper split blockers resolved

## [2026-04-25] runtime-gradle-module-split
Areas: settings.gradle.kts, build.gradle.kts, runtime-core, runtime-cli, runtime-mcp, architecture tests, SKILL-28 spec
- Split the runtime into `runtime-core`, `runtime-cli`, and `runtime-mcp`; CLI/MCP adapters now compile as independent Gradle modules over core.
- Kept deeper contract, domain, application, SQLite, and HTTP module extraction deferred in `docs/architecture/gradle-module-split-evaluation.md` until upward dependencies are removed.
- Reusable pattern: adapter modules use `api(project(":runtime-core"))` only where public context types expose core runtime ports; core exposes serialization as `api` because `JsonSupport` returns serialization types.
- Runtime architecture tests now scan all module source roots and assert the split decision plus declared Gradle modules.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-04-25] runtime-placeholder-surface-contracts
Areas: skillbill.install, skillbill.launcher, skillbill.scaffold, skillbill.workflow.*, skillbill.contracts.surface, runtime smoke tests
- Replaced empty marker interfaces with reserved runtime surface objects exposing `RuntimeSurfaceContract` metadata.
- Documented why install, launcher, scaffold, feature-implement workflow, and feature-verify workflow remain placeholder-only.
- Reusable pattern: reserved runtime surfaces must declare owner package, contract version, reserved status, and a concrete placeholder reason before implementation.
- Runtime smoke coverage now asserts reserved contracts and package boundaries instead of only checking class/package presence.
Feature flag: N/A
Acceptance criteria: 2/2 implemented

## [2026-04-25] runtime-contract-dtos-presenters
Areas: skillbill.contracts, skillbill.cli, skillbill.mcp, skillbill.application, architecture tests, golden fixtures
- Added explicit JSON contract DTOs for learning, review, MCP adapter-only payloads, and shared system version/doctor payloads.
- Moved CLI text rendering for learnings and review triage onto typed presenter models instead of rendering from raw map entries.
- Reusable pattern: external payload fields should be assembled in `skillbill.contracts.*`; CLI adapters choose text presenters or JSON payloads at the boundary.
- Golden coverage now pins representative CLI import-review JSON and MCP learning-resolution JSON while existing CLI/MCP behavior remains stable.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-25] runtime-versioned-db-migrations
Areas: skillbill.db, runtime architecture tests, database migration tests
- Added `schema_migrations` as the SQLite migration ledger and made `DatabaseMigrations` run ordered named migration objects only when their version is not recorded.
- Preserved legacy additive/backfill behavior by wrapping the existing review/workflow column and feedback-event normalization helpers as versioned migrations.
- Reusable pattern: future DB changes should append a new `DatabaseMigration` entry, keep names immutable, and cover legacy compatibility plus repeated-open behavior.
- Known limitation: base schema still creates the current full schema first; versioned migrations record compatibility state inside the current single-module bootstrap.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-25] workflow-runtime-phase-5
Areas: skillbill.workflow, skillbill.application, skillbill.ports.persistence, skillbill.infrastructure.sqlite, skillbill.cli, skillbill.mcp, runtime contracts
- Added Kotlin-owned durable workflow runtime behavior for `bill-feature-task` and `bill-feature-verify`: open, update, get, list, latest, resume, and continue now route through `WorkflowService`.
- Reused the existing `feature_implement_workflows` and `feature_verify_workflows` SQLite tables through `WorkflowStateRepository`; no workflow-local store, schema redesign, Python entrypoint cutover, loader/scaffolder/install changes, launcher changes, or Python deletion were included.
- Preserved stable step ids, step state ordering, artifact patch semantics, resume summaries, session-summary hydration from telemetry session rows, blocked/done/already-running/reopened continuation decisions, and exact step-specific continuation directives in Kotlin domain/contract code.
- CLI and MCP adapters now delegate workflow calls to application services and add adapter-facing `status`/`db_path`/blocked-error payload fields at the boundary.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-24] runtime-telemetry-ported-subsystem
Areas: skillbill.application, skillbill.telemetry, skillbill.ports.telemetry, skillbill.infrastructure.http, skillbill.infrastructure.fs, skillbill.contracts.telemetry, architecture tests
- Added telemetry settings/config/client ports and wired them through Kotlin-Inject; application telemetry/status/sync/mutation paths now use ports plus `TelemetryOutboxRepository`.
- Moved telemetry config file reads/writes/deletes into `FileTelemetryConfigStore` and relay HTTP request/response mechanics into `HttpTelemetryClient`.
- Reusable contract surface: telemetry proxy batch and remote-stats payload mapping now lives in `contracts/telemetry`, outside sync orchestration.
- Reusable sync pattern: `TelemetrySyncRuntime` accepts outbox and client ports directly, with behavior coverage for disabled, noop, synced, failed, and unconfigured paths.
- Known limitation: legacy telemetry runtime objects remain as compatibility facades while review telemetry helpers are ported in later phases.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-24] runtime-domain-persistence-separation
Areas: skillbill.learnings, skillbill.review, skillbill.application, skillbill.ports.persistence, skillbill.infrastructure.sqlite, architecture tests
- Moved `LearningRecord` and learning request/source-validation rules into the learnings domain; `skillbill.learnings` now stays free of JDBC and review runtime imports.
- Added pure review parsing and triage decision parsing surfaces so application use cases no longer import mixed persistence runtimes for parsing/normalization.
- Reusable pattern: application use cases should query repository ports for source facts, then pass those facts to pure domain validation before calling write repositories.
- Reusable adapter: SQLite learning table access now lives in `SQLiteLearningStore`, with source-validation matching enforced before insert.
- Known limitation: review metrics/finished-payload helpers still contain transitional SQL until telemetry and review persistence are ported further.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-24] runtime-repository-unit-of-work
Areas: skillbill.application, skillbill.ports.persistence, skillbill.infrastructure.sqlite, skillbill.db, architecture tests
- Added persistence ports for database sessions, unit-of-work access, review repositories, learning repositories, telemetry outbox, and workflow state.
- Added SQLite adapters that wrap existing JDBC review/learning/store helpers; application services now call `read` or `transaction` on `DatabaseSessionFactory`.
- Reusable pattern: write use cases should own transaction choice in application services, while SQLite adapters call no-transaction repository helpers inside the active unit of work.
- Reusable tests: application use cases can be tested with fake repositories via `ApplicationPersistencePortTest`; SQLite transaction behavior is covered in `SQLiteDatabaseSessionFactoryTest`.
- Known limitation: review/learnings domain objects still contain JDBC-shaped helpers until the next domain-separation phase.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-24] runtime-typed-learning-results
Areas: skillbill.application, skillbill.learnings, skillbill.cli, skillbill.mcp, architecture tests
- Added typed learning result models for list, show, resolve, mutations, and delete; `LearningService` no longer returns map payloads for learning use cases.
- Added `LearningEntry` as the typed learning view over current `LearningRecord` rows while preserving the existing persisted/session JSON shape.
- CLI and MCP now convert typed learning results to their existing wire payloads at adapter boundaries; CLI text rendering consumes typed entries directly.
- Reusable pattern: typed-result phases should add architecture tests that block the targeted service from regressing to `Map<String, Any?>` returns.
- Known limitation: review and telemetry application result maps remain for later SKILL-28 phases.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-24] runtime-mcp-application-routing
Areas: skillbill.mcp, skillbill.application, skillbill.di, architecture tests, MCP tests
- Added `McpComponent` as the MCP Kotlin-Inject composition root over the shared `RuntimeComponent`.
- Routed MCP import, triage, learning resolution, stats, telemetry, version, and doctor calls through application services.
- Kept MCP telemetry-disabled skip payloads and orchestrated payload enrichment in the adapter; application services own shared persistence and payload assembly.
- Reusable pattern: architecture tests now ban MCP from importing DB/review/learnings runtime internals or telemetry runtime implementation APIs for shared workflows.
- Regression coverage compares CLI and MCP system-service payloads for version and doctor while preserving existing MCP payload tests.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-24] runtime-architecture-phase-0
Areas: runtime-kotlin architecture, RuntimeModule, architecture tests, SKILL-28 spec
- Added `ARCHITECTURE.md` as the runtime boundary map and target dependency-direction contract.
- Added architecture guardrail tests for the doc, declared package boundaries, application entrypoint independence, CLI command delegation, and future domain package infrastructure bans.
- Updated `RuntimeModule` so `application` and `di` are first-class declared subsystem packages.
- Reusable pattern: keep boundary tests scoped to rules true today, and document transitional exceptions such as MCP before enforcing them in later phases.
- Known limitation: MCP still bypasses application services until the planned Phase 1 refactor.
Feature flag: N/A
Acceptance criteria: 3/3 implemented
