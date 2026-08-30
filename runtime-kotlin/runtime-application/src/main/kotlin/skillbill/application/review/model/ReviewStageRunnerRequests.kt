package skillbill.application.review.model

import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.SpecIntentProjection
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import java.nio.file.Path
import kotlin.time.Duration

internal data class ReviewDelegatedStageLaunch(
  val budget: ReviewContextBudgetPolicy,
  val brokerId: String,
  val repoRoot: Path,
  val timeout: Duration?,
  val modelOverride: String? = null,
  val promptSuffix: String = "",
)

internal data class ReviewClaimVerificationRunRequest(
  val packet: ReviewContextPacket?,
  val reviewOutput: String = "",
  val findings: List<ParallelReviewMergedFinding>,
  val existingVerdicts: List<ReviewFindingVerdict>,
  val mode: ResolvedReviewExecutionMode,
  val launch: ReviewDelegatedStageLaunch,
)

internal data class ReviewSpecAdjudicationRunRequest(
  val packet: ReviewContextPacket?,
  val findings: List<ParallelReviewMergedFinding>,
  val existingVerdicts: List<ReviewFindingVerdict>,
  val projection: SpecIntentProjection?,
  val launch: ReviewDelegatedStageLaunch,
)

internal data class ReviewIntegrationPassRunRequest(
  val packet: ReviewContextPacket,
  val lanes: List<ReviewLaneIntegrationInput>,
  val launch: ReviewDelegatedStageLaunch,
)
