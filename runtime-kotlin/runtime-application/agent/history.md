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
