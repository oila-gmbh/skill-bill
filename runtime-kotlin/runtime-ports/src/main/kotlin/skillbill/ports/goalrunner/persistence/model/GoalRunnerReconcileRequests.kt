package skillbill.ports.goalrunner.persistence.model
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact

data class CrashReconcileExpiredWorkerRequest(
  val workflowStates: WorkflowStateRepository,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val workflowId: String,
  val continuation: GoalContinuation,
  val ownership: FeatureTaskRuntimeWorkerOwnership,
  val row: WorkflowStateRecord,
)

data class StaleRunningCandidatesBlockRequest(
  val unitOfWork: GoalRunnerPersistenceSession,
  val normalizedIssueKey: String,
  val candidates: List<GoalContinuationCandidate>,
  val initialAuthoritative: Map<Int, GoalRunnerStoredOutcome>,
  val activeSet: Set<String>,
  val gate: GoalRunnerReconcileGate,
)

data class GoalRunnerChildRepairApplyStateInit(
  val request: GoalRunnerChildRepairApplyRequest,
  val record: WorkflowStateSnapshot,
  val artifacts: Map<String, Any?>,
  val workingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
  val workingReview: GoalSubtaskReviewState?,
)
