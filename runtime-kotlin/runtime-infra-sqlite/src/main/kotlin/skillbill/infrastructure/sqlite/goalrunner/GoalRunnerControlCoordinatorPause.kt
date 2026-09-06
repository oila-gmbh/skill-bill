package skillbill.infrastructure.sqlite.goalrunner
import skillbill.db.goalrunner.goalRepositoryIdentity
import skillbill.db.goalrunner.migrateLegacyGoalRunnerControls
import skillbill.db.workflow.findDecomposedParentWorkflow
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.get
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.WorkflowId
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

internal fun GoalRunnerControlCoordinator.requestPause(parentWorkflowId: String): GoalRunnerControlState? =
  database.transaction { unitOfWork ->
    WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, WorkflowId(parentWorkflowId))?.let { parent ->
      migrateLegacyGoalRunnerControls(unitOfWork, parent)
      persistPauseRequest(unitOfWork, parentWorkflowId)
    }
  }

internal fun GoalRunnerControlCoordinator.requestPauseByIssueKey(
  issueKey: IssueKey,
  repoRoot: Path?,
): GoalRunnerPausePersistenceResult? = database.transaction { unitOfWork ->
  val parent = unitOfWork.workflowStates.findDecomposedParentWorkflow(
    issueKey.value,
    decompositionManifestValidator,
  ) ?: return@transaction null
  migrateLegacyGoalRunnerControls(unitOfWork, parent.toSnapshot())
  val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId.value)
  if (repoRoot != null) {
    val identity = goalRepositoryIdentity(repoRoot)
    require(existing.repositoryIdentity == null || existing.repositoryIdentity == identity) {
      "Goal parent '${parent.workflowId}' belongs to another repository."
    }
    if (existing.repositoryIdentity == null) {
      unitOfWork.goalRunnerControls.persistControlState(
        parent.workflowId.value,
        existing.copy(repositoryIdentity = identity),
      )
    }
  }
  GoalRunnerPausePersistenceResult(parent.workflowId.value, persistPauseRequest(unitOfWork, parent.workflowId.value))
}
