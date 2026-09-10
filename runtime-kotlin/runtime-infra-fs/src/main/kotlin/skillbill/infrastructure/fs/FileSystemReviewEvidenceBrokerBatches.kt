package skillbill.infrastructure.fs
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.REVIEW_EVIDENCE_BATCH_SIZE
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewEvidenceLimits
internal fun FileSystemReviewEvidenceBrokerContext.readMeasuredBatch(
  request: ReviewEvidenceBatchRequest,
): ReviewEvidenceBatchResult {
  require(request.requests.size <= REVIEW_EVIDENCE_BATCH_SIZE) { "Evidence batch exceeds its request limit." }
  require(request.lane == assignment.lane) { "Evidence lane does not own this assignment." }
  readState.terminalOutcome?.let { outcome ->
    val terminated = request.requests.map {
      terminalResult(outcome, readState.cumulativeBytes, readState.expansionLedger.size)
    }
    return buildBatchResult(terminated, outcome)
  }
  val requestBytes = GovernedReviewEvidenceCodec.requestMetadataBytes(request)
  require(requestBytes <= ReviewEvidenceLimits.REQUEST_BYTES) { "Evidence batch metadata exceeds its byte limit." }
  val results = mutableListOf<ReviewEvidenceResult>()
  for (evidenceRequest in request.requests) {
    validateReadSelector(evidenceRequest)
    val readSelector = evidenceRequest.authorizedExpansion?.expansionId ?: evidenceRequest.selector
    val result = if (catalog.alreadyDelivered(readSelector, evidenceRequest.path)) {
      forbiddenResult(
        ForbiddenReviewOperation(
          "repeated_evidence_read",
          evidenceRequest.path,
          "This evidence was already delivered.",
        ),
        readState.cumulativeBytes,
        readState.expansionLedger.size,
      )
    } else if (evidenceRequest.selector?.startsWith("target:") == true) {
      val entry = requireNotNull(catalog.entry(requireNotNull(evidenceRequest.selector)))
      val source = sources.first { it.assignment.digest == entry.owners.first().assignmentDigest }
      readAssignedReviewTarget(
        readState,
        evidenceRequest.path,
        entry.selector,
        source.assignment.baseRevision,
        source.assignment.headRevision,
      )
    } else {
      reads.readOne(
        evidenceRequest,
        assignedDelta = evidenceRequest.selector?.let {
          catalog.entry(it)?.let { entry -> entry.expansionId == null && entry.path == evidenceRequest.path }
        } == true && evidenceRequest.authorizedExpansion == null,
      )
    }
    results += result
    val exceeded = result.budgetExceeded
    if (exceeded != null) {
      val served = results.size
      request.requests.drop(served).forEach {
        results += terminalResult(exceeded, readState.cumulativeBytes, readState.expansionLedger.size)
      }
      break
    }
  }
  return buildBatchResult(results, readState.terminalOutcome)
}

internal fun FileSystemReviewEvidenceBrokerContext.buildBatchResult(
  results: List<ReviewEvidenceResult>,
  outcome: ReviewBudgetOutcome?,
): ReviewEvidenceBatchResult {
  refusedOperationCount += results.count { it.forbidden != null || it.budgetExceeded != null }
  val batch = reviewEvidenceBatchResult(
    results = results,
    outcome = outcome,
    cumulativeBytes = readState.cumulativeBytes,
    expansionLedger = readState.expansionLedger.toList(),
    refusalLedger = refusalLedger,
  )
  val bounded = boundedReviewEvidenceBatch(readState, batch)
  if (bounded != batch) recordRequestRefusal()
  return bounded.copy(deliveryReceipt = catalog.receipt(bounded.results.flatMap { it.deliveredSelectors }.toSet()))
}

private fun FileSystemReviewEvidenceBrokerContext.validateReadSelector(evidenceRequest: ReviewEvidenceRequest) {
  evidenceRequest.selector?.let { selector ->
    val entry = catalog.entry(selector)
    require(entry?.path == evidenceRequest.path) { "Unknown evidence selector or mismatched path." }
    require(entry.expansionId == evidenceRequest.authorizedExpansion?.expansionId) {
      "Read selector and whole-file authorization must identify the same evidence unit."
    }
  }
}
