package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.model.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.application.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

/**
 * Read-only status service that projects durable per-phase records and the ledger into a typed
 * projection: phases ordered by the definition's `stepIds`, complete/pending/blocked counts, and
 * the first not-yet-complete phase as the current phase. No orchestration, no resume logic.
 */
@Inject
class FeatureTaskRuntimeStatusService(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
) {
  /**
   * Projects the read-only status. Returns null only when the workflow row is absent,
   * distinguishing "no such workflow" from "workflow exists but no phase has a record yet"
   * (an empty record map projects every phase as pending).
   */
  fun status(request: FeatureTaskRuntimeStatusRequest): FeatureTaskRuntimeStatusProjection? {
    val records = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride) ?: return null
    val decomposeTerminal = decomposeTerminalRecorder.loadDecomposeTerminal(request.workflowId, request.dbPathOverride)
    // Blocked-ness is derived primarily from the DURABLE per-phase records (a blocked phase
    // persists a terminal `blocked` record that survives ledger pruning); the append-only ledger
    // is supplementary detail only. A later non-blocked ledger entry from a resumed run can still
    // supersede a stale block, but a durable blocked record on a phase always reports blocked.
    val ledger = recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride).orEmpty()
    val durableBlockedPhaseIds = records.filterValues { it.status == STATUS_BLOCKED }.keys
    val blockedPhaseIds = durableBlockedPhaseIds + ledgerBlockedPhaseIds(ledger, durableBlockedPhaseIds)
    val phases = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.map { phaseId ->
      records[phaseId].toPhaseStatus(phaseId, blocked = phaseId in blockedPhaseIds)
    }
    val terminalDecomposeRecorded = decomposeTerminal != null
    return FeatureTaskRuntimeStatusProjection(
      workflowId = request.workflowId,
      featureSize = runInvariantsStore.resolve(request.workflowId, request.dbPathOverride)?.featureSize?.name,
      phases = phases,
      completeCount = phases.count { it.status == STATUS_COMPLETED },
      pendingCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status !in TERMINAL_PHASE_STATUSES },
      blockedCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status == STATUS_BLOCKED },
      currentPhaseId =
      if (terminalDecomposeRecorded) {
        null
      } else {
        phases.firstOrNull { it.status != STATUS_COMPLETED }?.phaseId
      },
      resolvedBranch = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch,
      decomposeTerminal = decomposeTerminal?.let {
        FeatureTaskRuntimeDecomposeTerminalStatus(
          reason = it.reason,
          parentSpecPath = it.parentSpecPath,
          decompositionManifestPath = it.decompositionManifestPath,
          subtaskSpecPaths = it.subtaskSpecPaths,
        )
      },
    )
  }

  // Supplementary ledger-derived blocked-ness: a phase is blocked when its newest ledger entry is
  // BLOCKED and no durable blocked record already covers it; a later entry from a resumed run
  // supersedes the block. Phases with a durable blocked record are excluded (already authoritative).
  private fun ledgerBlockedPhaseIds(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    durableBlockedPhaseIds: Set<String>,
  ): Set<String> = ledger
    .groupBy { it.phaseId }
    .filterKeys { it !in durableBlockedPhaseIds }
    .filterValues { entries ->
      entries.maxByOrNull { it.sequenceNumber }?.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
    }
    .keys

  private fun FeatureTaskRuntimePhaseRecord?.toPhaseStatus(
    phaseId: String,
    blocked: Boolean,
  ): FeatureTaskRuntimePhaseStatus = if (this == null) {
    FeatureTaskRuntimePhaseStatus(
      phaseId = phaseId,
      // A phase with no record can still be blocked when the block happened before any `running`
      // record was persisted (e.g. a missing-upstream block at handoff assembly).
      status = if (blocked) STATUS_BLOCKED else STATUS_PENDING,
      attemptCount = 0,
      resolvedAgentId = null,
      finished = false,
    )
  } else {
    FeatureTaskRuntimePhaseStatus(
      phaseId = phaseId,
      // The record is left at `running` on a block; the ledger reclassifies it as blocked, but a
      // completed record always wins over a stale block.
      status = if (blocked && status != STATUS_COMPLETED) STATUS_BLOCKED else status,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      finished = finishedAt != null,
    )
  }

  private companion object {
    const val STATUS_PENDING = "pending"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_BLOCKED = "blocked"
    val TERMINAL_PHASE_STATUSES = setOf(STATUS_COMPLETED, STATUS_BLOCKED)
  }
}
