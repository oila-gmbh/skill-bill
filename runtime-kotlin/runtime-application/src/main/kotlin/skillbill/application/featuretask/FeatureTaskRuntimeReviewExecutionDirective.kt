package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

/**
 * The review-execution directive and the four scope/tier sections it composes.
 *
 * Split from the general per-phase directive file because these five functions form one cluster: they
 * all read the same [ReviewExecutionDirectiveInputs] set and only ever run for the review phase.
 */

internal fun reviewExecutionDirective(phaseId: String, inputs: ReviewExecutionDirectiveInputs): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
    return ""
  }
  val remediationPass = (inputs.reviewPassNumber ?: 1) >= 2
  return buildString {
    append(resolvedTierInfo(inputs))
    append(baselineUntrackedPolicy(inputs, remediationPass))
    append(materializedScope(inputs, remediationPass))
    append(remediationContext(inputs, remediationPass))
  }.trim()
}

private fun baselineUntrackedPolicy(inputs: ReviewExecutionDirectiveInputs, remediationPass: Boolean): String =
  inputs.baselineUntrackedPaths
    .distinct()
    .sorted()
    .takeIf { it.isNotEmpty() && !remediationPass }
    ?.let { paths ->
      """
      ## Baseline-untracked review policy
      These paths existed before this run and are excluded from the immutable-base review packet:
      ${paths.joinToString("\n") { path -> "- `$path`" }}
      The runtime-owned review driver must not re-add these paths through a branch scope or a replacement diff.
      """.trimIndent()
    }
    .orEmpty()

private fun materializedScope(inputs: ReviewExecutionDirectiveInputs, remediationPass: Boolean): String {
  // The immutable-base framing is pass one's authority only. Emitting it on a remediation pass would
  // contradict the remediation-delta bound stated in the same prompt.
  return inputs.goalSubtaskReviewInput?.takeIf { !remediationPass }?.let { input ->
    """
    ## Immutable-base review scope
    Review only this run-owned delta from durable base `${input.reviewBaseSha}` to current HEAD `${input.currentHeadSha}`.
    It includes committed, staged, unstaged, and owned untracked changes below.
    Do not use `origin/main...HEAD`, a merge base, the full feature branch, or a replacement baseline.
    The runtime supplies this exact child-owned diff to the shared review driver. It never selects a branch scope, origin/main...HEAD, a merge base, or a replacement baseline.

    ${input.reviewText}
    """.trimIndent()
  }.orEmpty()
}

private fun remediationContext(inputs: ReviewExecutionDirectiveInputs, remediationPass: Boolean): String =
  if (remediationPass) {
    val materialized = inputs.goalSubtaskReviewInput?.let { input ->
      "\nThe materialized remediation delta below runs from pre-fix tree `${input.reviewBaseSha}` to " +
        "post-fix HEAD `${input.currentHeadSha}`; treat it as authoritative and do not rediscover or " +
        "replace its scope.\n\n${input.reviewText}"
    }.orEmpty()
    val ledger = inputs.repairLedger
      ?.takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)
      ?.let { "\n\n" + it.boundedProjection().renderReferenceSection() }
      .orEmpty()
    val priorPass = inputs.priorReviewContext
      ?.takeUnless(FeatureTaskRuntimePriorReviewContext::isEmpty)
      ?.let { "\n\n" + it.renderReferenceSection() }
      .orEmpty()
    """
    ## Reserved remediation pass (pass ${inputs.reviewPassNumber ?: 2})
    This is a reserved remediation pass under context:feature-remediation. A remediation pass adds context:feature-remediation, is bounded to that round's remediation delta, and always executes inline. Remediation passes are unbounded: they run for as long as an unresolved Blocker or Major survives. Scope is strictly all findings addressed in that round union diff(this round's pre-fix tree -> post-fix tree). Do not re-review the subtask's full base-to-current delta; the immutable `review_base_sha` and baseline untracked inventory are pass one's authority only. A defect introduced by the remediation itself must still be caught.$materialized$priorPass$ledger
    """.trimIndent()
  } else {
    ""
  }

private fun resolvedTierInfo(inputs: ReviewExecutionDirectiveInputs): String =
  if (inputs.resolvedReviewTier != null && inputs.reviewDecidingRule != null) {
    """
    ## Resolved review mode
    AUTO resolved to ${inputs.resolvedReviewTier.wireValue} by rule "${inputs.reviewDecidingRule}". An explicit INLINE or DELEGATED always overrides AUTO.
    """.trimIndent()
  } else {
    ""
  }
