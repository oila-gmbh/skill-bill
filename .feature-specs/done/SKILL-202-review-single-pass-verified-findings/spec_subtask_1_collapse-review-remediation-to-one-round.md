# SKILL-202 Subtask 1 — Collapse review remediation to one bounded round

## Intended Outcome

Review remediation runs at most once per subtask. The uncapped
`review --changes_requested--> plan_fix` edge becomes a single capped edge
`review --changes_requested--> implement_fix` with `perEdgeCap = 1` and
`capExhaustionBehavior = ADVANCE`, so the round runs once and the run advances to
`validate` whatever its outcome. `plan_fix` and every construct only a second
round could reach are removed, and the prose the agent reads at run time
describes the collapsed flow in this same commit. No unresolved finding, severity,
non-convergence condition, or churn condition pauses or blocks the run on this
path.

This subtask ships the fix for the observed failure on its own. It does not add
verification; severity still triggers the one round it now allows.

## Scope

Topology, in `FeatureTaskRuntimePhaseWorkflowDefinition`:

- `stepIds` becomes `preplan, plan, implement, audit, review, implement_fix,
  validate, write_history, commit_push, pr`. `implement_fix` stays in
  `loopOnlyPhaseIds`, so `forwardTransition` skips it and its own forward edge
  lands on `validate`.
- `loopOnlySuccessors` becomes empty. `requiredArtifactsByStep` for
  `implement_fix` becomes `listOf(PHASE_REVIEW)`.
- One declared edge replaces the old one: `fromPhaseId = review`,
  `triggeringVerdict = changes_requested`, `destinationPhaseId = implement_fix`,
  `loopId = review_fix`, `perEdgeCap = 1`,
  `capExhaustionBehavior = ADVANCE`, `capScope = PER_SUBTASK`, no
  `warnAfterIterations` (a bound of one has nothing to warn about).
- `PHASE_PLAN_FIX` and every keyed entry go: constant, `stepLabels`,
  `resumeActions`, `PHASE_PROJECTION_MATRIX`, `GENERATION_SCOPED_PHASE_IDS`,
  `OUTPUT_RETRY_PHASES`, `DEFAULT_PHASE_TIERS`, `phaseDirectives`,
  `FeatureTaskRuntimePhaseProjectionShapes`, `FeatureTaskRuntimeOutputVerification`'s
  `planFixVerdict` branch, and the `plan_fix` sites in
  `FeatureTaskRuntimeRunState`, `FeatureTaskRuntimeRunLoop`, and
  `FeatureTaskRuntimeGoalContinuationRecorder`.

Removals the parent spec's Decisions section settles:

- The `escalated` path: `FeatureTaskRuntimeVerdict.PLAN_FIX_VERDICTS`,
  `REPAIR_PLANNED`, `ESCALATED`, `FeatureTaskRuntimeRepairPlan`, and the
  `local_patch_site` / `design_symptom` classification.
- The review non-convergence and churn pauses:
  `pauseOnReviewRemediationNonConvergence`,
  `REMEDIATION_CHURN_CONSECUTIVE_ROUND_THRESHOLD`,
  `REMEDIATION_ESCALATION_EVIDENCE_MIN_CONSECUTIVE_ROUNDS`, and
  `FeatureTaskRuntimeReviewRemediationFindingIdentities` with its helpers.
  Whatever `audit_gap` still needs from
  `FeatureTaskRuntimeAuditRepairProgressDetection` stays.
- The unresolved-Blocker pause: `FeatureTaskRuntimeNextPhase.TerminalPause`,
  `FeatureTaskRuntimeTransitionFunction.terminalPauseFor`,
  `FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE_UNLESS_UNRESOLVED_BLOCKER`,
  `FeatureTaskRuntimeTransitionContext.unresolvedBlockerPresent`,
  `pauseOnUnresolvedBlocker`, and `FeatureTaskRuntimeOperatorRetryGrant` with the
  `retry_fix`, `accept_and_advance`, and `abandon_subtask` decisions where they
  exist only to release a review-path pause.
- `GoalSubtaskReviewState`'s advance block: severity and unresolved-finding count
  stop gating advancement. `blocksAdvance` survives on the compact finding as a
  reporting field for the summary counts in `GoalSubtaskReviewSummaryReducer`.
- The repair ledger's cross-round vocabulary: `superseded` and `reopened`, the
  `disturbed_remedies` declaration gate, and the ledger's delivery as a prompt
  projection. The per-round repair receipt stays.
- The `SEMANTIC_LOOP_WARNING_THRESHOLD` reference on the review path.
  `audit_gap` keeps it.

Contracts:

- `workflow-state-schema.yaml`: drop `plan_fix` from both step-id enums, with the
  `WORKFLOW_STATE_CONTRACT_VERSION` bump decision `WorkflowStateSchemaContractVersionTest`
  forces.
- `feature-task-runtime-phase-output-schema.yaml`: drop the `plan_fix` branch.
- `goal-subtask-review-state-schema.yaml`: drop the cross-round ledger statuses
  and the `disturbed_remedies` gate.
- A durable record carrying a removed phase id, verdict, or ledger status
  loud-fails with a named error. It is not coerced into the new shape.

Prose, in this commit because the agent reads it at run time:

- `../../../skills/bill-feature-task-runtime/content.md` phase order and the
  remediation-loop section.
- `../../../skills/bill-feature-goal/content.md` child-review paragraphs, including the
  claim that a later pass runs via `context:feature-remediation`. The review
  contract keeps that context; the runtime stops claiming it.
- `../../../runtime-kotlin/ARCHITECTURE.md` where it names `plan_fix` or the uncapped loop.
- Regenerate the governed `SKILL.md` outputs the generated-artifact guard checks.

## Acceptance Criteria

1. A subtask runs the `review` phase exactly once. No verdict, severity, or finding count can re-enter it, and no code path re-reviews a remediation delta.
2. `plan_fix` is absent from the phase set, the transition declaration, the prompt directives, the projection matrix, the tier defaults, the output verification, and the workflow-state step-id enums.
3. Exactly one declared edge drives review remediation: `review --changes_requested--> implement_fix` with `loopId = review_fix`, `perEdgeCap = 1`, `capExhaustionBehavior = ADVANCE`, and `capScope = PER_SUBTASK`.
4. `implement_fix` is loop-only, sits immediately before `validate`, and its forward edge lands on `validate`. A clean run never launches it.
5. The run advances to `validate` after the fix round regardless of whether the round resolved every finding. No severity, unresolved-finding count, non-convergence condition, or churn condition blocks or pauses the run on this path.
6. The `escalated` verdict, the repair-plan root-cause classification, the review non-convergence and churn pauses, the unresolved-Blocker pause, and the cross-round repair-ledger statuses are removed. No construct reachable only by a second round survives.
7. The `audit_gap` loop and the `record_rejected` regeneration edges keep their current behaviour, caps, and warning threshold.
8. Durable state written before this change stays decodable where its shape is still valid, and a record naming a removed phase, verdict, or ledger status loud-fails with a named error rather than being coerced.
9. Resume lands correctly when a run is interrupted inside the single `implement_fix` round, and no resume path mints a second round.
10. `../../../skills/bill-feature-task-runtime/content.md`, `../../../skills/bill-feature-goal/content.md`, and `../../../runtime-kotlin/ARCHITECTURE.md` describe one review pass and at most one fix round. No surface claims that remediation continues while findings survive, that Blocker and Major reopen a loop, or that a later pass runs against a remediation delta.
11. `(cd runtime-kotlin && ./gradlew check --continue)` passes.

## Non-Goals

- Adding the `verify_findings` phase or any per-finding verification (subtask 2).
- Making the fix round severity-independent. Severity still triggers the one
  round this subtask allows; subtask 2 replaces the trigger.
- Boundary-memory scoping (subtask 3).
- Changing `context:feature-remediation`'s validity in `bill-code-review`,
  `PLAYBOOK.md`, or `code-review-shell.yaml`.
- Changing review lane assembly, evidence brokering, or the review packet contract.
- Renaming the `review_fix` loop id.

## Dependency Notes

None. This subtask establishes the collapsed topology the other two build on,
and is independently shippable: it removes the runaway loop without waiting for
verification.

## Validation Strategy

Update the pinned topology tests (`FeatureTaskRuntimePhaseWorkflowDefinitionTest`
order, labels, DAG, closed-world declarations, and the exact entry-gate and
backward-edge assertions), `ExecutionMatrixModelsTest`,
`FeatureTaskRuntimePhasePromptComposerTest`,
`WorkflowStateSchemaValidatesExistingWorkflowsTest`,
`WorkflowStateSchemaContractVersionTest`, and the MCP golden workflow JSON.
Replace `UnboundedRemediationLoopRegressionTest` and
`ReviewFixCapReconciliationTest` with the bounded-round contract they now
describe: one round on `changes_requested`, then advance on the second firing.
Add a decode test that a durable record naming `plan_fix` or `escalated`
loud-fails with a named error. Add a resume test interrupted inside
`implement_fix` asserting no second round is allocated. Run the repository
validation gate.

## Next Path

Proceed to subtask 2 once the collapsed topology is green and the fix round is
capped at one.
