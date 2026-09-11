package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import java.nio.file.Path

internal data class ReviewIdentityAuthorityRequest(
  val repoRoot: Path,
  val reviewedTargetSha: String,
  val currentHeadSha: String,
  val reviewedTreeSha: String,
  val currentTreeSha: String,
  val checkpoint: FeatureTaskRuntimeCheckpointIdentity?,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val onReadFailure: ((String) -> Unit)? = null,
)
