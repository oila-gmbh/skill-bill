package skillbill.application.goalrunner

import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.goalrunner.model.GoalRunnerRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairResult
import skillbill.application.goalrunner.model.GoalRunnerRepairStatus
import skillbill.application.goalrunner.planning.goalPlanningHardResetRemedy
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Path

internal class GoalRunnerRepairCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val childRepairStore: GoalRunnerChildRepairStore,
) {
  @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult {
    val repoRoot = request.repoRoot ?: Path.of("").toAbsolutePath().normalize()
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, repoRoot)
      ?: return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.NOT_FOUND,
      )
    manifestStore.bindRepositoryIdentity(
      loaded.parentWorkflowId,
      goalRepositoryIdentity(repoRoot),
      request.dbPathOverride,
    )
    val children = loaded.manifest.subtasks
      .filter { request.subtaskId == null || it.id == request.subtaskId }
      .filter { !it.workflowId.isNullOrBlank() }
    if (request.subtaskId != null && children.isEmpty()) {
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.NOT_FOUND,
        parentWorkflowId = loaded.parentWorkflowId,
        refusalReason = "No child workflow is bound to subtask ${request.subtaskId}.",
      )
    }
    val diagnoses = children.map { subtask ->
      childRepairStore.diagnoseChildWedges(
        workflowId = requireNotNull(subtask.workflowId),
        issueKey = request.issueKey,
        subtaskId = subtask.id,
        subtasks = loaded.manifest.subtasks,
        repoRoot = repoRoot,
        dbPathOverride = request.dbPathOverride,
      )
    }
    val wedged = diagnoses.filterNot(GoalRunnerChildWedgeDiagnosis::isHealthy)
    if (wedged.isEmpty()) {
      val status = if (request.apply && request.subtaskId != null) {
        GoalRunnerRepairStatus.NOT_WEDGED
      } else {
        GoalRunnerRepairStatus.HEALTHY
      }
      val healthy = diagnoses.firstOrNull { request.subtaskId == null || it.subtaskId == request.subtaskId }
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = status,
        parentWorkflowId = loaded.parentWorkflowId,
        diagnoses = diagnoses,
        refusalReason = if (status == GoalRunnerRepairStatus.NOT_WEDGED) {
          "Subtask ${request.subtaskId} is not wedged; passed checks: " +
            (healthy?.passedChecks?.joinToString(", ") ?: "none")
        } else {
          "Goal children passed every repair check; no durable write."
        },
      )
    }
    val hardResetRequired = wedged.any { diagnosis ->
      diagnosis.wedges.any { it.wedgeClass.operatorRequired }
    }
    if (hardResetRequired) {
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = if (request.apply) {
          GoalRunnerRepairStatus.OPERATOR_REQUIRED
        } else {
          GoalRunnerRepairStatus.INSPECTED
        },
        parentWorkflowId = loaded.parentWorkflowId,
        diagnoses = diagnoses,
        refusalReason = "Phase-output contract version is incompatible with the installed runtime. " +
          "Recover with: '${goalPlanningHardResetRemedy(request.issueKey)}'.",
      )
    }
    if (!request.apply) {
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.INSPECTED,
        parentWorkflowId = loaded.parentWorkflowId,
        diagnoses = diagnoses,
      )
    }
    val applied = mutableListOf<GoalRunnerAppliedRepair>()
    for (diagnosis in wedged) {
      val workflowId = diagnosis.workflowId ?: continue
      val liveLease = childWorkerLeaseLive(workflowId, request.dbPathOverride)
      if (liveLease) {
        return GoalRunnerRepairResult(
          issueKey = request.issueKey,
          status = GoalRunnerRepairStatus.LIVE_LEASE_REFUSED,
          parentWorkflowId = loaded.parentWorkflowId,
          diagnoses = diagnoses,
          appliedRepairs = applied,
          liveLeaseWorkflowId = workflowId,
          refusalReason =
          "Child workflow '$workflowId' holds a live worker lease; a running worker owns that state.",
        )
      }
      val repairResult = childRepairStore.applyChildWedgeRepairs(
        workflowId = workflowId,
        issueKey = request.issueKey,
        subtaskId = diagnosis.subtaskId,
        wedgeClasses = diagnosis.wedges.map { it.wedgeClass },
        repoRoot = repoRoot,
        dbPathOverride = request.dbPathOverride,
      )
      applied += repairResult.repairs
    }
    return GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = GoalRunnerRepairStatus.REPAIRED,
      parentWorkflowId = loaded.parentWorkflowId,
      diagnoses = diagnoses,
      appliedRepairs = applied,
    )
  }

  private fun childWorkerLeaseLive(workflowId: String, dbPathOverride: String?): Boolean {
    val ownership = runCatching { phaseRecorder.workerOwnership(workflowId, dbPathOverride) }.getOrNull()
      ?: return false
    return when (workerSupervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.ExactLive -> true
      FeatureTaskRuntimeProcessInspection.NotRunning,
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
      is FeatureTaskRuntimeProcessInspection.Unsupported,
      -> false
    }
  }
}
