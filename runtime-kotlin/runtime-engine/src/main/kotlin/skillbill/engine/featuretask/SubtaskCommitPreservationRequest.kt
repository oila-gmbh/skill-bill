package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import java.nio.file.Path

internal data class SubtaskCommitPreservationRequest(
  val repoRoot: Path,
  val decision: FeatureTaskRuntimeSubtaskCommitDecision,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val message: String,
  val allowUnchangedIndex: Boolean,
  val ownedPaths: List<String>,
  val record: (String) -> Unit,
)
