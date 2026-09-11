package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal data class SubtaskMigrationRollbackRequest(
  val headSha: String,
  val stagedPaths: List<String>,
  val snapshot: String,
  val originalIdentities: List<FeatureTaskRuntimeCheckpointIdentity>? = null,
  val replacementRefName: String? = null,
  val replacementRefTarget: String? = null,
)
