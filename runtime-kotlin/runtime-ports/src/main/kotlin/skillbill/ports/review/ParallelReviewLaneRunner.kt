package skillbill.ports.review

import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult

fun interface ParallelReviewLaneRunner {
  fun runTwoLanes(request: ParallelReviewLaneRunRequest): ParallelReviewLaneRunResult
}
