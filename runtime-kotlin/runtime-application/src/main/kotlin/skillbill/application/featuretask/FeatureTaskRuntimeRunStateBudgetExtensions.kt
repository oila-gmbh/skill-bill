package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry

fun FeatureTaskRuntimeRunState.fixLoopIterationFor(phaseId: String, absoluteIteration: Int): Int =
  absoluteIteration - (fixLoopBudgetBaseByPhase[phaseId] ?: 0)

fun FeatureTaskRuntimeRunState.restartAttemptBudget(phaseId: String) {
  fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
}

internal fun FeatureTaskRuntimeRunState.trailingNonOutputAttempts(
  phaseId: String,
  isProcessFailure: (String) -> Boolean,
): List<FeatureTaskRuntimeNonOutputAttempt> {
  val base = fixLoopBudgetBaseByPhase[phaseId] ?: 0
  return initialLedger
    .filter { entry ->
      entry.phaseId == phaseId &&
        entry.attemptCount > base &&
        entry.action in NON_OUTPUT_LEDGER_ACTIONS
    }
    .sortedBy(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
    .takeLastWhile { entry ->
      entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED ||
        isProcessFailure(entry.blockedReason.orEmpty())
    }
    .map { entry ->
      FeatureTaskRuntimeNonOutputAttempt(
        paused = entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED,
        reason = entry.blockedReason.orEmpty(),
      )
    }
}

fun FeatureTaskRuntimeRunState.legacyReviewPreparationRetryConsumedBudget(
  phaseId: String,
  currentReason: String,
): Boolean {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ||
    !currentReason.startsWith("Phase 'review' exhausted the bounded fix loop")
  ) {
    return false
  }
  val recentBlocks = initialLedger
    .filter { entry ->
      entry.phaseId == phaseId && entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
    }
    .sortedByDescending(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
    .take(2)
  return recentBlocks.firstOrNull()?.blockedReason == currentReason &&
    recentBlocks.getOrNull(1)?.blockedReason
      ?.startsWith("Goal-subtask review state or durable raw evidence is malformed: [SQLITE_BUSY]") == true
}

fun FeatureTaskRuntimeRunState.legacyLaunchSeamRejectionConsumedBudget(
  phaseId: String,
  currentReason: String,
): Boolean {
  if (!currentReason.startsWith("Phase '$phaseId' exhausted the bounded fix loop") ||
    phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER
  ) {
    return false
  }
  val recentBlocks = initialLedger
    .filter { entry ->
      entry.phaseId == phaseId && entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
    }
    .sortedByDescending(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
    .take(2)
  return recentBlocks.firstOrNull()?.blockedReason == currentReason &&
    recentBlocks.getOrNull(1)?.blockedReason
      ?.contains("rejected an upstream bounded planning projection at the launch seam") == true
}
