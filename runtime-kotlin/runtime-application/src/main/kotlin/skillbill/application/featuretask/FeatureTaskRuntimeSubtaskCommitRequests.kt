package skillbill.application.featuretask

import java.nio.file.Path

internal data class SubtaskCommitPreservationRequest(
  val repoRoot: Path,
  val decision: FeatureTaskRuntimeSubtaskCommitDecision,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val message: String,
  val allowUnchangedIndex: Boolean,
  val record: (String) -> Unit,
)

internal data class FeatureTaskRuntimeSubtaskFinaliseRequest(
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val durableCommitSha: String?,
  val sequenceNumber: Int,
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
  val metadata: FeatureTaskRuntimeCheckpointMetadata,
  val manifestCommitSha: String? = null,
)
