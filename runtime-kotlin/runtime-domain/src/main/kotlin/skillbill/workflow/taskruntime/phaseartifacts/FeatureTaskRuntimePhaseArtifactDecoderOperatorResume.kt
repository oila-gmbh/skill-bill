package skillbill.workflow.taskruntime.phaseartifacts

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.model.WorkflowStepStatus

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
