package skillbill.application.featuretask.model

import skillbill.application.featuretask.FeatureTaskRuntimeCheckpointMetadata

data class FeatureTaskRuntimeSubtaskFinaliseRequest(
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val durableCommitSha: String?,
  val sequenceNumber: Int,
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
  val metadata: FeatureTaskRuntimeCheckpointMetadata,
  val manifestCommitSha: String? = null,
)
