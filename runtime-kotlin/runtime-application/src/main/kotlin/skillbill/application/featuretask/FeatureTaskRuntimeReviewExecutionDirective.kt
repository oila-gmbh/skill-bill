package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun reviewExecutionDirective(phaseId: String, inputs: ReviewExecutionDirectiveInputs): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
    return ""
  }
  return buildString {
    append(resolvedTierInfo(inputs))
    append(baselineUntrackedPolicy(inputs))
    append(materializedScope(inputs))
  }.trim()
}

private fun baselineUntrackedPolicy(inputs: ReviewExecutionDirectiveInputs): String = inputs.baselineUntrackedPaths
  .distinct()
  .sorted()
  .takeIf { it.isNotEmpty() }
  ?.let { paths ->
    """
      ## Baseline-untracked review policy
      These paths existed before this run and are excluded from the immutable-base review packet:
      ${paths.joinToString("\n") { path -> "- `$path`" }}
      The runtime-owned review driver must not re-add these paths through a branch scope or a replacement diff.
    """.trimIndent()
  }
  .orEmpty()

private fun materializedScope(inputs: ReviewExecutionDirectiveInputs): String =
  inputs.goalSubtaskReviewInput?.let { input ->
    """
    ## Immutable-base review scope
    Review only this run-owned delta from durable base `${input.reviewBaseSha}` to the current worktree (HEAD `${input.currentHeadSha}` plus staged, unstaged, and owned untracked changes).
    Do not use `origin/main...HEAD`, a merge base, the full feature branch, or a replacement baseline.
    The shared review driver resolves that scope itself from the durable base; it does not receive a pre-baked diff blob.
    """.trimIndent()
  }.orEmpty()

private fun resolvedTierInfo(inputs: ReviewExecutionDirectiveInputs): String =
  if (inputs.resolvedReviewTier != null && inputs.reviewDecidingRule != null) {
    """
    ## Resolved review mode
    AUTO resolved to ${inputs.resolvedReviewTier.wireValue} by rule "${inputs.reviewDecidingRule}".
    An explicit INLINE always overrides AUTO.
    """.trimIndent()
  } else {
    ""
  }
