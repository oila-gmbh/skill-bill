package skillbill.ports.persistence

import skillbill.workflow.taskruntime.ConvergenceRecord
import skillbill.workflow.taskruntime.ReplayResult
import skillbill.workflow.taskruntime.UnresolvedConvergence

interface ConvergenceStateRepository {
  fun append(record: ConvergenceRecord): ReplayResult
  fun history(workflowId: String): List<ConvergenceRecord>
  fun current(workflowId: String): Map<String, ConvergenceRecord>
  fun unresolved(workflowId: String): UnresolvedConvergence
  fun reconcileLegacy(workflowId: String, sourceDigest: String, records: List<ConvergenceRecord>): LegacyReconciliation
}

sealed interface LegacyReconciliation {
  data class Imported(val recordCount: Int) : LegacyReconciliation
  data object AlreadyImported : LegacyReconciliation
  data class Quarantined(val reasonCode: String) : LegacyReconciliation
}

class ConvergenceReplayConflictException(recordId: String) :
  IllegalStateException("Conflicting convergence evidence already exists for '$recordId'.")

object UnavailableConvergenceStateRepository : ConvergenceStateRepository {
  private fun unavailable(): Nothing = error("Convergence-state persistence is not available.")
  override fun append(record: ConvergenceRecord): ReplayResult = unavailable()
  override fun history(workflowId: String): List<ConvergenceRecord> = unavailable()
  override fun current(workflowId: String): Map<String, ConvergenceRecord> = unavailable()
  override fun unresolved(workflowId: String): UnresolvedConvergence = unavailable()
  override fun reconcileLegacy(
    workflowId: String,
    sourceDigest: String,
    records: List<ConvergenceRecord>,
  ): LegacyReconciliation = unavailable()
}
