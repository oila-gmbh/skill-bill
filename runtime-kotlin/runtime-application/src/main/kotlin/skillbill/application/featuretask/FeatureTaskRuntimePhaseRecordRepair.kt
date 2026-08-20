package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

internal fun FeatureTaskRuntimePhaseRecord.asPendingForOperatorResume(): FeatureTaskRuntimePhaseRecord = copy(
  status = FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING,
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
