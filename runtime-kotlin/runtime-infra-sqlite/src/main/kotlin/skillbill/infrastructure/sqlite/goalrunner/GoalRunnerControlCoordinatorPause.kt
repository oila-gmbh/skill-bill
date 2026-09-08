package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.persistence.goalRepositoryIdentity
import skillbill.ports.goalrunner.persistence.migrateLegacyGoalRunnerControls
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.persistence.findDecomposedParentWorkflow
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.ports.workflow.persistence.toSnapshot
import java.nio.file.Path

internal fun GoalRunnerControlCoordinator.persistPauseRequest(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
): GoalRunnerControlState {
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  return if (existing.paused || existing.pauseRequested) {
    existing
  } else {
    unitOfWork.goalRunnerControls.persistControlState(
      parentWorkflowId,
      existing.copy(
        pauseRequested = true,
        pauseConsumed = false,
        pauseReason = GOAL_PAUSE_REASON_OPERATOR_REQUEST,
      ),
    )
  }
}

internal fun GoalRunnerControlCoordinator.requestPause(
  parentWorkflowId: String,
  dbPathOverride: String?,
): GoalRunnerControlState? = database.transaction(dbPathOverride) { unitOfWork ->
  WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)?.let { parent ->
    migrateLegacyGoalRunnerControls(unitOfWork, parent)
    persistPauseRequest(unitOfWork, parentWorkflowId)
  }
}

internal fun GoalRunnerControlCoordinator.requestPauseByIssueKey(
  issueKey: String,
  dbPathOverride: String?,
  repoRoot: Path?,
): GoalRunnerPausePersistenceResult? = database.transaction(dbPathOverride) { unitOfWork ->
  val parent = unitOfWork.workflowStates.findDecomposedParentWorkflow(
    issueKey,
    decompositionManifestValidator,
  ) ?: return@transaction null
  migrateLegacyGoalRunnerControls(unitOfWork, parent.toSnapshot())
  val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
  if (repoRoot != null) {
    val identity = goalRepositoryIdentity(repoRoot)
    require(existing.repositoryIdentity == null || existing.repositoryIdentity == identity) {
      "Goal parent '${parent.workflowId}' belongs to another repository."
    }
    if (existing.repositoryIdentity == null) {
      unitOfWork.goalRunnerControls.persistControlState(
        parent.workflowId,
        existing.copy(repositoryIdentity = identity),
      )
    }
  }
  GoalRunnerPausePersistenceResult(parent.workflowId, persistPauseRequest(unitOfWork, parent.workflowId))
}
