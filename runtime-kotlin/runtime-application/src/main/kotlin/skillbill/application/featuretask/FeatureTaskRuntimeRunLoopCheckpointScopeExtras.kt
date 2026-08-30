package skillbill.application.featuretask

import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

internal fun FeatureTaskRuntimeRunLoop.checkpointWorktreeDelta(baselineOwnedPaths: List<String>): List<String>? {
  val owned = phaseGates.gitOperations.repositoryOwnedPaths(request.repoRoot)
  if (!owned.ok) return null
  val baseline = baselineOwnedPaths.toSet()
  return owned.value.orEmpty()
    .split(OWNED_PATH_DELIMITER)
    .map(String::trim)
    .filter(String::isNotBlank)
    .filterNot { it in baseline }
    .filterNot(::isRuntimePrivatePath)
    .distinct()
    .sorted()
}

// The tracked-and-untracked baseline supersedes the untracked-only one; a run resolved before the
// wider baseline existed still has the narrower one and must keep using it rather than none.
internal fun FeatureTaskRuntimeResolvedBranch.baselineOwnedPathsForCheckpoint(): List<String> =
  baselineOwnedPaths.ifEmpty { baselineUntrackedPaths }

/**
 * The checkpoint commit has just captured the pre-fix tree, so [commitSha] (or HEAD when the
 * checkpoint was skipped) IS the pre-fix tree. The reserved remediation pass reviews
 * diff(this sha -> post-fix HEAD), which is what materializes a defect the remediation itself
 * introduces instead of leaving it to be caught incidentally.
 */
internal fun FeatureTaskRuntimeRunLoop.recordRemediationBaseSha(
  precedingPhaseId: String,
  commitSha: String? = null,
): Boolean {
  if (!isGoalContinuationRun(request)) return true
  // Without durable review state there is no reserved remediation pass to bound, so there is no
  // base to record and nothing this gate can protect.
  if (goalReviewStateOrNull() == null) return true
  val baseSha = commitSha?.trim()?.takeIf(String::isNotBlank) ?: run {
    val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    if (!head.ok || head.value.isBlank()) {
      return blockRemediationBaseSha(precedingPhaseId, head.error.ifBlank { "HEAD resolved to an empty sha." })
    }
    head.value.trim()
  }
  return runCatching {
    goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.copy(remediationBaseSha = baseSha)
    }
  }.fold(
    onSuccess = { recorded ->
      if (recorded != null) {
        true
      } else {
        blockRemediationBaseSha(precedingPhaseId, "the review state could not be updated.")
      }
    },
    onFailure = { error -> blockRemediationBaseSha(precedingPhaseId, error.message.orEmpty()) },
  )
}

internal fun FeatureTaskRuntimeRunLoop.completedImplementFixProducedOutputs(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): Map<String, Any?>? = outputMap
  .takeIf {
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX &&
      it["status"] == STATUS_COMPLETED
  }
  ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]).orEmpty() }
