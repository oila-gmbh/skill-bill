package skillbill.engine.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal data class NormalizedSubtaskCommitRequest(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val precedingPhaseId: String,
  val branch: String,
  val blockedReason: (String, String) -> String,
  val ownedPaths: List<String>,
  val identities: List<FeatureTaskRuntimeCheckpointIdentity>,
  val active: List<FeatureTaskRuntimeCheckpointIdentity>,
  val headSha: String,
  val message: String,
)
