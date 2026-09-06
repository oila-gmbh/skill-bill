package skillbill.application.featuretask.model
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory

data class PhasePromptHeaderInputs(
  val issueKey: IssueKey,
  val phaseId: String,
  val agentRunValidateFallback: Boolean = false,
  val packCollectAllCommand: String? = null,
  val packBuildCommand: String? = null,
  val priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
  val validationGateRepair: Boolean = false,
  val validationGateTriage: Boolean = false,
  val acceptanceCriteria: List<String> = emptyList(),
  val auditGapImplement: Boolean = false,
)
