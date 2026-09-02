package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import java.nio.file.Path

internal data class SubtaskCommitPreservationRequest(
  val repoRoot: Path,
  val decision: FeatureTaskRuntimeSubtaskCommitDecision,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val message: String,
  val allowUnchangedIndex: Boolean,
  val record: (String) -> Unit,
)
