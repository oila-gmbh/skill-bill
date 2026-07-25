package skillbill.application.featuretask

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

// Phase-scoped prompt directives and the per-phase task directive table, split out of
// FeatureTaskRuntimePhasePromptComposer so the composer object stays within its size budget.

// Emitted only for mutating phases (see [FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase]):
// implement and implement_fix. The directive is empty for every other phase so their prompts stay
// byte-for-byte unchanged.
internal fun mutatingPhaseIdempotencyDirective(phaseId: String): String {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
    return ""
  }
  return """
    ## Mutating-phase idempotency contract
    You are given intended-state plan inputs (the target the repository should reach) plus the
    CURRENT working tree, which may already carry some or all of those changes from a prior
    attempt that was interrupted mid-edit. Converge the tree to the target state; treat any change
    that is already applied as a no-op and NEVER blindly re-apply it (no duplicated edits, appended
    blocks, or re-created files). This phase may be re-entered or resumed after a crash, so it must
    be safe to run again: reconciling to target, not re-applying from scratch. Before finishing,
    verify every changed file is at its intended state and report that reconciled end-state in
    produced_outputs (see the reconciliation report in the required output below).
  """.trimIndent()
}

/**
 * Everything the review-execution directive needs to state the run's review depth and scope. These
 * travel together from [FeatureTaskRuntimePhasePromptComposer.compose] and are only ever read as a set.
 */
internal data class ReviewExecutionDirectiveInputs(
  val codeReviewMode: CodeReviewExecutionMode,
  val parallelReviewAgent: String?,
  val goalSubtaskReviewInput: GoalSubtaskReviewInput?,
  val reviewPassNumber: Int?,
  val resolvedReviewTier: CodeReviewExecutionMode?,
  val reviewDecidingRule: String?,
)

internal fun reviewExecutionDirective(phaseId: String, inputs: ReviewExecutionDirectiveInputs): String {
  val codeReviewMode = inputs.codeReviewMode
  val parallelReviewAgent = inputs.parallelReviewAgent
  val goalSubtaskReviewInput = inputs.goalSubtaskReviewInput
  val reviewPassNumber = inputs.reviewPassNumber
  val resolvedReviewTier = inputs.resolvedReviewTier
  val reviewDecidingRule = inputs.reviewDecidingRule
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
    return ""
  }
  val parallel = parallelReviewAgent?.takeIf(String::isNotBlank)?.let { agent ->
    " Combine it with `parallel:$agent`; both lanes must receive the resolved tier " +
      "${resolvedReviewTier?.wireValue ?: codeReviewMode.wireValue} " +
      "and the second lane must not launch parallel review recursively."
  }.orEmpty()
  val remediationPass = reviewPassNumber == 2
  // The immutable-base framing is pass one's authority only. Emitting it on pass two would contradict
  // the remediation-delta bound stated in the same prompt.
  val materializedScope = goalSubtaskReviewInput?.takeIf { !remediationPass }?.let { input ->
    """
    ## Immutable-base review scope
    Review only this run-owned delta from durable base `${input.reviewBaseSha}` to current HEAD `${input.currentHeadSha}`.
    It includes committed, staged, unstaged, and owned untracked changes below.
    Do not use `origin/main...HEAD`, a merge base, the full feature branch, or a replacement baseline.
    If parallel CLI delegation is required, give both lanes this exact diff through `--diff-file`;
    never select a branch scope.

    ${input.reviewText}
    """.trimIndent()
  }.orEmpty()
  val remediationContext = if (remediationPass) {
    val materialized = goalSubtaskReviewInput?.let { input ->
      "\nThe materialized remediation delta below runs from pre-fix tree `${input.reviewBaseSha}` to " +
        "post-fix HEAD `${input.currentHeadSha}`; treat it as authoritative and do not rediscover or " +
        "replace its scope.\n\n${input.reviewText}"
    }.orEmpty()
    """
    ## Reserved remediation pass (pass two)
    This is the reserved remediation pass under context:feature-remediation. Scope is strictly the prior Blocker findings union diff(pre-fix tree -> post-fix tree). Do not re-review the subtask's full base-to-current delta; the immutable `review_base_sha` and baseline untracked inventory are pass one's authority only. A defect introduced by the remediation itself must still be caught.$materialized
    """.trimIndent()
  } else {
    ""
  }
  val resolvedTierInfo = if (resolvedReviewTier != null && reviewDecidingRule != null) {
    """
    ## Resolved review tier
    AUTO resolved to tier ${resolvedReviewTier.wireValue} by rule "$reviewDecidingRule". An explicit INLINE or DELEGATED always overrides AUTO.
    """.trimIndent()
  } else {
    ""
  }
  return """
    ## Review execution mode
    Run `bill-code-review mode:${codeReviewMode.wireValue}` for this review. The reserved remediation pass adds context:feature-remediation and is bounded to the remediation delta. Never launch a third review pass. AUTO resolves depth by pass number: pass one to DELEGATED, every later pass to INLINE. An explicit INLINE or DELEGATED always overrides AUTO on every pass.$parallel$resolvedTierInfo$materializedScope$remediationContext
  """.trimIndent()
}

// Emits only for the commit phase of a linear-mode run: the local spec scratch is never committed
// (it is rehydrated from Linear on demand and deleted on success), so the commit step must stage by
// explicit enumeration and exclude the whole `.feature-specs/{KEY}/` tree. For local mode (default)
// the section is empty, leaving the commit prompt byte-for-byte unchanged (AC6).
internal fun commitExclusionDirective(phaseId: String, issueKey: String, specSource: SpecSource): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH || specSource != SpecSource.LINEAR) {
    return ""
  }
  return """
    ## Linear-mode commit exclusion
    This feature's spec_source is `linear`: the local spec scratch is NOT committed. Stage every
    changed path by explicit enumeration and never run `git add -A` / `git add .`. Exclude the
    entire `.feature-specs/$issueKey/` directory from staging — the parent spec, every subtask
    spec, and `decomposition-manifest.yaml`. The committed tree must contain no spec, subtask spec,
    or manifest file. The local spec scratch is deleted on terminal success and rehydrated from
    Linear when a later resume or verify needs it.
  """.trimIndent()
}

// Emits only for the commit phase of a local-mode run when a spec reference is known: the runtime
// updates the spec file with the run's completion status just before launching commit_push, so the
// agent must include it in the staged files. Empty for linear mode (spec is excluded from the commit
// there) and for all other phases, leaving those prompts byte-for-byte unchanged.
internal fun specCommitInclusionDirective(phaseId: String, specReference: String?, specSource: SpecSource): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ||
    specSource != SpecSource.LOCAL ||
    specReference.isNullOrBlank()
  ) {
    return ""
  }
  return """
    ## Spec file — stage with this commit
    The runtime updated `$specReference` with the run's completion status just before this
    phase was launched. Stage it together with the other changed files.
  """.trimIndent()
}

internal fun goalContinuationDirective(phaseId: String, suppressDecomposition: Boolean): String {
  if (!suppressDecomposition || phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
    return ""
  }
  return """
    ## Goal-continuation planning constraint
    This run is already executing one governed decomposed subtask. Do not propose or emit a new
    decomposition package in the plan phase. Produce an implementable single-subtask plan for the
    current spec; `produced_outputs.mode` must not be "decompose".
    Never include installer, uninstall, or install-sync commands in the plan: do not plan to run
    `./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any
    equivalent install refresh inside a goal-continuation child. The plan phase defines how future
    acceptance work will be implemented and validated; it does not require that work to have already
    happened. Never block planning merely because a later implementation or validation action is not
    yet complete. A blocked plan requires a genuinely missing input or an irreconcilable constraint
    that prevents an implementable plan from being produced.
  """.trimIndent()
}

// One imperative task directive per phase; the briefing carries the spec-specific scope.
internal val phaseDirectives: Map<String, String> = mapOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
    "Produce the scaled pre-planning digest for the resolved feature size. Do not modify " +
    "repository files during this phase. Emit a schema-valid produced_outputs object carrying the " +
    "bounded digest for the downstream plan phase: projection_kind \"preplanning_digest\", " +
    "contract_version \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\", and the " +
    "declared digest fields (affected_boundaries, patterns_and_decisions, risks, rollout, " +
    "validation_strategy, unresolved_questions, evidence_refs). Do not forward the complete " +
    "preplan envelope, a generic summary, or progress diagnostics.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
    "Produce an ordered implementation plan that satisfies every acceptance criterion, using " +
    "the upstream preplan digest as planning context. Do not modify repository files during " +
    "this phase. Emit a schema-valid produced_outputs object carrying the bounded executable plan: " +
    "projection_kind \"executable_plan\", contract_version " +
    "\"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\", mode (direct or decompose), " +
    "stable ordered tasks " +
    "(task_id, depends_on, description, criterion_refs, target_paths_or_symbols, test_obligations, " +
    "constraints), and validation_strategy. Exclude planning narration, presentation summary, and " +
    "generic producer notes; decomposition detail stays private to the preparation boundary.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
    "Reconcile the repository to the intended state the upstream plan output describes: make the " +
    "changes it specifies, treating any already-applied change as a no-op. See the mutating-phase " +
    "idempotency contract below. Emit produced_outputs carrying the bounded implementation receipt " +
    "(projection_kind \"implementation_receipt\", contract_version " +
    "\"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\": completed_task_ids, " +
    "normalized changed_paths, " +
    "tests_added, tests_updated, deviations, unresolved_items, " +
    "reconciliation_evidence, and the repository_checkpoint the audit will verify against). When the " +
    "briefing carries audit_gaps, reuse its immutable initial preplan and plan outputs and change " +
    "only what the latest listed gaps require; do not regenerate planning, expand scope, or disturb " +
    "settled implementation.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
    "Address the carried review Blocker AND Major findings on the CURRENT working tree as incremental " +
    "reconciliation: fix every Blocker and Major finding using the review findings, the latest implement " +
    "output, and the intended state from the briefing. Minor and Nit findings are recorded in the " +
    "unaddressed-findings ledger and are NOT in scope for this pass. Do NOT re-apply the plan from " +
    "scratch and do not expand scope beyond the Blocker and Major findings. Treat any fix already " +
    "present as a no-op. See the mutating-phase idempotency contract below.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
    "Review the implemented changes at the encoded review scope against the acceptance criteria " +
    "and report defects with concrete file references.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
    "Run the encoded completeness audit ceremony and report production-behavior or production-implementation " +
    "acceptance-criterion gaps only. Never report test adequacy, coverage, fixtures, assertions, or other " +
    "test-only concerns as audit gaps. The upstream implementation receipt is a producer CLAIM, not " +
    "evidence: read the repository itself at the resolved checkpoint in the briefing — the diff over its " +
    "base_ref/head_ref plus its scoped_owned_paths — and compare that actual state against the plan " +
    "commitment and the acceptance criteria. A criterion is satisfied only by repository evidence you " +
    "read; never mark one satisfied because the receipt lists a completed task id, a changed path, or " +
    "reconciliation_evidence claiming reconciled. A claim contradicted by the tree is itself a gap.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
    "Run tests written during the implement phase, then run the repository validation gate " +
    "relevant to the change. Fix validation findings at their root cause and rerun the gate " +
    "until it passes; validation findings are repair work, not a reason to block the phase.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
    "Invoke bill-boundary-history inline and apply its write/skip rules for the implemented " +
    "runtime change. Emit a produced_outputs object containing history_result with whether " +
    "history was written or skipped and the affected path when written.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
    "Stage and commit the implemented, reviewed, audited, validated, and history-updated " +
    "changes on the resolved feature branch, then push the branch. Stage by explicit enumerated " +
    "path; never run `git add -A` or `git add .`. Emit commit_push_result " +
    "with the commit SHA, branch name, and pushed status. If goal-continuation suppresses PR, " +
    "this successful phase is the terminal success signal for the goal subtask.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
    "Invoke bill-pr-description, honor any repo-native PR template, create or reuse the open " +
    "pull request for the branch idempotently, and emit pr_result with the PR URL/number, " +
    "title, and whether a new PR was created.",
)
