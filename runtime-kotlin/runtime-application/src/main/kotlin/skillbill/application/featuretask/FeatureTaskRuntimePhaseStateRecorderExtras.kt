package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire
import java.time.Instant

internal fun FeatureTaskRuntimePhaseStateRecorder.implementationAttemptsFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeImplementationAttempt> {
  val raw = artifacts[FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY]
    ?: return emptyList()
  return featureTaskRuntimeImplementationAttemptsFromWire(raw)
}

internal fun FeatureTaskRuntimePhaseStateRecorder.implementationAttemptPatch(
  artifacts: Map<String, Any?>,
  request: FeatureTaskRuntimePhaseStateRequest,
  attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus,
): Map<String, Any?> {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(request.phaseId)) return emptyMap()
  val produced = request.normalizedOutput?.envelope
    ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]) }
  val value = produced?.get("value")?.toString()?.trim().orEmpty()
  if (produced == null || value.isBlank()) return emptyMap()
  val prompt = produced["prompt"]?.toString()?.trim()?.takeIf(String::isNotBlank)
  val existing = implementationAttemptsFrom(artifacts)
  val appended = featureTaskRuntimeAppendImplementationAttempt(
    existing = existing,
    entry = FeatureTaskRuntimeImplementationAttempt(
      sequenceNumber = (existing.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      phaseId = request.phaseId,
      attemptNumber = request.attemptCount,
      agentId = request.resolvedAgentId,
      status = attemptStatus,
      recordedAt = Instant.now().toString(),
      value = value,
      loopId = request.loopId,
      edgeIteration = request.edgeIteration,
      failureDisposition = request.failureDisposition,
      prompt = prompt,
    ),
  )
  val wire = featureTaskRuntimeImplementationAttemptRecordToWire(appended)
  implementationAttemptValidator.validateImplementationAttemptRecord(
    wire,
    FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY,
  )
  return mapOf(FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY to wire)
}

internal fun FeatureTaskRuntimePhaseStateRecorder.findingVerificationCheckpointPatch(
  request: FeatureTaskRuntimePhaseStateRequest,
): Map<String, Any?> {
  if (request.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return emptyMap()
  if (request.finished && request.status == "completed") {
    val dispositions = request.normalizedOutput?.envelope
      ?.let(FeatureTaskRuntimeOutputVerification::dispositionsFrom)
      .orEmpty()
    return buildMap {
      put(FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY, null)
      put(FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY, null)
      if (dispositions.isNotEmpty()) {
        put(
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY,
          dispositions.map { it.toArtifactMap() },
        )
      }
    }
  }
  val checkpoint = request.findingVerificationCheckpoint?.takeIf { it.isNotEmpty() } ?: return emptyMap()
  return mapOf(
    FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY to checkpoint.map { it.toArtifactMap() },
  )
}

internal fun FeatureTaskRuntimePhaseStateRecorder.phaseRecordFor(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord?,
  now: String,
): FeatureTaskRuntimePhaseRecord = featureTaskRuntimePhaseRecordFor(request, previous, now)
