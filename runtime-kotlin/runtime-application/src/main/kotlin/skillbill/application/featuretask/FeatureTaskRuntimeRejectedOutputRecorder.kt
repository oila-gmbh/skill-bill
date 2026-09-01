package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeProducerOutputRead
import skillbill.application.featuretask.model.ProducerOutputQueryArgs
import skillbill.application.featuretask.model.RejectedOutputDiagnosticDegradeRequest
import skillbill.application.featuretask.model.RejectedOutputDiagnosticPersistRequest
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.error.InvalidProducerOutputEvidenceSchemaError
import skillbill.error.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import skillbill.ports.diagnostics.model.evidenceKey
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendDiagnosticSignal
import skillbill.workflow.taskruntime.model.featureTaskRuntimeDiagnosticSignalsFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRejectionCapOf
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRejectionViolationClassOf
import java.time.Clock

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

class FeatureTaskRuntimeRejectedOutputRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
  private val clock: Clock,
) : FeatureTaskRuntimePhaseRejectedApi {
  private sealed class DiagnosticWriteOutcome<out T> {
    class Written<T>(val value: T) : DiagnosticWriteOutcome<T>()
    class Degraded(
      val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
    ) : DiagnosticWriteOutcome<Nothing>()
  }

  override fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    dbOverride: String?,
    producerGeneration: Int,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val evidence = ProducerOutputEvidence(
      workflowId = request.workflowId,
      phaseId = request.phaseId,
      attempt = request.attempt,
      agentId = request.agentId,
      model = request.model,
      recordedAt = clock.instant(),
      byteSize = request.observedByteSize,
      sha256 = request.observedSha256,
      payload = request.rawResponse.takeUnless { request.truncated },
      generation = producerGeneration,
      repairTurn = request.repairTurn,
    )
    return when (
      val outcome = degradeDiagnosticFailure(
        RejectedOutputDiagnosticDegradeRequest(
          workflowId = request.workflowId,
          operation = "record-rejected-output",
          conflictingKey = evidence.evidenceKey(),
          phaseId = request.phaseId,
          attempt = request.attempt,
          repairTurn = request.repairTurn,
          generation = producerGeneration,
          dbOverride = dbOverride,
        ),
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
    runCatching {
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
    }
  }

  override fun retainProducerOutput(evidence: ProducerOutputEvidence, dbOverride: String?) {
    degradeDiagnosticFailure(
      RejectedOutputDiagnosticDegradeRequest(
        workflowId = evidence.workflowId,
        operation = "retain-producer-output",
        conflictingKey = evidence.evidenceKey(),
        phaseId = evidence.phaseId,
        attempt = evidence.attempt,
        repairTurn = evidence.repairTurn,
        generation = evidence.generation,
        dbOverride = dbOverride,
      ),
    ) {
      database.transaction(dbOverride) { unitOfWork ->
        diagnosticService(unitOfWork).retainProducerOutput(evidence)
      }
    }
  }

  override fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead {
    val workflowId = args.workflowId
    val phaseId = args.phaseId
    val attempt = args.attempt
    val agentId = args.agentId
    val dbOverride = args.dbOverride
    val generation = args.generation
    val conflictingKey = "$workflowId:$phaseId:$generation:$attempt:*:$agentId"
    fun unreadable(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): FeatureTaskRuntimeProducerOutputRead {
      persistDegradedDiagnostic(
        RejectedOutputDiagnosticPersistRequest(
          workflowId = workflowId,
          operation = "read-producer-output",
          conflictingKey = conflictingKey,
          phaseId = phaseId,
          attempt = attempt,
          repairTurn = null,
          generation = generation,
          dbOverride = dbOverride,
          failureClass = failureClass,
        ),
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

  private fun <T> degradeDiagnosticFailure(
    request: RejectedOutputDiagnosticDegradeRequest,
    block: () -> T,
  ): DiagnosticWriteOutcome<T> {
    fun degrade(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): DiagnosticWriteOutcome<T> {
      persistDegradedDiagnostic(
        RejectedOutputDiagnosticPersistRequest(
          workflowId = request.workflowId,
          operation = request.operation,
          conflictingKey = request.conflictingKey,
          phaseId = request.phaseId,
          attempt = request.attempt,
          repairTurn = request.repairTurn,
          generation = request.generation,
          dbOverride = request.dbOverride,
          failureClass = failureClass,
        ),
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

  private fun persistDegradedDiagnostic(request: RejectedOutputDiagnosticPersistRequest) {
    val signal = FeatureTaskRuntimeDiagnosticSignal(
      operation = request.operation,
      failureClass = request.failureClass,
      conflictingKey = request.conflictingKey,
      phaseId = request.phaseId,
      attempt = request.attempt.coerceAtLeast(0),
      repairTurn = request.repairTurn?.coerceAtLeast(0),
      generation = request.generation.coerceAtLeast(0),
      recordedAt = clock.instant().toString(),
    )
    persistDiagnosticSignal(request.workflowId, signal, request.dbOverride)
    recordDegradationMeasurement(request.workflowId, signal, request.dbOverride)
  }
  private fun recordDegradationMeasurement(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
    dbOverride: String?,
  ) {
    runCatching {
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
    }
  }
  private fun persistDiagnosticSignal(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
    dbOverride: String?,
  ) {
    runCatching {
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
    }
  }
  override fun loadDiagnosticSignals(
    workflowId: String,
    dbOverride: String?,
  ): List<FeatureTaskRuntimeDiagnosticSignal> = database.read(dbOverride) { unitOfWork ->
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
      clock = clock,
    )
  }
}
