package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

internal fun FeatureTaskRuntimePhaseRecord.asPendingForOperatorResume(): FeatureTaskRuntimePhaseRecord = copy(
  status = "pending",
  finishedAt = null,
  durationMillis = null,
  outputArtifact = null,
  rejectedOutput = null,
  blockedReason = null,
  failureDisposition = null,
  fileManifestBefore = emptyList(),
  fileManifestAfter = emptyList(),
  fileManifestIntroduced = emptyList(),
)
