package skillbill.engine.featuretask

import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDINGimport skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

fun FeatureTaskRuntimePhaseRecord.asPendingForOperatorResume(): FeatureTaskRuntimePhaseRecord = copy(
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
