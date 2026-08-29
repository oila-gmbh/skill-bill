package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidProducerOutputEvidenceSchemaError
import skillbill.error.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import skillbill.ports.diagnostics.model.evidenceKey
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendDiagnosticSignal
import skillbill.workflow.taskruntime.model.featureTaskRuntimeDiagnosticSignalsFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRejectionCapOf
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRejectionViolationClassOf
import java.time.Instant

private fun RejectedOutputDiagnosticError.degradableFailureClass(): FeatureTaskRuntimeDiagnosticFailureClass? =
  when (this) {
    is RejectedOutputDiagnosticError.Conflict -> FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT
    is RejectedOutputDiagnosticError.Permission -> FeatureTaskRuntimeDiagnosticFailureClass.PERMISSION
    is RejectedOutputDiagnosticError.Corrupt -> FeatureTaskRuntimeDiagnosticFailureClass.CORRUPT
    is RejectedOutputDiagnosticError.Persistence,
    is RejectedOutputDiagnosticError.Retrieval,
    is RejectedOutputDiagnosticError.Expired,
    is RejectedOutputDiagnosticError.Oversized,
    is RejectedOutputDiagnosticError.Absent,
    -> FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE
    is RejectedOutputDiagnosticError.InvalidRequest,
    is RejectedOutputDiagnosticError.InvalidConfiguration,
    -> null
  }

internal sealed class FeatureTaskRuntimeProducerOutputRead {
  internal data class Found(val evidence: ProducerOutputEvidence) : FeatureTaskRuntimeProducerOutputRead()
  internal data object Absent : FeatureTaskRuntimeProducerOutputRead()
  internal data class Unreadable(
    val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) : FeatureTaskRuntimeProducerOutputRead()
}

internal class FeatureTaskRuntimeRejectedOutputRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
) {
  private sealed class DiagnosticWriteOutcome<out T> {
    class Written<T>(val value: T) : DiagnosticWriteOutcome<T>()
    class Degraded(
      val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
    ) : DiagnosticWriteOutcome<Nothing>()
  }

  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    dbOverride: String? = null,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val evidence = ProducerOutputEvidence(
      workflowId = request.workflowId,
      phaseId = request.phaseId,
      attempt = request.attempt,
      agentId = request.agentId,
      model = request.model,
      recordedAt = Instant.now(),
      byteSize = request.observedByteSize,
      sha256 = request.observedSha256,
      payload = request.rawResponse.takeUnless { request.truncated },
      generation = producerGeneration,
      repairTurn = request.repairTurn,
    )
    return when (
      val outcome = degradeDiagnosticFailure(
        workflowId = request.workflowId,
        operation = "record-rejected-output",
        conflictingKey = evidence.evidenceKey(),
        phaseId = request.phaseId,
        attempt = request.attempt,
        repairTurn = request.repairTurn,
        generation = producerGeneration,
        dbOverride = dbOverride,
      ) {
        database.transaction(dbOverride) { unitOfWork ->
          val service = diagnosticService(unitOfWork)
          service.retainProducerOutput(evidence)
          service.record(request)
          recordRejectionMeasurement(unitOfWork, request)
        }
      }
    ) {
      is DiagnosticWriteOutcome.Written<*> -> FeatureTaskRuntimeRejectedOutputWrite.Written(
        RejectedOutputDiagnosticService.stableIdentity(
          request.workflowId,
          request.phaseId,
          request.attempt,
          request.repairTurn,
        ),
      )
      is DiagnosticWriteOutcome.Degraded -> FeatureTaskRuntimeRejectedOutputWrite.Degraded(outcome.failureClass)
    }
  }
  private fun recordRejectionMeasurement(unitOfWork: UnitOfWork, request: RejectedOutputDiagnosticRequest) {
    try {
      unitOfWork.lifecycleTelemetry.featureTaskRuntimeRejection(
        FeatureTaskRuntimeRejectionMeasurement(
          workflowId = request.workflowId,
          phaseId = request.phaseId,
          iteration = request.attempt.coerceAtLeast(1),
          rule = request.rule,
          pointerPath = request.path.ifBlank { "/" },
          violationClass = featureTaskRuntimeRejectionViolationClassOf(request.reason),
          declaredCap = featureTaskRuntimeRejectionCapOf(request.reason),
        ),
      )
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    }
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence, dbOverride: String? = null) {
    degradeDiagnosticFailure(
      workflowId = evidence.workflowId,
      operation = "retain-producer-output",
      conflictingKey = evidence.evidenceKey(),
      phaseId = evidence.phaseId,
      attempt = evidence.attempt,
      repairTurn = evidence.repairTurn,
      generation = evidence.generation,
      dbOverride = dbOverride,
    ) {
      database.transaction(dbOverride) { unitOfWork ->
        diagnosticService(unitOfWork).retainProducerOutput(evidence)
      }
    }
  }

  internal fun producerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    dbOverride: String? = null,
    generation: Int = 0,
  ): FeatureTaskRuntimeProducerOutputRead {
    val conflictingKey = "$workflowId:$phaseId:$generation:$attempt:*:$agentId"
    fun unreadable(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): FeatureTaskRuntimeProducerOutputRead {
      persistDegradedDiagnostic(
        workflowId = workflowId,
        operation = "read-producer-output",
        conflictingKey = conflictingKey,
        phaseId = phaseId,
        attempt = attempt,
        repairTurn = null,
        generation = generation,
        dbOverride = dbOverride,
        failureClass = failureClass,
      )
      return FeatureTaskRuntimeProducerOutputRead.Unreadable(failureClass)
    }
    return try {
      val evidence = database.read(dbOverride) { unitOfWork ->
        val repository = unitOfWork.rejectedOutputDiagnostics
          ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable")
        repository.readProducerOutput(workflowId, phaseId, attempt, agentId, generation)
      }
      if (evidence == null) {
        FeatureTaskRuntimeProducerOutputRead.Absent
      } else {
        FeatureTaskRuntimeProducerOutputRead.Found(evidence)
      }
    } catch (error: RejectedOutputDiagnosticError) {
      unreadable(error.degradableFailureClass() ?: throw error)
    } catch (_: InvalidProducerOutputEvidenceSchemaError) {
      unreadable(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (_: InvalidRejectedOutputDiagnosticSchemaError) {
      unreadable(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    }
  }
  @Suppress("LongParameterList")
  private fun <T> degradeDiagnosticFailure(
    workflowId: String,
    operation: String,
    conflictingKey: String,
    phaseId: String,
    attempt: Int,
    repairTurn: Int?,
    generation: Int,
    dbOverride: String?,
    block: () -> T,
  ): DiagnosticWriteOutcome<T> {
    fun degrade(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): DiagnosticWriteOutcome<T> {
      persistDegradedDiagnostic(
        workflowId = workflowId,
        operation = operation,
        conflictingKey = conflictingKey,
        phaseId = phaseId,
        attempt = attempt,
        repairTurn = repairTurn,
        generation = generation,
        dbOverride = dbOverride,
        failureClass = failureClass,
      )
      return DiagnosticWriteOutcome.Degraded(failureClass)
    }
    return try {
      DiagnosticWriteOutcome.Written(block())
    } catch (error: RejectedOutputDiagnosticError) {
      degrade(error.degradableFailureClass() ?: throw error)
    } catch (_: InvalidProducerOutputEvidenceSchemaError) {
      degrade(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (_: InvalidRejectedOutputDiagnosticSchemaError) {
      degrade(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    }
  }
  @Suppress("LongParameterList")
  private fun persistDegradedDiagnostic(
    workflowId: String,
    operation: String,
    conflictingKey: String,
    phaseId: String,
    attempt: Int,
    repairTurn: Int?,
    generation: Int,
    dbOverride: String?,
    failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) {
    val signal = FeatureTaskRuntimeDiagnosticSignal(
      operation = operation,
      failureClass = failureClass,
      conflictingKey = conflictingKey,
      phaseId = phaseId,
      attempt = attempt.coerceAtLeast(0),
      repairTurn = repairTurn?.coerceAtLeast(0),
      generation = generation.coerceAtLeast(0),
      recordedAt = Instant.now().toString(),
    )
    persistDiagnosticSignal(workflowId, signal, dbOverride)
    recordDegradationMeasurement(workflowId, signal, dbOverride)
  }
  private fun recordDegradationMeasurement(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
    dbOverride: String?,
  ) {
    try {
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.featureTaskRuntimeDiagnosticDegradation(
          FeatureTaskRuntimeDiagnosticDegradationMeasurement(
            workflowId = workflowId,
            phaseId = signal.phaseId,
            attempt = signal.attempt,
            repairTurn = signal.repairTurn,
            generation = signal.generation,
            operation = signal.operation,
            failureClass = signal.failureClass,
            conflictingKey = signal.conflictingKey,
          ),
        )
      }
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    }
  }
  private fun persistDiagnosticSignal(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
    dbOverride: String?,
  ) {
    try {
      database.transaction(dbOverride) { unitOfWork ->
        val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
          ?: return@transaction
        val existing = featureTaskRuntimeDiagnosticSignalsFromWire(
          decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY],
        )
        workflowPersistence.persistPatch(
          unitOfWork.workflowStates,
          record,
          mapOf(
            FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY to
              featureTaskRuntimeAppendDiagnosticSignal(existing, signal).map { it.toArtifactMap() },
          ),
        )
      }
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    }
  }
  fun loadDiagnosticSignals(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimeDiagnosticSignal> =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read emptyList()
      featureTaskRuntimeDiagnosticSignalsFromWire(
        decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY],
      )
    }

  private fun diagnosticService(unitOfWork: UnitOfWork): RejectedOutputDiagnosticService {
    val repository = unitOfWork.rejectedOutputDiagnostics
      ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable")
    val permissions = unitOfWork.rejectedOutputDiagnosticPermissions
      ?: throw RejectedOutputDiagnosticError.Permission("permissions-unavailable")
    return RejectedOutputDiagnosticService(
      repository = repository,
      permissions = permissions,
      metadataValidator = rejectedOutputDiagnosticMetadataValidator,
      producerEvidenceValidator = producerOutputEvidenceValidator,
    )
  }
}
