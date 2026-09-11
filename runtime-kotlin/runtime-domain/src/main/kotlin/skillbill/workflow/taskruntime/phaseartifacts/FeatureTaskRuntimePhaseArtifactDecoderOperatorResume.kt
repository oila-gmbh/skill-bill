package skillbill.workflow.taskruntime.phaseartifacts

import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

fun FeatureTaskRuntimePhaseRecord.asPendingForOperatorResume(): FeatureTaskRuntimePhaseRecord = copy(
  status = WorkflowStepStatus.PENDING,
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
