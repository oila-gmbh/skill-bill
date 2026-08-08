## [2026-08-08] SKILL-173 subtask 1 — Thread validation_depth on goal continuation
Areas: runtime-application/{goalrunner,featuretask,model}, runtime-domain/workflow/{model,taskruntime}, runtime-ports/agentrun, runtime-infra-fs/launcher/agentrun, runtime-cli/featuretask, .feature-specs/SKILL-173
- Goal-continuation models carry `validation_depth` (`build_only` | `full`); omitted / legacy / non-goal defaults to `full`.
- `GoalRunner` stamps via `validationDepthForSubtask`: last non-skipped in manifest array order gets `full`, earlier non-skipped get `build_only`; skipped ordinal-last promotes the previous last non-skipped. reusable
- Status source is `DecompositionSubtask.status`, never dependency.skipped.
- CLI / command-builder / env round-trip preserves depth on launch and resume; closed-key loud-fail and resume conflict covered in tests.
- Limitation: validate phase still ignores depth until subtask 2 consumes it; this only threads and stamps the field.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-08] Goal planning shared-context packet v0.2
Areas: runtime-application/goalrunner, .feature-specs/SKILL-172-goal-planning-burst-and-context (deferred follow-on)
- `GoalPlanningSharedContextPacket.VERSION` is `0.2`; `platform_packs` removed from `PACKET_FIELDS`.
- Resume migrates intact 0.1 checkpoints in memory (verify legacy integrity, drop key, re-digest) without rewriting immutable preplan rows; `validate` stays strict on current keys only.
- Fresh assembly and `GoalPlanningContext` no longer produce or carry platform packs. `validation_guidance` rename remains deferred.
Feature flag: N/A
Acceptance criteria: N/A (follow-on to SKILL-172 deferred packet cleanup)

## [2026-08-08] SKILL-172 planning burst control: pace + empty-turn backoff (subtask 2)
Areas: runtime-application/{goalrunner,model}, .feature-specs/SKILL-172-goal-planning-burst-and-context
- Planning sweep no longer launches consecutive per-subtask plans back-to-back: `GoalPlanningBurstSchedule` applies a configurable pace between launches only (default 20s; never before first or after last).
- `EmptyProviderTurn` retries back off before relaunch (default base 30s, factor 2 → 30s then 60s); attempt 1 stays unpreceded and `MAX_FIX_LOOP_ITERATIONS` is unchanged.
- Waits go through injected `RuntimeTimingPort` in `waitSlice` chunks so durable pause and thread interrupt terminate the same way as a launch interrupt — no `Thread.sleep`, no agent/model/provider identity reads. reusable
- Defaults arithmetic is documented on the schedule: 15-subtask happy path adds `14 * 20s = 280s` (4m40s), inside default planning budget; tests drive ordinal wait recording, backoff schedule, pause-mid-wait, and interrupt-during-wait.
- Limitation: pacing/backoff apply only to the planning sweep; no adaptive rate control and no non-planning launch pacing.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-08] SKILL-168 subtask 4 — IDE status pause signals contract
Areas: runtime-application/{model,work,goalrunner}, runtime-domain/goalrunner/model, orchestration/contracts, runtime-infra-fs/contracts/workflow, .feature-specs/SKILL-168
- IDE status gains two optional additive wire fields, `pause_requested` (boolean) and `paused_at` (string instant); neither is in the schema `required` list and `contract_version` stays `0.1`, so the plugin pin needs no change.
- Semantic split: only a *consumed* pause projects `lifecycle_state: paused`. A requested-but-unconsumed pause stays `active` and surfaces as the `pause_requested` modifier — the previous projector collapsed both into `paused`.
- Omission discipline: `pause_requested` is never emitted as `false` and `paused_at` is emitted only from the durable control record; a lease-expiry-inferred pause omits the timestamp rather than back-filling from `heartbeat_at`/`updated_at` (an inference must not be sold as a record). reusable
- `pausedAt` threaded through `GoalRunnerStatusProjectionExtras` -> `GoalRunnerStatusProjection` -> `GoalRunnerStatusService`; only `projectGoal` populates the pause fields, so every non-`feature-goal` family stays wire-identical.
- Pattern reused from SKILL-165 subtask 1: schema-first additive extension (canonical schema with `additionalProperties:false` + contract-version parity test), then models/projector/goldens follow; golden fixture diff is additions-only. reusable
- Limitation: projection-only — plugin consumption of both fields is subtask 5.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-07] SKILL-165 subtask 1 — IDE status planning progress wiring
Areas: runtime-application/{model,work}, orchestration/contracts, runtime-cli, runtime-infra-fs/contracts/workflow, .feature-specs/SKILL-165
- IDE status gains an optional `planning` block (snake_case wire keys, omitted entirely when null) so goal decomposition progress is visible before any subtask exists; additive-only, so `contract_version` stays at `0.1`. reusable
- `IdeStatusProjector` maps non-prepared planning to step `planning`/label `Planning` with a progress summary; prepared or absent planning keeps prior projection behavior, and non-goal families never carry planning.
- Pattern: schema-first additive wire extension — canonical schema (`ide-status-schema.yaml`) with `additionalProperties:false` plus a contract-version parity test pins the shape, then models/projector/goldens follow. reusable
- Pause precedence reconfirmed by test: a blocked candidate stays blocked under `paused`/`pause_requested`; only an active candidate projects `paused`.
- Limitation: planning is projection-only (no consumer-side IDE rendering in this subtask); golden fixtures cover a single mid-planning goal shape.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-07] SKILL-164 subtask 3 — Audit and review projection delivery
Areas: runtime-application/{evidence,featuretask}, runtime-domain/workflow/taskruntime, runtime-ports/taskruntime, runtime-infra-fs, orchestration/contracts, .feature-specs/SKILL-164
- `FeatureTaskRuntimeSharedReviewEvidenceReference` is a closed-world projection (store_path, checkpoint_fingerprint, base_ref/head_ref, file/hunk index only) with an explicit field allowlist; no diff bytes cross the handoff boundary, so projection size stays independent of branch diff size. reusable
- `PHASE_PROJECTION_MATRIX` delivers that reference to both `PHASE_REVIEW` and `PHASE_AUDIT`; audit retains `scoped_repository_state` unchanged — the shared diff is a floor, never audit's whole evidence set. reusable
- Briefing `diff` / `current_unit_of_work` instructions name the delivered reference when present (and fall back to self-read only on the omit path); `scoped_repository_state` still requires actual repository state over upstream receipt claims.
- Backward edges (`audit_gap`, `review_fix`) reuse or re-derive via existing `REFRESH_FROM_REPOSITORY` / `MUST_MATCH` checkpoint policies; an absent referenced artifact re-derives instead of failing the run. reusable
- Pattern: deliver shared checkpoint-keyed evidence as a reference projection, not inlined bytes; keep topology, entry gates, and verdict semantics untouched. Limitation: schema registration, telemetry, and e2e wiring remain subtask 4.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-07] SKILL-164 subtask 2 — Phase-neutral shared review evidence assembly
Areas: runtime-application/evidence, runtime-application/review, runtime-ports/taskruntime, runtime-infra-fs, .feature-specs/SKILL-164
- Commit evidence assembly (`ReviewCommitUnit`/`ReviewChangedHunk`/`ReviewEvidenceTarget`) moved out of `ReviewCommitSequenceResolver` into a phase-neutral `SharedReviewEvidenceAssembly` invoked by the checkpoint-keyed shared deriver; review is now the first consumer, not the owner. reusable
- Derivation happens once per checkpoint+scope key; a fingerprint hit resolves with zero git traversal and every specialist lane (incl. `ReviewLaneBundleAssembly`) reads the same stored artifact instead of re-deriving per lane. reusable
- `SharedReviewEvidenceCodec` gives byte-for-byte identity round-trip for stored evidence, including synthetic sources (`SYNTHETIC_WORKING_TREE`/`SUPPLIED_DIFF`/`AGGREGATE_PR_DIFF`) and degraded-reason fidelity; corrupt payloads degrade to fresh derivation rather than failing the phase. reusable
- Pattern: key shared phase evidence by (repository checkpoint fingerprint, scope) at a port seam, derive once, and prove reuse with a zero-traversal assertion rather than a cache-hit counter. reusable
- Limitations: only review consumes the store; audit consumption, projection contract changes, and briefing instruction changes remain out of scope. Observable review behaviour is unchanged by design.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-06] SKILL-158 subtask 2 — Sparse commit-to-lane routing
Areas: runtime-domain/review/plan, runtime-domain/review/context/model, runtime-application/review, runtime-ports/review, runtime-infra-fs, orchestration/contracts/review-context-schema.yaml, .feature-specs/SKILL-158
- Specialist selection is now a final commit×lane matrix (`focused`|`skipped` + falsifiable reason/signals) decided once from each commit's own hunk paths/content — never the commit subject — before launch; workers do not re-decide relevance. reusable
- Required baseline lanes stay focused for every unit; optional lanes receive only evidence-matched commits, and per-lane bundles keep packet commit order with only focused hunk bodies. reusable
- Routing/assignment digests and merger provenance include the matrix; non-commit scopes keep single-synthetic-unit lane selection. Parent analysis is bounded by `max_routing_analysis_pairs`/`max_routing_analysis_bytes` and loud-fails on breach.
- Pattern: decide relevance at the parent routing seam against rubric path/content signals, assemble lane bundles from focused commits only, and prove skip reasons with adversarial fixtures rather than a worker backstop. reusable
- Limitations: single-pass bundled lane worker execution remains subtask 3; this unit only owns sparse routing and bundle preparation.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-06] SKILL-158 subtask 1 — Commit-aware review evidence model
Areas: runtime-application/review, runtime-domain/review-context-model, runtime-ports/review-model, runtime-contracts/review, orchestration/contracts/review-context-schema.yaml, .feature-specs/SKILL-158
- Review packets now carry the ordered commit sequence (sha, parent, subject, position) plus each commit's incremental hunk set, replacing the single accumulated base-to-head diff as the worker-facing evidence surface.
- Commit identity/order and per-lane bundle composition participate in packet and assignment digests; validation rejects missing, duplicate, out-of-order, unowned, or out-of-packet commit/hunk references. reusable
- Coverage is proven by an aggregate base-to-head equivalence fact (chain-and-ownership) so ordered commit units cannot silently omit or duplicate the authoritative delta. reusable
- Non-commit scopes (staged, unstaged, combined working tree, supplied diff, locally-unavailable PR) collapse to exactly one synthetic review unit with explicit source metadata — no fabricated commit history. reusable
- Pattern: Git discovery stays parent-owned; workers receive projected commit units with commit-attributed hunk bodies and never recompute scope, order, or broad diffs. Complete-diff artifacts are no longer part of the launch projection.
- Limitations: commit-to-lane routing (subtask 2) and single-pass bundled lane execution (subtask 3) are out of scope; this unit only establishes the typed evidence and serialization contract.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-05] SKILL-160 subtask 1 — Scoped per-subtask replan
Areas: runtime-application/{goalrunner,model}, runtime-cli/goal, runtime-infra-sqlite/workflow, runtime-ports/{goalrunner,persistence}, .feature-specs/SKILL-160
- Added `goal replan <key> --subtask <id>`: delete-only `deleteSubtaskPlan` discards one `goal_subtask_plans` row, retargets current-subtask intent, and leaves sibling plans, shared preplan, runtime fields, and acceptances untouched.
- Reuses `firstMissingPlan` so the next goal launch regenerates only the discarded plan; `PARTIALLY_PLANNED` status names the replanned subtask. reusable
- Pattern: new request type beside reset (never widen `GoalRunnerResetRequest`); refuse LIVE/UNKNOWN liveness, terminal targets (name `reset`), unknown key, absent/non-positive id before any durable write. reusable
- Breaking changes/limitations: does not delete the child workflow row or shared preplan (`--include-shared-preplan` is subtask 2); operator must name the subtask — no automatic staleness detection.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-01] SKILL-154 subtask 3 — Monitor-only bill-monitor skill and status integration
Areas: runtime-cli/goal, runtime-infra-fs/scaffold, governed skills, docs, .feature-specs/SKILL-154
- Added `bill-monitor` as a governed read-only entry point with dynamic catalog/install coverage and same-thread monitor-mode guidance.
- The monitor status seam canonicalizes the repository, performs one bounded snapshot, and exposes only counts, current location, liveness, and resumable state. reusable
- Pattern: keep monitor output separate from full goal status and reject diff, malformed-issue, launch, resume, watch, and mutation paths before execution.
- Known limitation: monitor mode does not carry across a new conversation; an explicit invocation is required to re-establish the read-only contract.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-01] SKILL-154 subtask 2 — Deterministic goal pause and completion control
Areas: runtime-application/{goalrunner,model}, runtime-cli/goal, runtime-domain/goalrunner, runtime-infra-fs/{agentrun,process}, runtime-infra-sqlite/{workflow,migrations}, runtime-ports/{agentrun,goalrunner,persistence}, orchestration/contracts/telemetry, .feature-specs/SKILL-154
- Added predeclared stop-after-subtask and durable idempotent operator pause control; the pause is applied after terminal child completion is persisted and before the next child is selected or launched.
- Resume preserves the parent workflow identity, planning checkpoints, commits, lease/generation fencing, and child continuation state, then starts at the first pending runnable subtask. reusable
- The foreground driver treats the original child process result as the bounded completion signal, without repeated status, log, filesystem, or database polling. reusable
- Pattern: persist completed-child state and the parent pause boundary atomically, then re-read the control projection before selecting the next child. reusable
- Breaking changes/limitations: a resumed parent keeps its existing stop-after policy; OS interrupts remain only an interruption fallback, not the operator pause protocol.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-01] SKILL-154 subtask 1 — Context-efficient skill and goal orchestration
Areas: runtime-application/{goalrunner,workflow}, runtime-cli/goal, runtime-domain/{goalrunner,workflow}, runtime-infra-fs/{goalplanning,install}, runtime-infra-sqlite/{goalrunner,migrations}, runtime-ports/{goalrunner,persistence}, runtime-contracts/errors, docs, governed feature skills, .feature-specs/SKILL-154
- Added compact supplied-versus-installed skill identity validation, bounded goal-planning context discovery, and thin `{status, commit_sha, workflow_id}` terminal retention.
- Goal launch/completion guidance now exposes one monitoring block and one terminal notification; bounded repository searches and fresh-conversation handoffs name the canonical repository path and issue key.
- Added durable goal-runner control, planning-context projections, and workflow input selection seams with contract coverage for identity mismatch, messaging, bounded output, and retention shape. reusable
- Pattern: keep child payloads, transcripts, and durable evidence out of the parent conversation while passing only validated projections and canonical handoff metadata. reusable
- Breaking changes/limitations: mismatched supplied/installed skill identities loud-fail with both source identities; pause protocol and bill-monitor remain out of scope.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-07-26] SKILL-146 subtask 3 — Bounded feature-task phase projections
Areas: runtime-application/featuretask
- Tightened audit-remediation and review-retry launches to their dedicated bounded projection sets instead of rebuilding broad upstream receipts
- Review-fix handoffs now carry the reviewed repository checkpoint through re-entry and verify the completed implement-fix fingerprint before retrying review
- Audit repair may recover its durable repair plan and state when the prior audit output is unavailable, while still requiring every other declared upstream
- Reused the phase handoff checkpoint contract for branch identity, base branch, and expected-checkpoint enforcement across remediation edges. reusable
- Preserved operator-decision pauses during carried-forward goal review so the runtime cannot settle or advance a review awaiting explicit action
- Breaking changes/limitations: completed implement-fix phases now require repository fingerprinting; fingerprint failure blocks the phase as a process failure
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-26] SKILL-143 — Bounded blocked-subtask recovery
Areas: runtime-application, runtime-cli, runtime-domain, runtime-ports, runtime-infra-sqlite, runtime-infra-fs
- Added an explicit subtask-scoped reset that deletes only an incompatible terminal child workflow while preserving planning checkpoints, unrelated subtask state, runtime fields, commits, workflow links, and out-of-band acceptances
- Soft-reset diagnostics name the blocking child workflow and exact scoped recovery command; the attempt ledger distinguishes resumable children from stale terminal children requiring deletion
- Goal-wide hard reset now preflights discarded acceptances and emits restorable commands; explicit restoration mode accepts only the cleared reset projection and recreates durable acceptance
- Pattern: classify durable child state from the workflow store, keep the manifest as a projection, and require an explicit operator action for every destructive recovery edge. reusable
- CLI, application, persistence, and regression coverage lock malformed/unknown/incompatible selector rejection, mutation boundaries, and end-to-end recovery without reaccepting unrelated work
- Breaking changes/limitations: recovery never retries automatically; blocked subtasks still require operator action, and hard-reset restoration requires the emitted explicit flag
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-25] SKILL-142 subtask 5 — Bounded inline remediation convergence
Areas: runtime-application, runtime-domain, runtime-cli, runtime-infra-fs, orchestration contracts
- Added a distinct bounded inline review tier for later remediation passes while preserving delegated review as the default full-depth path
- Auto depth now resolves from pass number through a named rule, records the resolved tier and rule, and keeps parallel lanes on one shared tier
- Remediation review is bounded to prior Blockers plus the pre-fix-to-post-fix delta; evidence-backed dispositions drive advance or resumable pause
- Added durable operator decisions; `retry_fix` uses a reusable single-use grant that discounts one capped backward edge, survives resume, and is consumed when `review_fix` re-enters `implement_fix`
- Reconciled the fixed edge cap with unresolved-Blocker semantics so cap exhaustion cannot advance a child that must pause
- Goal-facing summaries stay sanitized while location evidence remains available through the findings command
- Breaking changes/limitations: review-state contract version changed and legacy 0.1 records loud-fail; retry grants apply only to explicit `retry_fix` decisions
Feature flag: N/A
Acceptance criteria: 20/20 implemented

## [2026-07-25] SKILL-142 subtask 3 — Blocker-only reopen semantics
Areas: skills/bill-feature-goal, runtime-domain, runtime-application
- Rewrote reopen sentence in bill-feature-goal to state Blocker-only reopen without contradiction; removed mixed-signal language that suggested Major findings also trigger implement_fix
- Narrowed FeatureTaskRuntimeReviewVerdict.requiresRemediation to BLOCKER only; unchanged blocksAdvance semantics ensures only Blockers prevent advancement
- GoalSubtaskReviewSummaryReducer now counts only Blockers for remediation; Major findings are recorded in the ledger without triggering a fix pass
- Added parity test binding governed prose to runtime enum; prevents contradiction regression between skill content and runtime verdicts
- Compact summaries remove location details while ledger preserves full finding context
- Bounded-output regression test ensures path/line/hunk sanitization from goal-facing summaries
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-25] SKILL-142 subtask 2 — Planning projection gate parity
Areas: runtime-application, runtime-infra-sqlite, runtime-ports
- Added producer-side planning projection gate for preplan/plan phases; completed outputs now validate against the planning projection contract before being marked settled or checkpointed
- Introduced in-band replace path to goal planning preparation store (replaceSharedPreplan/replaceSubtaskPlan) for repairing projection-invalid records without losing the parent workflow id
- Producer gate (FeatureTaskRuntimePlanningProjectionGate) and consumer launch seam (GoalChildPlanningHydrator) now share one validation function through one validator port; closes the parity gap where hydration validated phase-output contract while consumer validated planning projection
- GoalPlanningPreparationCheckpoint rejects projection-invalid shared preplans and subtask plans at write time; descriptor() recovers subSpecHash from stored record independent of projection verdict
- Checkpoint-level acceptance tests: projection-invalid records are replaced; gate-satisfying records with different bytes still loud-fail as immutable
- Breaking changes/limitations: Shared preplan replace uses UPDATE-then-insert rather than DELETE because goal_subtask_plans cascades on the shared row (provenance safety)
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-25] SKILL-141-goal-parent-resume-lifecycle (subtask 1: parent-workflow-non-terminal-status)
Areas: runtime-application, runtime-infra-fs, runtime-mcp
- Added non-terminal `paused` parent-workflow status; GoalRunnerWorkflowStores.importFromManifestProjection and DecompositionWorkflowContinuation stamp `paused` on interruption instead of `abandoned`
- DecompositionWorkflowRuntimeLookup.findDecomposedParentWorkflow reuses non-terminal parents; isStaleAbandonedLineage never GCs them
- GoalChildPlanningHydrator hydrates from non-terminal parents preserving GoalPlanningIdentity; resume reuses the existing parent workflow id with no identity loud-fail
- FeatureTaskRuntimePhasePromptComposer updated to recognise the new status
- AgentRunCommandBuilders (runtime-infra-fs): foreground driver propagates and detects paused parent status on launch
- McpToolRegistry (runtime-mcp): removed `paused` from feature_task_runtime_workflow_update enum; runtime tool now advertises only statuses FeatureTaskRuntimePhaseWorkflowDefinition accepts; prose tool retains paused unchanged
- Explicit operator abandonment path unchanged: still terminal, reason-required, stamps operator-abandonment artifact
Feature flag: N/A
Acceptance criteria: 7/7 implemented
