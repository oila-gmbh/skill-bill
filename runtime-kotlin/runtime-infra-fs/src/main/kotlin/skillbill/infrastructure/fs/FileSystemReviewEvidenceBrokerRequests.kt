package skillbill.infrastructure.fs
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.error.ShellContentContractException
import skillbill.ports.review.model.REVIEW_EVIDENCE_MAX_REQUESTS
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.review.context.model.ReviewBudgetEvaluator
import java.io.IOException
internal fun <T> FileSystemReviewEvidenceBrokerContext.measuredRequest(action: () -> T): T {
  countRequest()
  var failure: Exception? = null
  try {
    return action()
  } catch (error: ShellContentContractException) {
    failure = error
  } catch (error: IOException) {
    failure = InvalidReviewContextSchemaError("review-evidence", "Evidence could not be materialized.", cause = error)
  } catch (error: IllegalArgumentException) {
    failure = error
  }
  recordRequestRefusal()
  throw requireNotNull(failure)
}

internal fun FileSystemReviewEvidenceBrokerContext.recordRequestRefusal() {
  refusedOperationCount += 1
  appendReviewRefusal(refusalLedger, ReviewRefusedOperationRecord("invalid_request", "review-evidence"))
}

internal fun FileSystemReviewEvidenceBrokerContext.countRequest() {
  evidenceRequests = minOf(evidenceRequests + 1, REVIEW_EVIDENCE_MAX_REQUESTS + 1)
  val outcome = ReviewBudgetEvaluator.exceededOrNull(
    identity,
    "evidence_requests",
    REVIEW_EVIDENCE_MAX_REQUESTS.toLong(),
    evidenceRequests.toLong(),
  )
  if (outcome != null && readState.terminalOutcome == null) readState.terminalOutcome = outcome
}
