package skillbill.engine.featuretask

import skillbill.ports.workflow.gitops.stagedPaths

internal fun prepareNormalizationFromDirty(
  request: NormalizedSubtaskCommitRequest,
  dirtyPaths: List<String>,
): NormalizationPreparationOutcome {
  val staged = request.runLoop.phaseGates.gitOperations.stagedPaths(request.runLoop.request.repoRoot)
  return if (!staged.ok) {
    refusedNormalization(
      "staged content could not be inspected before normalization (${staged.error}); operator decision: clear or " +
        "explicitly attribute staged content before resuming",
    )
  } else {
    prepareNormalizationFromStaged(request, dirtyPaths, staged.value.orEmpty())
  }
}

private fun prepareNormalizationFromStaged(
  request: NormalizedSubtaskCommitRequest,
  dirtyPaths: List<String>,
  stagedOutput: String,
): NormalizationPreparationOutcome {
  val stagedPaths = stagedOutput.split('\u0000').map(String::trim).filter(String::isNotBlank)
  val foreignStaged = stagedPaths.filterNot(::isGovernedSpecPath)
    .filterNot(::isRuntimePrivatePath)
    .filterNot { it in request.ownedPaths }
  return if (foreignStaged.isNotEmpty()) {
    refusedNormalization(
      "staged path ownership is ambiguous (${foreignStaged.size} path(s) are outside the " +
        "durable subtask inventory); operator decision: preserve or attribute that staged content before normalizing",
    )
  } else {
    prepareNormalizationAfterStaged(request, dirtyPaths, stagedPaths)
  }
}

internal fun refusedNormalization(reason: String): NormalizationRefused = NormalizationRefused(reason)
