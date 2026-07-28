package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LegacyReconciliation
import skillbill.workflow.taskruntime.ConvergenceRecord
import skillbill.workflow.taskruntime.ReplayResult
import skillbill.workflow.taskruntime.UnresolvedConvergence

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
}
