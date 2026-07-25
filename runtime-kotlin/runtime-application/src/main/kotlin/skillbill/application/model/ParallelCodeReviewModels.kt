package skillbill.application.model

import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewBudgetOutcome
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
  val resolvedTier: CodeReviewExecutionMode? = null,
  val suppliedDiff: String? = null,
  val suppliedDiffPath: Path? = null,
  val reviewRunId: String? = null,
) {
  init {
    reviewRunId?.let { require(it.isNotBlank()) { "reviewRunId must be non-blank when provided." } }
    suppliedDiff?.let { require(it.isNotBlank()) { "suppliedDiff must be non-blank when provided." } }
    require(suppliedDiff == null || suppliedDiffPath == null) {
      "suppliedDiff and suppliedDiffPath cannot both be provided."
    }
    resolvedTier?.let { tier ->
      require(tier == CodeReviewExecutionMode.INLINE || tier == CodeReviewExecutionMode.DELEGATED) {
        "resolvedTier must be either INLINE or DELEGATED, got $tier."
      }
      require(codeReviewMode == CodeReviewExecutionMode.AUTO || codeReviewMode == tier) {
        "Both parallel review lanes must share one resolved depth tier: lane 1 resolved to " +
          "${tier.wireValue} but the requested mode is ${codeReviewMode.wireValue}."
      }
    }
  }

  /**
   * Lane 2 never resolves depth for itself; it inherits whatever lane 1 resolved to so a light lane
   * can never be paired with a full-depth one.
   */
  val lane2Tier: CodeReviewExecutionMode get() = resolvedTier ?: codeReviewMode
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
