package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import java.nio.file.Path

internal data class SubtaskCommitSpanFailureRequest(
  val repoRoot: Path,
  val baseSha: String,
  val headSha: String,
  val branch: String,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
)
