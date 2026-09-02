package skillbill.application.featuretask.model

data class FeatureTaskRuntimeCommitPushHandoff(
  val outcomeMessage: String,
  val changedPaths: List<String>,
)

sealed interface FeatureTaskRuntimeCommitPushHandoffResult

data class FeatureTaskRuntimeCommitPushHandoffValid(
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
) : FeatureTaskRuntimeCommitPushHandoffResult

data class FeatureTaskRuntimeCommitPushHandoffInvalid(
  val reason: String,
) : FeatureTaskRuntimeCommitPushHandoffResult

sealed interface FeatureTaskRuntimeSubtaskFinalisationResult

data class FeatureTaskRuntimeSubtaskFinalised(
  val commitSha: String,
  val stagedPaths: List<String>,
  val excludedSpecPaths: List<String>,
  val forcedWithLease: Boolean,
) : FeatureTaskRuntimeSubtaskFinalisationResult

data class FeatureTaskRuntimeSubtaskFinalisationBlocked(
  val reason: String,
) : FeatureTaskRuntimeSubtaskFinalisationResult
