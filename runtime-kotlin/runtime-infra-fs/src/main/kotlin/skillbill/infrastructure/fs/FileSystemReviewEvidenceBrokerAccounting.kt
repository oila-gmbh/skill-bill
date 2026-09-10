package skillbill.infrastructure.fs
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewRequestedOperation
import java.nio.charset.StandardCharsets
internal fun FileSystemReviewEvidenceBrokerContext.recordToolCall(call: ReviewToolCall): ReviewToolCallResult {
  require(call.lane == assignment.lane) { "Tool call lane does not own this assignment." }
  readState.terminalOutcome?.let { return ReviewToolCallResult(budgetExceeded = it) }
  policy.classify(ReviewRequestedOperation(call.kind, call.target, searchScopes = call.searchScopes))?.let {
    return ReviewToolCallResult(forbidden = it)
  }
  toolCalls += 1
  val outcome = ReviewBudgetEvaluator.exceededOrNull(
    identity,
    "specialist_tool_calls",
    budget.maxSpecialistToolCalls.toLong(),
    toolCalls.toLong(),
  )
  return ReviewToolCallResult(budgetExceeded = outcome?.also { readState.terminalOutcome = it })
}

internal fun FileSystemReviewEvidenceBrokerContext.recordModelTurn(): ReviewBudgetOutcome? {
  readState.terminalOutcome?.let { return it }
  modelTurns += 1
  return ReviewBudgetEvaluator.exceededOrNull(
    identity,
    "specialist_model_turns",
    budget.maxSpecialistModelTurns.toLong(),
    modelTurns.toLong(),
  )?.also { readState.terminalOutcome = it }
}

internal fun FileSystemReviewEvidenceBrokerContext.validateLaneResult(result: String): ReviewBudgetOutcome? {
  laneResultObserved = true
  readState.terminalOutcome?.let { return it }
  resultBytes = maxOf(resultBytes, result.toByteArray(StandardCharsets.UTF_8).size.toLong())
  return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)
    ?.also { readState.terminalOutcome = it }
}

internal fun FileSystemReviewEvidenceBrokerContext.observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? {
  laneResultObserved = true
  readState.terminalOutcome?.let { return it }
  resultBytes += chunk.toByteArray(StandardCharsets.UTF_8).size.toLong()
  return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)
    ?.also { readState.terminalOutcome = it }
}

internal fun FileSystemReviewEvidenceBrokerContext.hasObservedLaneResult(): Boolean = laneResultObserved

internal fun FileSystemReviewEvidenceBrokerContext.accounting(): ReviewLaneAccounting {
  val terminal = readState.terminalOutcome
  val evidenceIncomplete = terminal?.budgetKind == LANE_EVIDENCE_BYTES_DIMENSION
  return ReviewLaneAccounting(
    lane = assignment.lane,
    authorizedReadCount = readState.authorizedReadCount,
    refusedOperationCount = refusedOperationCount,
    refusals = refusalLedger.toList(),
    evidenceBytes = readState.cumulativeBytes,
    expansions = readState.expansionLedger.toList(),
    toolCalls = toolCalls,
    modelTurns = modelTurns,
    resultBytes = resultBytes,
    terminalOutcome = terminal,
    budgetDimension = if (evidenceIncomplete) LANE_EVIDENCE_BYTES_DIMENSION else null,
    unreviewedUnits = catalog.remaining().map { it.unitId }.distinct(),
    requiredEvidenceUnits = catalog.requiredCount(),
    deliveredEvidenceUnits = catalog.deliveredCount(),
    remainingEvidence = catalog.remaining(),
    evidenceRequests = evidenceRequests,
  )
}

internal fun FileSystemReviewEvidenceBrokerContext.terminalOutcome(): ReviewBudgetOutcome? = readState.terminalOutcome
