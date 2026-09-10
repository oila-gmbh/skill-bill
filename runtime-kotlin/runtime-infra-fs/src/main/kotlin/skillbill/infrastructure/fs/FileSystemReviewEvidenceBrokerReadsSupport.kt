package skillbill.infrastructure.fs

import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewChangedHunk

internal fun exceededEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  kind: String,
  limit: Long,
  observed: Long,
): ReviewEvidenceResult {
  val outcome = checkNotNull(ReviewBudgetEvaluator.exceededOrNull(state.identity, kind, limit, observed)) {
    "Budget dimension '$kind' reported an excess of $observed against $limit that does not exceed it."
  }
  if (state.terminalOutcome == null) state.terminalOutcome = outcome
  return terminalResult(requireNotNull(state.terminalOutcome), state.cumulativeBytes, state.expansionLedger.size)
}

internal fun assignedHunkBudgetOutcome(
  state: FileSystemReviewEvidenceBrokerReadState,
  bytes: Long,
  unit: String,
): ReviewEvidenceResult? {
  val observedCumulative = state.cumulativeBytes + bytes
  return if (observedCumulative > state.budget.maxLaneEvidenceBytes) {
    recordLaneEvidenceDenial(state, unit)
    exceededEvidence(state, "lane_evidence_bytes", state.budget.maxLaneEvidenceBytes, observedCumulative)
  } else {
    null
  }
}

internal fun evidenceBudgetOutcome(
  state: FileSystemReviewEvidenceBrokerReadState,
  bytes: Long,
  unit: String,
): ReviewEvidenceResult? {
  if (bytes > state.budget.maxEvidenceResultBytes) {
    return exceededEvidence(state, "evidence_result_bytes", state.budget.maxEvidenceResultBytes, bytes)
  }
  val observedCumulative = state.cumulativeBytes + bytes
  return if (observedCumulative > state.budget.maxLaneEvidenceBytes) {
    recordLaneEvidenceDenial(state, unit)
    exceededEvidence(state, "lane_evidence_bytes", state.budget.maxLaneEvidenceBytes, observedCumulative)
  } else {
    null
  }
}

internal fun recordLaneEvidenceDenial(state: FileSystemReviewEvidenceBrokerReadState, unit: String) {
  state.deniedUnits += unit
}

internal fun unitForHunk(state: FileSystemReviewEvidenceBrokerReadState, hunk: ReviewChangedHunk): String =
  "${commitShaForHunk(state, hunk.hunkId)}@${hunk.path}"

internal fun unitAtPath(state: FileSystemReviewEvidenceBrokerReadState, path: String): String {
  val hunkId = state.projectedHunks.firstOrNull { it.path == path }?.hunkId
  val commit = hunkId?.let { commitShaForHunk(state, it) } ?: state.assignment.headRevision
  return "$commit@$path"
}

internal fun commitShaForHunk(state: FileSystemReviewEvidenceBrokerReadState, hunkId: String): String =
  state.hunkCommitById[hunkId] ?: state.assignment.headRevision

internal fun readAssignedReviewTarget(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  selector: String,
  base: String,
  head: String,
): ReviewEvidenceResult {
  val content = readImmutableReviewDelta(state.root, base, head, path, state.budget.maxEvidenceResultBytes)
    ?: return unavailableEvidence(state, path)
  evidenceBudgetOutcome(state, content.size.toLong(), selector)?.let { return it }
  state.authorizedReadCount += 1
  state.cumulativeBytes += content.size
  return ReviewEvidenceResult(
    content.toString(Charsets.UTF_8),
    content.size.toLong(),
    state.cumulativeBytes,
    state.expansionLedger.size,
    deliveredSelectors = listOf(selector),
  )
}
