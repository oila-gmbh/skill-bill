package skillbill.application.featuretask.model
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId

data class FeatureTaskRuntimeCheckpointRefPruneRequest(
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val manifestCommitSha: String?,
  val bypassEligibilityGate: Boolean = false,
  val featureBranch: String? = null,
)
