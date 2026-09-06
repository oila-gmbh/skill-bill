package skillbill.ports.goalrunner.persistence.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.engine.model.WorkflowStateSnapshot

data class GoalSubtaskIdentity(
  val workflowId: WorkflowId,
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
)

data class HistoryArtifactAppend(
  val workflowId: WorkflowId,
  val latestKey: String?,
  val historyKey: String,
  val retentionLimit: Int,
  @OpenBoundaryMap("Bounded history artifact entry map at the goal-runner durable artifact seam")
  val entryMap: Map<String, Any?>,
)

data class GoalContinuationCandidate(
  val family: WorkflowFamily,
  val snapshot: WorkflowStateSnapshot,
  val goalContinuation: GoalContinuation,
  val outcome: GoalRunnerStoredOutcome?,
)

data class GoalRunnerBlockWrite(
  val family: WorkflowFamily,
  val record: WorkflowStateSnapshot,
  val blockedReason: String,
  val lastResumableStep: String,
  val workflowStates: WorkflowStateRepository,
  val supervisionEvent: GoalRunnerSupervisionEvent?,
)
