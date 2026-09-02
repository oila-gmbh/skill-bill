package skillbill.application.featuretask

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.review.model.ParallelReviewMergeResult

object ApprovingReviewDriverStub : FeatureTaskRuntimeReviewDriver {
  override fun run(request: ParallelCodeReviewRequest): ParallelCodeReviewResult = ParallelCodeReviewResult(
    mergeResult = ParallelReviewMergeResult(
      findings = emptyList(),
      formattedOutput = "verdict: approved",
    ),
    lane1 = ParallelReviewLaneStatus(agentId = request.agent1Id, success = true),
  )
}
