package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import java.nio.file.Path

internal data class RecoveredSubtaskParentRequest(
  val repoRoot: Path,
  val headSha: String,
  val branch: String,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val sequenceNumber: Int,
  val prior: FeatureTaskRuntimeCheckpointIdentity?,
)
