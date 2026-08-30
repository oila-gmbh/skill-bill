package skillbill.cli.featuretask

import me.tatarka.inject.annotations.Inject

@Inject
data class FeatureTaskRuntimeControlSubcommands(
  val status: FeatureTaskRuntimeStatusCommand,
  val resume: FeatureTaskRuntimeResumeCommand,
  val abandon: FeatureTaskRuntimeAbandonCommand,
  val retryBlocked: FeatureTaskRuntimeRetryBlockedCommand,
  val repairIdentity: FeatureTaskRuntimeRepairIdentityCommand,
  val lookup: FeatureTaskLookupCommand,
)

@Inject
data class FeatureTaskRejectedOutputSubcommands(
  val inspect: RejectedOutputInspectCliCommand,
  val cleanup: RejectedOutputCleanupCliCommand,
)
