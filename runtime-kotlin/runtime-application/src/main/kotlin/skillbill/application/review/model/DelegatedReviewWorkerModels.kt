package skillbill.application.review.model

import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import java.nio.file.Path
import kotlin.time.Duration

enum class ReviewWorkerKind {
  PROVIDER_NATIVE,
  GENERIC,
}

data class ReviewRubricProjection(val rubricId: String, val body: String) {
  init {
    require(rubricId.isNotBlank()) { "A projected rubric must carry an id." }
    require(body.isNotBlank()) { "A projected rubric must carry a body." }
  }
}

data class DelegatedReviewLaunchRequest(
  val packet: ReviewContextPacket,
  val assignment: ReviewAssignment,
  val specialistContract: String,
  val rubrics: List<ReviewRubricProjection>,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val agentId: String,
  val workerKind: ReviewWorkerKind,
  val repoRoot: Path,
  val namedDependencies: Set<String> = emptySet(),
)

data class DelegatedReviewLaunch(
  val launch: GovernedReviewLaunch,
  val prompt: String,
  val isolation: ReviewLaunchIsolationStrategy,
  val evidenceBroker: ReviewEvidenceBroker,
  val rubricIsAuthoritative: Boolean,
)

sealed interface DelegatedReviewLaunchOutcome {
  data class Prepared(val launch: DelegatedReviewLaunch) : DelegatedReviewLaunchOutcome
  data class Terminated(val outcome: ReviewBudgetOutcome) : DelegatedReviewLaunchOutcome
}

data class DelegatedReviewWorkerRequest(
  val prepared: DelegatedReviewLaunchOutcome.Prepared,
  val agentId: String,
  val repoRoot: Path,
  val timeout: Duration,
  val modelOverride: String? = null,
)

data class DelegatedReviewWorkerOutcome(
  val facts: AgentRunLaunchFacts? = null,
  val unsupportedReason: String? = null,
  val forbiddenOperation: ForbiddenReviewOperation? = null,
  val budgetOutcome: ReviewBudgetOutcome? = null,
  val accounting: ReviewLaneAccounting? = null,
) {
  init {
    require(
      listOf(
        facts != null,
        unsupportedReason != null,
        forbiddenOperation != null,
        budgetOutcome != null && facts == null,
      ).count { it } == 1,
    ) {
      "A delegated worker outcome carries launch facts, an unsupported reason, a forbidden operation, or a " +
        "pre-launch budget outcome."
    }
  }
}

data class DelegatedReviewExecutionRequest(
  val launchRequest: DelegatedReviewLaunchRequest,
  val repoRoot: Path,
  val timeout: Duration,
  val modelOverride: String? = null,
)

sealed interface DelegatedReviewExecutionOutcome {
  data class Completed(val worker: DelegatedReviewWorkerOutcome) : DelegatedReviewExecutionOutcome
  data class Terminated(val budgetOutcome: ReviewBudgetOutcome) : DelegatedReviewExecutionOutcome
}
