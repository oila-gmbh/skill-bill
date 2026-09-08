package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimeProjectionRejection
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

class FeatureTaskRuntimePhaseBriefingRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  val handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  val handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
) : FeatureTaskRuntimePhaseBriefingApi {
  override fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    dbOverride: String?,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    handoffEnvelopeValidator.validateEnvelope(briefing.handoffEnvelope.toEnvelopeMap(), workflowId)
    val artifacts = decodeArtifacts(record.artifactsJson)
    val updatedBriefings = LinkedHashMap(phaseBriefingsFrom(artifacts, handoffEnvelopeValidator::validateEnvelopeWire))
      .apply { put(briefing.phaseId, briefing) }
    val deliveredHistory = deliveredProjectionHistoryFrom(
      artifacts,
      handoffEnvelopeValidator::validateEnvelopeWire,
      handoffFoundationValidator::validatePersistenceWire,
    )
    val delivered = nextDeliveredProjectionRecord(workflowId, briefing, deliveredHistory)
    handoffFoundationValidator.validatePersistenceRecord(
      delivered.toArtifactMap(),
      "delivered-projection:${briefing.phaseId}",
    )
    recordProjectionMeasurements(unitOfWork, workflowId, briefing, delivered, artifacts)
    recordSharedEvidenceMeasurement(unitOfWork, sharedEvidenceMeasurement)
    val updatedDelivered = LinkedHashMap(deliveredHistory)
      .apply {
        entries.removeIf { (_, value) -> value.consumerPhaseId == briefing.phaseId }
        put(deliveredProjectionKey(delivered), delivered)
      }
    val patch = mapOf(
      FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY to
        updatedBriefings.mapValues { (_, value) -> value.toArtifactMap() },
      FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY to
        updatedDelivered.mapValues { (_, value) -> value.toArtifactMap() },
    )
    workflowPersistence.persistPatch(unitOfWork.workflowStates, record, patch)
    true
  }

  override fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
    dbOverride: String?,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    recordProjectionRejectionMeasurement(
      unitOfWork,
      FeatureTaskRuntimeProjectionRejection(
        workflowId = workflowId,
        consumerPhaseId = consumerPhaseId,
        projectionContractId = error.projectionContractId.ifBlank { "unknown" },
        producerIteration = FeatureTaskRuntimeProducerIteration(consumerPhaseId, 1),
        repositoryCheckpointFingerprint = repositoryCheckpointFingerprint,
        failureClassification = error.failureKind.toMeasurementFailureClassification(),
        sourceLabel = error.projectionName,
      ),
    )
  }

  override fun recordProjectionRejection(
    rejection: FeatureTaskRuntimeProjectionRejection,
    dbOverride: String?,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    recordProjectionRejectionMeasurement(unitOfWork, rejection)
  }

  override fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) {
    declarations.forEach { declaration ->
      handoffFoundationValidator.validateDeclaration(
        declaration.toArtifactMap(),
        "phase-handoff-declaration:${declaration.consumerPhaseId}:${declaration.projectionName}",
      )
    }
  }
  override fun loadPhaseBriefings(
    workflowId: String,
    dbOverride: String?,
  ): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    phaseBriefingsFrom(decodeArtifacts(record.artifactsJson)) { envelope ->
      handoffEnvelopeValidator.validateEnvelope(envelope, workflowId)
    }
  }
  override fun loadDeliveredProjections(
    workflowId: String,
    dbOverride: String?,
  ): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    deliveredProjectionsFrom(
      decodeArtifacts(record.artifactsJson),
      validateEnvelope = { envelope -> handoffEnvelopeValidator.validateEnvelope(envelope, workflowId) },
      validatePersistenceRecord = { persistence ->
        handoffFoundationValidator.validatePersistenceRecord(persistence, "delivered-projection:$workflowId")
      },
    )
  }
}

fun FeatureTaskRuntimePhaseBriefingRecorder.nextDeliveredProjectionRecord(
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

fun FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionMeasurements(
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

fun FeatureTaskRuntimePhaseBriefingRecorder.recordSharedEvidenceMeasurement(
  unitOfWork: UnitOfWork,
  measurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
) {
  if (measurement == null) return
  runCatching {
    unitOfWork.lifecycleTelemetry.featureTaskRuntimeSharedEvidence(measurement)
  }
}

fun deliveredProjectionKey(delivered: FeatureTaskRuntimeDeliveredProjectionRecord): String = listOf(
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

fun FeatureTaskRuntimeHandoffEnvelopeValidator.validateEnvelopeWire(envelope: Map<String, Any?>) =
  validateEnvelope(envelope, workflowId = null)

fun FeatureTaskRuntimeHandoffFoundationValidator.validatePersistenceWire(record: Map<String, Any?>) =
  validatePersistenceRecord(record, "delivered-projection")
