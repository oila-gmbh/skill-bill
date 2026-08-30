package skillbill.application.goalrunner.model

import skillbill.application.goalrunner.GoalContinuation
import skillbill.application.goalrunner.GoalContinuationCandidate
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.db.UnitOfWork
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact

internal data class CrashReconcileExpiredWorkerRequest(
  val workflowStates: WorkflowStateRepository,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val workflowId: String,
  val continuation: GoalContinuation,
  val ownership: FeatureTaskRuntimeWorkerOwnership,
  val row: WorkflowStateRecord,
)

internal data class StaleRunningCandidatesBlockRequest(
  val unitOfWork: UnitOfWork,
  val normalizedIssueKey: String,
  val candidates: List<GoalContinuationCandidate>,
  val initialAuthoritative: Map<Int, GoalRunnerStoredOutcome>,
  val activeSet: Set<String>,
  val gate: GoalRunnerReconcileGate,
)

internal data class GoalRunnerChildRepairApplyStateInit(
  val request: GoalRunnerChildRepairApplyRequest,
  val record: WorkflowStateSnapshot,
  val artifacts: Map<String, Any?>,
  val workingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
  val workingReview: GoalSubtaskReviewState?,
)

internal data class RejectedVerificationFindingInput(
  val entry: Any?,
  val index: Int,
  val reviewRunId: String?,
  val reviewFindings: List<StructuredGoalReviewFinding>,
  val reviewById: Map<String, StructuredGoalReviewFinding>,
  val scope: UnaddressedFindingLedgerScope,
  val truncationRecords: MutableList<String>?,
)
