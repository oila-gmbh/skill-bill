package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.ParallelCodeReviewMergeRequest
import skillbill.application.model.ParallelCodeReviewMergeResult
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.model.ParallelReviewLaneResult

@Inject
class ParallelCodeReviewMergeService {
  fun merge(request: ParallelCodeReviewMergeRequest): ParallelCodeReviewMergeResult {
    val lane1 = ParallelReviewLaneResult(
      agentId = request.lane1AgentId,
      findings = ParallelReviewFindingParser.parse(request.lane1RawOutput),
    )
    val lane2 = ParallelReviewLaneResult(
      agentId = request.lane2AgentId,
      findings = ParallelReviewFindingParser.parse(request.lane2RawOutput),
    )
    val mergeResult = ParallelReviewMerger.merge(lane1, lane2)
    return ParallelCodeReviewMergeResult(formattedOutput = mergeResult.formattedOutput)
  }
}
