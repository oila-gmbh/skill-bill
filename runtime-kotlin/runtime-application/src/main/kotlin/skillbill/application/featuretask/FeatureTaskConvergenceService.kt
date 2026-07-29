package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.LegacyReconciliation
import skillbill.workflow.taskruntime.ConvergenceStateCodec
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.ReplayResult
import skillbill.workflow.taskruntime.model.UnresolvedConvergence

@Inject
class FeatureTaskConvergenceService(private val database: DatabaseSessionFactory) {
  fun recordAndAdvance(
    record: ConvergenceRecord,
    dbOverride: String? = null,
    isAlreadyAdvanced: (skillbill.ports.persistence.WorkflowStateRepository) -> Boolean,
    advance: (skillbill.ports.persistence.WorkflowStateRepository) -> Unit,
  ): ReplayResult = database.transaction(dbOverride) { unitOfWork ->
    when (val replay = unitOfWork.convergenceStates.append(record)) {
      is ReplayResult.Conflict -> throw ConvergenceReplayConflictException(record.recordId)
      is ReplayResult.Appended -> {
        advance(unitOfWork.workflowStates)
        replay
      }
      is ReplayResult.Identical -> {
        if (!isAlreadyAdvanced(unitOfWork.workflowStates)) {
          throw ConvergenceReplayConflictException(record.recordId)
        }
        replay
      }
    }
  }

  fun unresolved(workflowId: String, dbOverride: String? = null): UnresolvedConvergence =
    database.read(dbOverride) { it.convergenceStates.unresolved(workflowId) }

  fun reconcileLegacy(
    workflowId: String,
    sourceDigest: String,
    encodedSource: String,
    dbOverride: String? = null,
  ): LegacyReconciliation = database.transaction(dbOverride) {
    it.convergenceStates.reconcileLegacy(workflowId, sourceDigest, encodedSource)
  }

  fun reconcileLegacyArtifacts(
    workflowId: String,
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    dbOverride: String? = null,
  ): LegacyReconciliation {
    val records = mapLegacyArtifactRecords(workflowId, phaseRecords)
    val encoded = ConvergenceStateCodec.encodeLegacySource(records)
    return reconcileLegacy(
      workflowId = workflowId,
      sourceDigest = ConvergenceIdentities.digest(encoded),
      encodedSource = encoded,
      dbOverride = dbOverride,
    )
  }
}

internal fun mapLegacyArtifactRecords(
  workflowId: String,
  phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
): List<ConvergenceRecord> = phaseRecords.values
  .filter { it.finishedAt != null && it.phaseId in setOf("implement", "audit", "review") }
  .map { phase ->
    val generation = phase.edgeIteration ?: phase.attemptCount
    val stableKey = "${phase.phaseId}:${phase.attemptCount}:${phase.loopId.orEmpty()}:${phase.edgeIteration ?: 0}"
    val logicalId = ConvergenceIdentities.logical(workflowId, ConvergenceRecordKind.LEGACY_IMPORT, stableKey)
    ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        workflowId,
        ConvergenceRecordKind.LEGACY_IMPORT,
        logicalId,
        generation,
      ),
      logicalId = logicalId,
      kind = ConvergenceRecordKind.LEGACY_IMPORT,
      provenance = ConvergenceProvenance(workflowId, generation, phase.phaseId),
      evidenceDigest = ConvergenceIdentities.digest(
        listOf(workflowId, stableKey, phase.status, phase.outputArtifact.orEmpty()).joinToString("|"),
      ),
      createdAt = requireNotNull(phase.finishedAt),
      status = ConvergenceStatus.COMPLETED,
      summary = "legacy ${phase.phaseId} phase ${phase.attemptCount} reconciled",
      evidenceRef = null,
    )
  }
