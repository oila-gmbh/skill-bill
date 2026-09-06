package skillbill.application.featuretask.model
import skillbill.agent.model.AgentId
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

data class GoalReviewPhaseCompletionRequest(
  val phaseState: FeatureTaskRuntimePhaseStateRequest,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

data class FeatureTaskRuntimeProjectionRejection(
  val workflowId: WorkflowId,
  val consumerPhaseId: String,
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpointFingerprint: String?,
  val failureClassification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
)

sealed class FeatureTaskRuntimeProducerOutputRead {
  data class Found(val evidence: ProducerOutputEvidence) : FeatureTaskRuntimeProducerOutputRead()
  data object Absent : FeatureTaskRuntimeProducerOutputRead()
  data class Unreadable(
    val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) : FeatureTaskRuntimeProducerOutputRead()
}

data class ProducerOutputQueryArgs(
  val workflowId: WorkflowId,
  val phaseId: String,
  val attempt: Int,
  val agentId: AgentId,
  val generation: Int,
)

data class AppendCheckpointIdentityArgs(
  val workflowId: WorkflowId,
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val branch: String,
  val phaseId: String,
  val loopId: String?,
  val generation: Int,
  val parentSha: String?,
  val ownedPaths: List<String>,
  val commitSha: String,
)
