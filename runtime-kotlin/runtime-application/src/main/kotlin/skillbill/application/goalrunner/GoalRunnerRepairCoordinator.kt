package skillbill.application.goalrunner

import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairResult
import skillbill.application.goalrunner.model.GoalRunnerRepairStatus
import skillbill.application.goalrunner.planning.goalPlanningHardResetRemedy
import skillbill.model.RepositoryRoot
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Path

internal class GoalRunnerRepairCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val childRepairStore: GoalRunnerChildRepairStore,
  private val repositoryRoot: RepositoryRoot,
) {
  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult {
    val repoRoot = request.repoRoot ?: repositoryRoot.path
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, repoRoot)
      ?: return notFound(request.issueKey)
    manifestStore.bindRepositoryIdentity(
      loaded.parentWorkflowId,
      goalRepositoryIdentity(repoRoot),
      request.dbPathOverride,
    )
    val children = loaded.manifest.subtasks
      .filter { request.subtaskId == null || it.id == request.subtaskId }
      .filter { !it.workflowId.isNullOrBlank() }
    val diagnoses = children.map { subtask ->
      childRepairStore.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = requireNotNull(subtask.workflowId),
          issueKey = request.issueKey,
          subtaskId = subtask.id,
          subtasks = loaded.manifest.subtasks,
          repoRoot = repoRoot,
          dbPathOverride = request.dbPathOverride,
        ),
      )
    }
    val wedged = diagnoses.filterNot(GoalRunnerChildWedgeDiagnosis::isHealthy)
    return when {
      request.subtaskId != null && children.isEmpty() ->
        subtaskNotFound(request, loaded.parentWorkflowId)
      wedged.isEmpty() ->
        healthyResult(request, loaded.parentWorkflowId, diagnoses)
      wedged.any { diagnosis -> diagnosis.wedges.any { it.wedgeClass.operatorRequired } } ->
        operatorRequiredResult(request, loaded.parentWorkflowId, diagnoses)
      !request.apply ->
        inspectedResult(request, loaded.parentWorkflowId, diagnoses)
      else ->
        applyRepairs(request, loaded.parentWorkflowId, diagnoses, wedged, repoRoot)
    }
  }

  private fun notFound(issueKey: String): GoalRunnerRepairResult = GoalRunnerRepairResult(
    issueKey = issueKey,
    status = GoalRunnerRepairStatus.NOT_FOUND,
  )

  private fun subtaskNotFound(request: GoalRunnerRepairRequest, parentWorkflowId: String): GoalRunnerRepairResult =
    GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = GoalRunnerRepairStatus.NOT_FOUND,
      parentWorkflowId = parentWorkflowId,
      refusalReason = "No child workflow is bound to subtask ${request.subtaskId}.",
    )

  private fun healthyResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult {
    val status = if (request.apply && request.subtaskId != null) {
      GoalRunnerRepairStatus.NOT_WEDGED
    } else {
      GoalRunnerRepairStatus.HEALTHY
    }
    val healthy = diagnoses.firstOrNull { request.subtaskId == null || it.subtaskId == request.subtaskId }
    return GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = status,
      parentWorkflowId = parentWorkflowId,
      diagnoses = diagnoses,
      refusalReason = if (status == GoalRunnerRepairStatus.NOT_WEDGED) {
        "Subtask ${request.subtaskId} is not wedged; passed checks: " +
          (healthy?.passedChecks?.joinToString(", ") ?: "none")
      } else {
        "Goal children passed every repair check; no durable write."
      },
    )
  }

  private fun operatorRequiredResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult = GoalRunnerRepairResult(
    issueKey = request.issueKey,
    status = if (request.apply) GoalRunnerRepairStatus.OPERATOR_REQUIRED else GoalRunnerRepairStatus.INSPECTED,
    parentWorkflowId = parentWorkflowId,
    diagnoses = diagnoses,
    refusalReason = "Phase-output contract version is incompatible with the installed runtime. " +
      "Recover with: '${goalPlanningHardResetRemedy(request.issueKey)}'.",
  )

  private fun inspectedResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult = GoalRunnerRepairResult(
    issueKey = request.issueKey,
    status = GoalRunnerRepairStatus.INSPECTED,
    parentWorkflowId = parentWorkflowId,
    diagnoses = diagnoses,
  )

  private fun applyRepairs(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
    wedged: List<GoalRunnerChildWedgeDiagnosis>,
    repoRoot: Path,
  ): GoalRunnerRepairResult {
    val applied = mutableListOf<GoalRunnerAppliedRepair>()
    for (diagnosis in wedged) {
      val workflowId = diagnosis.workflowId ?: continue
      if (childWorkerLeaseLive(workflowId, request.dbPathOverride)) {
        return GoalRunnerRepairResult(
          issueKey = request.issueKey,
          status = GoalRunnerRepairStatus.LIVE_LEASE_REFUSED,
          parentWorkflowId = parentWorkflowId,
          diagnoses = diagnoses,
          appliedRepairs = applied,
          liveLeaseWorkflowId = workflowId,
          refusalReason =
          "Child workflow '$workflowId' holds a live worker lease; a running worker owns that state.",
        )
      }
      val repairResult = childRepairStore.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = request.issueKey,
          subtaskId = diagnosis.subtaskId,
          wedgeClasses = diagnosis.wedges.map { it.wedgeClass },
          repoRoot = repoRoot,
          dbPathOverride = request.dbPathOverride,
        ),
      )
      applied += repairResult.repairs
    }
    return GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = GoalRunnerRepairStatus.REPAIRED,
      parentWorkflowId = parentWorkflowId,
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
