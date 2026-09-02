package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

internal data class FeatureTaskRuntimeProjectionRejection(
  val workflowId: String,
  val consumerPhaseId: String,
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpointFingerprint: String?,
  val failureClassification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
)

internal class FeatureTaskRuntimePhaseBriefingRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  internal val handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  internal val handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
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
