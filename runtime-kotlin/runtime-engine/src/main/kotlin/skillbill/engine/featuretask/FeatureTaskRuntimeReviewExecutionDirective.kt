package skillbill.engine.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun reviewExecutionDirective(phaseId: String, inputs: ReviewExecutionDirectiveInputs): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
    return ""
  }
  return buildString {
    append(resolvedTierInfo(inputs))
    if (inputs.goalSubtaskReviewInput == null) append(baselineUntrackedPolicy(inputs))
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
      These paths existed before this run and are excluded from the last-commit review packet:
      ${paths.joinToString("\n") { path -> "- `$path`" }}
      The runtime-owned review driver must not re-add these paths through a replacement diff.
    """.trimIndent()
  }
  .orEmpty()

private fun materializedScope(inputs: ReviewExecutionDirectiveInputs): String =
  inputs.goalSubtaskReviewInput?.let { input ->
    """
    ## Last-commit review scope
    Review only the last commit `${input.currentHeadSha}` against its first parent.
    Do not use `origin/main...HEAD`, a merge base, the full feature branch, the durable implement base,
    or the current worktree. Standalone `skill-bill code-review` still reviews the caller target
    (pr, commit SHA or last, or uncommitted changes). The phase driver resolves last-commit itself; it does
    not receive a pre-baked diff blob.
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
