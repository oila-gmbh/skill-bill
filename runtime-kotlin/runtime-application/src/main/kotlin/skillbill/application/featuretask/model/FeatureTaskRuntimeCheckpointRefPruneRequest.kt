package skillbill.application.featuretask.model

data class FeatureTaskRuntimeCheckpointRefPruneRequest(
  val issueKey: String,
  val subtaskId: String,
  val manifestCommitSha: String?,
  val bypassEligibilityGate: Boolean = false,
  val featureBranch: String? = null,
)
