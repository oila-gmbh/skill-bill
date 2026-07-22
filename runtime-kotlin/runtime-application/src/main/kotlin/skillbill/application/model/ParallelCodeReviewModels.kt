package skillbill.application.model

import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.ReviewAccountingSummary
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.workflow.model.CodeReviewExecutionMode
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
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val suppliedDiff: String? = null,
  val suppliedDiffPath: Path? = null,
) {
  init {
    suppliedDiff?.let { require(it.isNotBlank()) { "suppliedDiff must be non-blank when provided." } }
    require(suppliedDiff == null || suppliedDiffPath == null) {
      "suppliedDiff and suppliedDiffPath cannot both be provided."
    }
  }
}

data class ParallelCodeReviewResult(
  val mergeResult: ParallelReviewMergeResult,
  val lane1: ParallelReviewLaneStatus,
  val lane2: ParallelReviewLaneStatus,
  val accountingSummary: ReviewAccountingSummary? = null,
)

data class ParallelReviewLaneStatus(
  val agentId: String,
  val success: Boolean,
  val failureReason: String? = null,
  val tokenUsage: ProviderTokenUsage? = null,
  val budgetOutcome: ReviewBudgetOutcome? = null,
  val accounting: ReviewLaneAccounting? = null,
  val specialistAccounting: List<ReviewLaneAccounting> = accounting?.let(::listOf) ?: emptyList(),
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
