# SKILL-202 — One review pass, verified findings, one fix round

## Intended Outcome

A subtask child reviews its delta exactly once. Every finding that pass produces
is then verified against the subtask's declared intent and against the boundary
memory of the module the finding touches. Findings that survive verification are
fixed in exactly one remediation round, at any severity including Minor and Nit.
The run then advances to validate. Review never runs a second time, and no
finding severity can reopen it.

## Current Behaviour

`FeatureTaskRuntimePhaseWorkflowDefinition` declares the pipeline
`preplan, plan, implement, audit, plan_fix, implement_fix, review, validate,
write_history, commit_push, pr`, with `plan_fix` and `implement_fix` loop-only so
a clean run skips them. One backward edge drives review remediation:

- `review --changes_requested--> plan_fix`, `loopId = review_fix`,
  `perEdgeCap = null`, `warnAfterIterations = 3`
  (`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt:674`).
  Uncapped by design: the triggering verdict is the only exit.
- `FeatureTaskRuntimeReviewVerdict.verdict` derives `changes_requested` from
  `severity.requiresRemediation`, and `GoalSubtaskReviewState.blocksAdvance`
  (`.../model/GoalSubtaskReviewState.kt:136`) treats `blocker` and `major` as
  advance-blocking. So severity, not correctness, is the re-entry signal.
- Because `implement_fix` sits immediately before `review` in `stepIds`, the
  round's forward edge lands back on `review`, and the whole
  `review_base_sha`-to-worktree delta is reviewed again.

The uncapped edge is not the whole cost. Removing it strands a set of constructs
that exist only because remediation can run more than once. Traced; do not
re-derive:

- `plan_fix` itself: 49 non-test references across 10 files, plus 27 test files.
  `stepIds`, `stepLabels` (`Phase 4a: Plan Fix`), `requiredArtifactsByStep`
  (including `implement_fix to listOf(PHASE_REVIEW, PHASE_PLAN_FIX)`),
  `resumeActions`, `GENERATION_SCOPED_PHASE_IDS`, `OUTPUT_RETRY_PHASES`,
  `FeatureTaskRuntimeOutputVerification.kt:30`,
  `FeatureTaskRuntimePhaseProjectionShapes.kt:23`,
  `FeatureTaskRuntimePhasePromptDirectives.kt:315`, `FeatureTaskRuntimeRunState.kt:180`,
  and eight sites in `FeatureTaskRuntimeRunLoop.kt`.
- The `escalated` root-cause path: `FeatureTaskRuntimeVerdict.PLAN_FIX_VERDICTS`
  (`.../model/FeatureTaskRuntimeVerdict.kt:65`), `FeatureTaskRuntimeRepairPlan`
  with its `local_patch_site` and `design_symptom` classification, and the
  operator pause the classification raises.
- The review non-convergence and churn pauses:
  `pauseOnReviewRemediationNonConvergence` (`FeatureTaskRuntimeRunLoop.kt:679,2170`),
  `REMEDIATION_CHURN_CONSECUTIVE_ROUND_THRESHOLD`,
  `REMEDIATION_ESCALATION_EVIDENCE_MIN_CONSECUTIVE_ROUNDS`, and
  `FeatureTaskRuntimeReviewRemediationFindingIdentities` with the review-side
  helpers in `FeatureTaskRuntimeAuditRepairProgressDetection.kt`. All three
  conditions compare consecutive rounds, so one round makes them unreachable.
- The unresolved-Blocker pause: `FeatureTaskRuntimeNextPhase.TerminalPause`,
  `FeatureTaskRuntimeTransitionFunction.terminalPauseFor`,
  `FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE_UNLESS_UNRESOLVED_BLOCKER`,
  `pauseOnUnresolvedBlocker` (`FeatureTaskRuntimeRunLoop.kt:2320`), and
  `FeatureTaskRuntimeOperatorRetryGrant` with the `retry_fix`,
  `accept_and_advance`, and `abandon_subtask` decisions.
- The repair ledger's cross-round vocabulary: `superseded` and `reopened`
  statuses, the `disturbed_remedies` declaration gate
  (`orchestration/contracts/goal-subtask-review-state-schema.yaml:337`), and the
  ledger's role as a prompt projection for `plan_fix` and for the remediation
  review pass.
- The remediation review context `context:feature-remediation`, governed in
  `orchestration/review-orchestrator/PLAYBOOK.md:68`,
  `orchestration/skill-classes/code-review-shell.yaml:25`, and
  `skills/bill-code-review/content.md:15`, and claimed as the runtime's
  re-review path in `skills/bill-feature-task-runtime/content.md:248` and
  `skills/bill-feature-goal/content.md:139,439`.

## Verified Root Cause

Traced in the code and in a real run; do not re-derive.

The loop re-enters on a finding's severity rather than on whether the finding is
right, and each remediation round is new code that the next pass reviews. A
remediation can therefore mint the findings that justify the next round. Rounds
multiply on the reviewer's own churn, and every round pays for a full re-review
of the entire delta.

Observed on capmo-android WE-4863 subtask 2. Pass one objected to test
placement. The remediation satisfied it by deleting a ViewModel-level assertion
and re-homing the coverage at the screen level. Pass two then raised three
findings, all located in the files that remediation had just authored, including
a Major for the deleted assertion. Three `review_fix` iterations went to test
placement while the two production lines stayed untouched from the first
commit, and the run ended at a provider spend limit.

Three pieces of the wanted design already exist and must be reused rather than
rebuilt:

- `SpecIntentProjectionResolver`
  (`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/SpecIntentProjectionResolver.kt`)
  owns `SpecIntentProjectionExtractor` and already resolves a budgeted intent
  projection from a governed spec, including the manifest-unreadable and
  parent-spec-unavailable degradation seams. `ParallelCodeReviewRunner:149` is
  its current consumer.
- `GoalPlanningContextDiscovery` and `GoalPlanningBoundaryBodyResolver`
  (ports, with `FileSystemGoalPlanningContextDiscovery` and
  `FileSystemGoalPlanningBoundaryBodyResolver` implementations) already model
  titles-only boundary discovery against the caps in `GoalPlanningContext`:
  `MAX_DISCOVERY_FILE_COUNT = 32`, `MAX_HEADINGS_PER_FILE = 64`,
  `MAX_CATALOG_HEADINGS = 256`, `MAX_BOUNDARY_FILE_BYTES = 128 KiB`,
  `MAX_SELECTED_BODIES = 24`, `MAX_BODY_BYTES = 8192`,
  `MAX_TOTAL_BODY_BYTES = 64 KiB`. Discovery parses each file inside the
  runtime; unselected bodies never reach a prompt.
- `UnaddressedFindingsLedgerService`
  (`.../application/goalrunner/UnaddressedFindingsLedgerService.kt`) already owns
  the goal-wide ledger that `skill-bill goal findings` reads.

## Target Topology

```
preplan -> plan -> implement -> audit -> review -> verify_findings -> [implement_fix] -> validate -> write_history -> commit_push -> pr
```

- `review` has no edge of its own. Its verdict is recorded and never routes.
- `verify_findings` is a new forward phase settling `findings_verified` when at
  least one finding survived, else `no_findings_verified`.
- `implement_fix` stays in `loopOnlyPhaseIds` and moves to sit between
  `verify_findings` and `validate`, so `forwardTransition` skips it on a clean
  run and its own forward edge lands on `validate`.
- The fix round is one entry in `FeatureTaskRuntimeTransitionDeclaration.backwardEdges`:
  `fromPhaseId = verify_findings`, `triggeringVerdict = findings_verified`,
  `destinationPhaseId = implement_fix`, `loopId = review_fix`, `perEdgeCap = 1`,
  `capExhaustionBehavior = ADVANCE`, `capScope = PER_SUBTASK`. The declaration
  model puts caps and exhaustion behaviour on declared edges only; the default
  forward edge is index+1 and carries neither, so a capped one-shot round has to
  be a declared edge. Nothing requires a declared edge to point backwards:
  `spanBetween` falls back to the destination alone when the indices do not
  bracket, which is exactly a one-shot round's invalidation span.
- A phase entry gate makes the rule structural: `implement_fix` requires
  `verify_findings` to have settled `findings_verified`.
  `FeatureTaskRuntimeTransitionFunction` guards the computed target, so the gate
  fires for forward advance and for cap-exhaustion advance alike, throwing
  `FeatureTaskRuntimePhaseOrderViolationError`.
- `plan_fix` is removed. Its only caller was the `review_fix` edge.

## Wiring Inventory For A New Phase Id

Traced; do not re-derive. A phase id in `stepIds` needs all of these, and the
first four are hard loud-fails rather than degradations.

- `PHASE_PROJECTION_MATRIX` entry (`FeatureTaskRuntimePhaseWorkflowDefinition.kt:434-601`).
  `phaseDeclarations` builds as `stepIds.associateWith { requireNotNull(PHASE_PROJECTION_MATRIX[it]) }`
  at `:616-632`, so a missing entry throws at object init, not at run time. An
  empty declaration list is a valid entry.
- `DEFAULT_PHASE_TIERS` entry (`runtime-domain/.../config/model/ExecutionMatrixModels.kt:20-32`),
  read through `getValue` at `:49`.
- `phaseDirectives` entry (`FeatureTaskRuntimePhasePromptDirectives.kt:277-387`),
  read through `error("No phase directive for runtime phase '<id>'.")` at
  `FeatureTaskRuntimePhasePromptValidateDirectives.kt:60-72`.
- Both step-id enums in `orchestration/contracts/workflow-state-schema.yaml:126-139,148-160`,
  with the `WORKFLOW_STATE_CONTRACT_VERSION` bump decision that
  `WorkflowStateSchemaContractVersionTest.kt:95-130` forces.
- `stepIds`, `stepLabels`, `requiredArtifactsByStep`, `resumeActions`.
- Test pins that assert the phase set exactly:
  `FeatureTaskRuntimePhaseWorkflowDefinitionTest.kt:38-78` (order and labels),
  `:132-146` (DAG), `:238-262` and `:~362` (closed-world declarations),
  `:598-626` (exact `entryGates` and backward-edge triples);
  `ExecutionMatrixModelsTest.kt:170-188`;
  `FeatureTaskRuntimePhasePromptComposerTest.kt:456-478`;
  `WorkflowStateSchemaValidatesExistingWorkflowsTest.kt:83-140`; and the MCP
  golden `runtime-mcp/src/test/resources/golden/mcp-feature-task-runtime-workflow.json`,
  which enumerates every step row three times.
- `IdeStatusProjector.kt:336` uses `stepIds.size` as the progress denominator.

Not required, contrary to what the phase count suggests: no runner (the run loop
launches an unmarked phase generically as an agent phase, with no
`when (phaseId)` dispatch), no branch in
`feature-task-runtime-phase-output-schema.yaml` (`phase_id` is an open string at
`:308-310`; per-phase `allOf` branches are optional), no telemetry schema change
(`phase_id` is a free string, with no closed runtime-phase enum), and no IntelliJ
change (the plugin treats `phaseId` as opaque). Opt-in sets to decide rather than
populate: `OUTPUT_RETRY_PHASES`, `MUTATING_PHASES`,
`GENERATION_SCOPED_PHASE_IDS`, `REGENERATION_PRODUCER_BY_CONSUMER`.

## Decisions

Settled at spec level so no subtask re-litigates them.

- The loop id stays `review_fix`. It names the same thing, and renaming it would
  invalidate durable loop accounting, checkpoint identities, status output, and
  finished telemetry for no behavioural gain.
- The `escalated` verdict, `FeatureTaskRuntimeRepairPlan`, and the
  `design_symptom` classification are removed with `plan_fix`. Root-cause
  classification before an edit was a multi-round device.
- The review non-convergence pause, the churn pause, and the unresolved-Blocker
  pause are removed from the review path, together with
  `FeatureTaskRuntimeOperatorRetryGrant` and the `retry_fix`,
  `accept_and_advance`, and `abandon_subtask` decisions where those exist only to
  release a review-path pause. Whatever `audit_gap` still needs from
  `FeatureTaskRuntimeAuditRepairProgressDetection` stays.
- The per-round repair receipt stays: one round still records what it changed.
  The cross-round ledger vocabulary (`superseded`, `reopened`) and the
  `disturbed_remedies` declaration gate go, and the ledger stops being a prompt
  projection because no phase re-reads it.
- `context:feature-remediation` stays valid in the review contract
  (`bill-code-review`, `PLAYBOOK.md`, `code-review-shell.yaml`): changing the
  review packet contract is a non-goal. What goes is every runtime claim to use
  it, since there is no second pass to bound.
- Verification runs intent-only until boundary memory lands, and keeps that as
  its permanent fallback for a finding path no eligible boundary owns.
- The phase id is `verify_findings`, but Kotlin identifiers for it must not read
  as `verifyFindings`: `FeatureTaskRuntimeValidationGateCoordinator.kt:163-183`
  already owns a `verifyFindings` member meaning the post-repair confirmation
  gate run inside `validate`, which is a different thing entirely.

## Acceptance Criteria

1. A subtask runs the `review` phase exactly once. No verdict, severity, or finding count can re-enter it, and no code path re-reviews a remediation delta.
2. Every finding from that pass is verified individually against the subtask's spec intent projection and against boundary memory scoped to the finding's path, yielding a verified-or-rejected disposition plus a bounded reason.
3. A verified finding is fixed regardless of severity. Minor and Nit findings are fixed in the same round as Blocker and Major.
4. A rejected finding is never fixed and is recorded in the unaddressed-findings ledger with its rejection reason, retrievable through `skill-bill goal findings --issue-key <KEY>`.
5. The remediation round runs at most once per subtask, driven by one declared edge with `perEdgeCap = 1` and `ADVANCE` exhaustion behaviour.
6. The run advances to `validate` after the fix round regardless of whether the fix resolved every verified finding. No severity blocks advancement and no unresolved-finding, non-convergence, or churn pause remains on this path.
7. `implement_fix` is unreachable unless `verify_findings` settled `findings_verified`, enforced by a declared phase entry gate rather than by a branch in the run loop.
8. Verification reads boundary memory by title first: it receives a catalog of headings with stable ids scoped to the boundaries that own the finding paths, selects ids semantically, and receives only the selected bodies under verification-specific caps tighter than the planning caps.
9. No verification path delivers a whole `history.md` or `decisions.md` to a prompt, and no path widens discovery to boundaries that own none of the finding paths.
10. The `audit_gap` loop and the `record_rejected` regeneration edges keep their current behaviour and caps.
11. Durable state written before this change stays decodable, and a record whose shape this change invalidates loud-fails with a named error rather than being coerced.
12. Resume lands correctly when a run is interrupted inside `verify_findings` or inside the single `implement_fix` round, without minting a second round.
13. Every prompt-visible and operator-visible surface describes the new flow. No surface still claims that remediation continues while findings survive, that Blocker and Major reopen `implement_fix`, that Minor and Nit merely advance, or that a later pass runs against a remediation delta.
14. No unreachable remnant of the multi-round design survives: no phase, verdict, pause, ledger status, or prose claim that only a second round could produce.
15. The repository validation gate passes.

## Constraints

- Topology is declaration data. Express the change in
  `FeatureTaskRuntimeTransitionDeclaration` (`forwardPhaseIds`, `backwardEdges`,
  `entryGates`, `loopOnlyPhaseIds`, `loopOnlySuccessors`), not as phase-identity
  branches in `FeatureTaskRuntimeTransitionFunction` or the run loop.
- Every subtask commit leaves a runnable pipeline. A phase id in `stepIds`
  without its full wiring set (below) does not fail late at run time; it throws at
  object init and takes most of the runtime test suite with it.
- Prose that governs runtime behaviour ships in the same commit as the behaviour
  it describes. `skills/bill-feature-task-runtime/content.md` and
  `skills/bill-feature-goal/content.md` are read by the agent at run time.
- Telemetry for a phase ships in the same commit as the phase. A commit that
  persists dispositions without emitting a record violates
  `docs/observability-policy.md`.
- Reuse `SpecIntentProjectionResolver` for intent. Do not add a second spec
  reader and do not call `SpecIntentProjectionExtractor` around the resolver.
- Reuse the `GoalPlanningContextDiscovery` and `GoalPlanningBoundaryBodyResolver`
  machinery for boundary memory. Scope it by path and tighten its caps; do not
  fork a parallel discovery implementation and do not relax
  `MAX_BOUNDARY_FILE_BYTES`.
- Verification is one child phase settling once per subtask. It never loops, and
  it never edits the worktree.
- Severity keeps its reporting role in findings and the ledger. It must stop
  being control flow on the review path.
- Parallel review stays two full lanes counting as the single pass. Neither lane
  may recursively launch review.
- Contract and schema changes follow the existing versioning discipline: bump
  what the durable contract requires and loud-fail an incompatible record.
- Validation runs through the runtime-owned gate declared in
  `.skill-bill/config.yaml` (`validation_gate.gradle_wrapper:
  runtime-kotlin/gradlew`). Read the gate's findings rather than trusting a
  wrapper exit code.
- Branch prefix `feat/`. No AI co-author footers.

## Accepted Trade-off

With no re-review, a verified finding whose single fix round fails to resolve it
reaches `validate` unfixed. That is deliberate: the ledger records it, the
validation gate still runs, and the cost of a guaranteed second full review is
the problem this change exists to remove. Do not reintroduce a re-review to close
this gap.

## Non-Goals

- Changing the `audit_gap` loop, the `record_rejected` quarantine edges, or the
  audit-first ordering.
- Changing planning's own boundary discovery, its caps, or its allowlist.
- Adding a second remediation round, a conditional re-review, or an operator
  decision gate on unresolved findings.
- Teaching verification to consult git history, PR review comments, or other
  subtasks' ledgers.
- Changing review lane assembly, evidence brokering, or the review packet
  contract, including the validity of `context:feature-remediation` in
  `bill-code-review`.
- Cross-subtask or cross-goal learning from rejection reasons.
- Renaming the `review_fix` loop id.

## Subtasks

1. `spec_subtask_1_collapse-review-remediation-to-one-round.md` — collapse review
   remediation to a single bounded fix round and remove every construct that only
   a second round could reach.
2. `spec_subtask_2_verify-findings-phase.md` — add the `verify_findings` phase
   end to end, gate the fix round on per-finding intent verification, and surface
   dispositions.
3. `spec_subtask_3_scoped-boundary-memory-for-verification.md` — give
   verification path-scoped, titles-first boundary memory under tightened caps.
