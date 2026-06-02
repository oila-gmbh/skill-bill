package skillbill.workflow.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-64 Subtask 3: single source of truth for bounded, sequence-ordered
 * retention of durable goal history/ledger artifact maps (declared progress,
 * session accounting, attempt ledger). The durable write seam appends one entry
 * to the existing run-history list and prunes to [retentionLimit].
 *
 * Semantics mirror the typed domain `append()` helpers
 * ([GoalProgressHistory.append], `GoalSessionAccountingHistory.append`,
 * `GoalAttemptLedger.append`): entries are ordered by their `sequence_number`
 * (stably, preserving prior order on ties / missing sequences) and the OLDEST
 * entries are pruned first so retention keeps the highest sequence numbers.
 */
@OpenBoundaryMap("Goal history/ledger artifact-map list at the durable bounded-retention write seam")
fun appendBoundedHistoryBySequence(
  existing: List<Map<String, Any?>>,
  entry: Map<String, Any?>,
  retentionLimit: Int,
): List<Map<String, Any?>> = (existing + entry)
  .sortedBy { item -> item.historySequenceNumber() }
  .takeLast(retentionLimit)

private fun Map<String, Any?>.historySequenceNumber(): Int = when (val raw = this["sequence_number"]) {
  is Int -> raw
  is Number -> raw.toInt()
  is String -> raw.toIntOrNull() ?: 0
  else -> 0
}
