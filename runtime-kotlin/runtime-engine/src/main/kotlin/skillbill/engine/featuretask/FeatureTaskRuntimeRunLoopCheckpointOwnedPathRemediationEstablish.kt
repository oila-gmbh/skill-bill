package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

@Inject
class FeatureTaskRuntimeRunLoopCheckpointOwnedPathRemediationEstablish {
  fun concurrentlyModifiedOwnedPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    ownedPaths: List<String>,
  ): List<String> {
    val captured = runLoop.session.phaseContentIdentities[phaseId] ?: return emptyList()
    val current = runLoop.phaseGates.gitOperations.pathContentIdentities(runLoop.request.repoRoot, ownedPaths)
    if (!current.ok) return emptyList()
    val now = runLoop.collaborators.launch.parseContentIdentities(current.value.orEmpty())
    return captured.filter { (path, identity) -> path in now && now[path] != identity }.keys.sorted()
  }

  fun blockCheckpointScope(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    runLoop.collaborators.checkpointContinued6.blockCheckpoint(runLoop, precedingPhaseId, branch, error, blockedReason)
    return null
  }

  fun checkpointWorktreeDelta(runLoop: FeatureTaskRuntimeRunLoop, baselineOwnedPaths: List<String>): List<String>? {
    val owned = runLoop.phaseGates.gitOperations.repositoryOwnedPaths(runLoop.request.repoRoot)
    if (!owned.ok) return null
    val current = owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot(::isRuntimePrivatePath)
    return (baselineOwnedPaths.filterNot(::isRuntimePrivatePath) + current).distinct().sorted()
  }

  fun recordRemediationBaseSha(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    commitSha: String? = null,
  ): Boolean {
    if (!isGoalContinuationRun(runLoop.request)) return true
    if (runLoop.collaborators.planningBranch.goalReviewStateOrNull(runLoop) == null) return true
    val baseSha = commitSha?.trim()?.takeIf(String::isNotBlank) ?: run {
      val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
      if (!head.ok || head.value.isBlank()) {
        return runLoop.collaborators.repairReceipt.blockRemediationBaseSha(
          runLoop,
          precedingPhaseId,
          head.error.ifBlank { "HEAD resolved to an empty sha." },
        )
      }
      head.value.trim()
    }
    return runCatching {
      runLoop.goalContinuationRecorder.updateReviewState(
        runLoop.request.workflowId,
        runLoop.request.dbPathOverride,
      ) { state ->
        state.copy(remediationBaseSha = baseSha)
      }
    }.fold(
      onSuccess = { recorded ->
        if (recorded != null) {
          true
        } else {
          runLoop.collaborators.repairReceipt.blockRemediationBaseSha(
            runLoop,
            precedingPhaseId,
            "the review runLoop.state could not be updated.",
          )
        }
      },
      onFailure = { error ->
        runLoop.collaborators.repairReceipt.blockRemediationBaseSha(
          runLoop,
          precedingPhaseId,
          error.message.orEmpty(),
        )
      },
    )
  }

  internal fun completedImplementFixProducedOutputs(run: PhaseRun, outputMap: Map<String, Any?>): Map<String, Any?>? =
    outputMap
      .takeIf {
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX &&
          it["status"] == STATUS_COMPLETED
      }
      ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]).orEmpty() }

  fun establishRemediationCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    loopId: String,
  ): Boolean {
    if (runLoop.collaborators.checkpointContinued3.remediationCheckpointSkippable(runLoop)) {
      return runLoop.collaborators.checkpointContinued2.recordRemediationBaseIfNeeded(
        runLoop,
        precedingPhaseId,
        loopId,
        commitSha = null,
        parentSha = null,
      )
    }
    val branch = requireNotNull(runLoop.session.resolvedBranch)
    if (runLoop.collaborators.checkpointContinued3.remediationCheckpointOffBranch(runLoop, branch)) {
      return runLoop.collaborators.checkpointContinued2.recordRemediationBaseIfNeeded(
        runLoop,
        precedingPhaseId,
        loopId,
        commitSha = null,
        parentSha = null,
      )
    }
    val scope = runLoop.collaborators.checkpoint.resolveCheckpointScope(
      runLoop,
      precedingPhaseId,
      branch,
    ) { errorBranch, error ->
      runLoop.collaborators.planningBranch.remediationCheckpointBlockedReason(
        errorBranch,
        error,
      )
    } ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip ->
        runLoop.collaborators.checkpointContinued2.recordRemediationBaseIfNeeded(
          runLoop,
          precedingPhaseId,
          loopId,
          commitSha = null,
          parentSha = null,
        )
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        runLoop.collaborators.planningBranch.blockAt(runLoop, precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage ->
        runLoop.collaborators.checkpointContinued3.establishRemediationCheckpointStage(
          runLoop,
          precedingPhaseId,
          branch,
          loopId,
          scope,
        )
    }
  }
}

fun FeatureTaskRuntimeResolvedBranch.baselineOwnedPathsForCheckpoint(): List<String> =
  baselineOwnedPaths.ifEmpty { baselineUntrackedPaths }
