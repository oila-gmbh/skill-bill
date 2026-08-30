package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.db.UnitOfWork
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement

internal fun FeatureTaskRuntimePhaseBriefingRecorder.nextDeliveredProjectionRecord(
  workflowId: String,
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  deliveredHistory: Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>,
): FeatureTaskRuntimeDeliveredProjectionRecord {
  val existingDelivered = deliveredHistory.values
    .filter { it.consumerPhaseId == briefing.phaseId }
    .maxByOrNull(FeatureTaskRuntimeDeliveredProjectionRecord::iteration)
  return FeatureTaskRuntimeDeliveredProjectionRecord(
    workflowId = workflowId,
    consumerPhaseId = briefing.phaseId,
    iteration = (existingDelivered?.iteration ?: 0) + 1,
    envelope = briefing.handoffEnvelope,
  )
}

internal fun FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionMeasurements(
  unitOfWork: UnitOfWork,
  workflowId: String,
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  delivered: FeatureTaskRuntimeDeliveredProjectionRecord,
  artifacts: Map<String, Any?>,
) {
  val privatePhaseRecords = phaseRecordsFrom(artifacts)
  briefing.handoffEnvelope.projections.forEach { projection ->
    val deliveredProjectionUtf8Bytes = projection.utf8ByteSize
    val privateEvidenceUtf8Bytes =
      privatePhaseRecords[projection.producerIteration.phaseId]
        ?.outputArtifact
        ?.toByteArray(Charsets.UTF_8)
        ?.size
        ?: 0
    val measurement = FeatureTaskRuntimeProjectionMeasurement(
      workflowId = workflowId,
      consumerPhaseId = briefing.phaseId,
      projectionContractId = projection.projectionContractId,
      producerIteration = projection.producerIteration,
      repositoryCheckpointFingerprint = delivered.repositoryCheckpointFingerprint,
      projectedUtf8Bytes = projection.utf8ByteSize,
      projectedCollectionItems = projection.itemCount,
      estimatedTokens = (projection.utf8ByteSize + 3) / 4,
      privateEvidenceUtf8Bytes = privateEvidenceUtf8Bytes,
      deliveredProjectionUtf8Bytes = deliveredProjectionUtf8Bytes,
    )
    handoffFoundationValidator.validateMeasurement(
      measurement.toTelemetryMap(),
      "projection-delivery:${briefing.phaseId}:${projection.projectionName}",
    )
    unitOfWork.lifecycleTelemetry.featureTaskRuntimeProjectionMeasurement(measurement)
  }
}

internal fun FeatureTaskRuntimePhaseBriefingRecorder.recordSharedEvidenceMeasurement(
  unitOfWork: UnitOfWork,
  measurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
) {
  if (measurement == null) return
  runCatching {
    unitOfWork.lifecycleTelemetry.featureTaskRuntimeSharedEvidence(measurement)
  }
}

internal fun deliveredProjectionKey(delivered: FeatureTaskRuntimeDeliveredProjectionRecord): String = listOf(
  delivered.workflowId,
  delivered.consumerPhaseId,
  delivered.iteration.toString(),
  delivered.sourceProducerIterations
    .sortedWith(
      compareBy(
        FeatureTaskRuntimeProducerIteration::phaseId,
        FeatureTaskRuntimeProducerIteration::iteration,
      ),
    )
    .joinToString(separator = ",") { "${it.phaseId}#${it.iteration}" },
  delivered.repositoryCheckpointFingerprint,
).joinToString(separator = "|")

internal fun FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionRejectionMeasurement(
  unitOfWork: UnitOfWork,
  rejection: FeatureTaskRuntimeProjectionRejection,
): Boolean {
  if (WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, rejection.workflowId) == null) {
    return false
  }
  val measurement = FeatureTaskRuntimeProjectionMeasurement(
    workflowId = rejection.workflowId,
    consumerPhaseId = rejection.consumerPhaseId,
    projectionContractId = rejection.projectionContractId.ifBlank { "unknown" },
    producerIteration = rejection.producerIteration,
    repositoryCheckpointFingerprint = rejection.repositoryCheckpointFingerprint
      ?: "not_resolved:${rejection.consumerPhaseId}",
    projectedUtf8Bytes = 0,
    projectedCollectionItems = 0,
    estimatedTokens = 0,
    privateEvidenceUtf8Bytes = 0,
    deliveredProjectionUtf8Bytes = 0,
    failureClassification = rejection.failureClassification,
  )
  handoffFoundationValidator.validateMeasurement(
    measurement.toTelemetryMap(),
    "projection-rejection:${rejection.consumerPhaseId}:${rejection.sourceLabel}",
  )
  unitOfWork.lifecycleTelemetry.featureTaskRuntimeProjectionMeasurement(measurement)
  return true
}

internal fun FeatureTaskRuntimeHandoffEnvelopeValidator.validateEnvelopeWire(envelope: Map<String, Any?>) =
  validateEnvelope(envelope, workflowId = null)

internal fun FeatureTaskRuntimeHandoffFoundationValidator.validatePersistenceWire(record: Map<String, Any?>) =
  validatePersistenceRecord(record, "delivered-projection")
