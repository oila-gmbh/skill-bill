package skillbill.application.model

import skillbill.review.model.ParallelReviewMergeResult
import java.nio.file.Path
import kotlin.time.Duration

enum class ParallelReviewScope { STAGED, UNSTAGED, BRANCH, PR }

data class ParallelCodeReviewRequest(
  val agent1Id: String,
  val agent2Id: String,
  val agent2Model: String? = null,
  val scope: ParallelReviewScope,
  val repoRoot: Path,
  val timeout: Duration?,
)

data class ParallelCodeReviewResult(
  val mergeResult: ParallelReviewMergeResult,
  val lane1: ParallelReviewLaneStatus,
  val lane2: ParallelReviewLaneStatus,
)

data class ParallelReviewLaneStatus(
  val agentId: String,
  val success: Boolean,
  val failureReason: String? = null,
)

data class ParallelCodeReviewMergeRequest(
  val lane1AgentId: String,
  val lane1RawOutput: String,
  val lane2AgentId: String,
  val lane2RawOutput: String,
)

data class ParallelCodeReviewMergeResult(
  val formattedOutput: String,
)

class DiffResolutionException(message: String) : RuntimeException(message)

class UsageValidationException(message: String) : RuntimeException(message)

class StackDetectionException(message: String, cause: Throwable) : RuntimeException(message, cause)
