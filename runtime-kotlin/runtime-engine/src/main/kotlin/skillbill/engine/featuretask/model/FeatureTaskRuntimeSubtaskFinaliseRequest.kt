package skillbill.engine.featuretask.model

import skillbill.engine.featuretask.FeatureTaskRuntimeCheckpointMetadata

data class FeatureTaskRuntimeSubtaskFinaliseRequest(
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val durableCommitSha: String?,
  val sequenceNumber: Int,
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
  val metadata: FeatureTaskRuntimeCheckpointMetadata,
  val manifestCommitSha: String? = null,
  val enforceReviewBoundary: Boolean = false,
  val ownedPaths: List<String> = emptyList(),
  val boundaryHistoryPaths: List<String> = emptyList(),
  val boundaryHistoryRoots: List<String> = emptyList(),
)
