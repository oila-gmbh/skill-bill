package skillbill.application.model

import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewLaneCompletionState
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewStageResumeReport
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Path
import kotlin.time.Duration

enum class ParallelReviewScope { STAGED, UNSTAGED, BRANCH, PR }

data class ParallelCodeReviewRequest(
  val agent1Id: String,
  val agent2Id: String?,
  val agent2Model: String? = null,
  val scope: ParallelReviewScope,
  val repoRoot: Path,
  val timeout: Duration?,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val resolvedTier: CodeReviewExecutionMode? = null,
  val suppliedDiff: String? = null,
  val suppliedDiffPath: Path? = null,
  val reviewRunId: String? = null,
  val baseRevision: String? = null,
  val headRevision: String? = null,
  val prelaunchExpansions: List<ReviewPrelaunchExpansion> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
  val specPath: Path? = null,
  val selectedAgentAddonsSection: String = "",
) {
  init {
    reviewRunId?.let { require(it.isNotBlank()) { "reviewRunId must be non-blank when provided." } }
    specPath?.let { require(it.toString().isNotBlank()) { "specPath must be non-blank when provided." } }
    baseRevision?.let { require(it.isNotBlank()) { "baseRevision must be non-blank when provided." } }
    headRevision?.let { require(it.isNotBlank()) { "headRevision must be non-blank when provided." } }
    require(suppliedDiff == null || suppliedDiffPath == null) {
      "suppliedDiff and suppliedDiffPath cannot both be provided."
    }
    require((baseRevision == null) == (headRevision == null)) {
      "baseRevision and headRevision must be supplied together."
    }
    require((suppliedDiff == null && suppliedDiffPath == null) || baseRevision != null) {
      "A supplied diff requires paired baseRevision and headRevision immutable identities."
    }
    resolvedTier?.let { tier ->
      require(tier != CodeReviewExecutionMode.AUTO) {
        "resolvedTier must be a concrete mode, got $tier."
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

  /**
   * Pins lane 1's resolved depth onto the request so lane 2 inherits it rather than re-resolving.
   * The `init` check above is what rejects a mixed-tier pairing, and it runs before either lane
   * starts because the pinned request is built before the lanes are launched.
   */
  fun withResolvedTier(tier: CodeReviewExecutionMode): ParallelCodeReviewRequest = copy(resolvedTier = tier)

  fun withSelectedAgentAddons(prompt: String): String {
    if (selectedAgentAddonsSection.isEmpty()) return prompt
    return prompt.trimEnd() + "\n\n" + selectedAgentAddonsSection
  }

  companion object {
    fun baselineUntrackedPolicy(includedPaths: List<String>, excludedPaths: List<String>) =
      ReviewBaselineUntrackedPolicy(includedPaths, excludedPaths)
  }
}

data class ReviewPrelaunchExpansion(
  val lane: String,
  val path: String,
  val reachabilityReason: String,
) {
  init {
    require(lane.isNotBlank() && path.isNotBlank() && reachabilityReason.isNotBlank()) {
      "A prelaunch expansion requires a lane, path, and non-blank reachability reason."
    }
  }
}

data class ParallelCodeReviewResult(
  val mergeResult: ParallelReviewMergeResult,
  val lane1: ParallelReviewLaneStatus,
  val lane2: ParallelReviewLaneStatus,
  val accountingSummary: ReviewAccountingSummary? = null,
  /** Terminal state of the single integration pass; null only for a non-delegated run. */
  val integration: ReviewIntegrationPassOutcome? = null,
  /**
   * Coverage as it actually stands. Reported alongside [integration] precisely so a completed
   * integration pass is never read as compensating for a lane that ended incomplete.
   */
  val coverage: ReviewCoverageReport? = null,
  val stageResume: ReviewStageResumeReport? = null,
)

data class ParallelReviewLaneStatus(
  val agentId: String,
  val success: Boolean,
  val failureReason: String? = null,
  val droppedCandidateDiagnostic: String? = null,
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

/** One lane's finished state as the integration pass sees it: coverage plus a finding count. */
data class ReviewLaneIntegrationInput(
  val launch: ReviewSpecialistLaunchRequest,
  val completion: ReviewLaneCompletionState,
  val findingCount: Int,
)
