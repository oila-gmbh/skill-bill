package skillbill.application.review

import skillbill.application.review.model.DelegatedReviewLaunchRequest
import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.ports.review.ReviewBuildTestFactsPort
import skillbill.ports.review.ReviewGuidancePort
import skillbill.ports.review.ReviewLaneSelectionPort
import skillbill.ports.review.ReviewLearningsPort
import skillbill.ports.review.ReviewScopeResolverPort
import skillbill.ports.review.ReviewStackRoutingPort
import skillbill.ports.review.model.ReviewFactPorts
import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewRevision
import skillbill.review.plan.model.ReviewLaunchLane
import java.nio.file.Path
import java.security.MessageDigest

/** Compiles the already-resolved parallel-review facts into validated assignment-owned launches. */
internal object ParallelReviewPreparationCompiler {
  fun compile(
    input: ParallelReviewPreparationInput,
    budget: ReviewContextBudgetPolicy,
    envelopeValidator: ReviewContextEnvelopeValidator,
  ): List<DelegatedReviewLaunchRequest> {
    val hunks = input.evidence.hunks
    val routes = specialistRoutes(input)
    val decisions = routes.map { route ->
      ReviewLaneDecision(
        route.lane,
        true,
        "selected non-empty ${route.rubric.area ?: "generic"} specialist lane",
        listOf("parallel-review", route.rubric.area ?: "generic"),
        route.ownedPaths,
        orderIndex = route.descriptor.orderIndex,
        required = route.descriptor.required,
        originLayerChains = route.originLayerChains,
        owningPack = route.descriptor.packSlug,
        specialistSkillName = route.descriptor.skillName,
        addOns = route.descriptor.addOns,
      )
    }
    val revisionId = digest(input.diff)
    val preparation = prepareReview(input, hunks, routes, decisions, revisionId, budget, envelopeValidator)
    return launchRequests(input, preparation, routes, budget)
  }

  private fun specialistRoutes(input: ParallelReviewPreparationInput): List<SpecialistRoute> {
    val selectedRubrics = input.lanes.mapNotNull { planned ->
      val authoritativePaths = planned.descriptor.ownedPaths
      planned.takeIf { authoritativePaths.isNotEmpty() }?.let { SelectedRubric(it, authoritativePaths) }
    }
    require(selectedRubrics.isNotEmpty()) { "Review routing selected no non-empty specialist lane." }
    return input.agents.flatMap { agentId ->
      selectedRubrics.map { selected ->
        SpecialistRoute(
          "$agentId:${selected.planned.descriptor.skillName}",
          agentId,
          selected.planned.rubric,
          selected.planned.descriptor,
          selected.planned.originLayerChains,
          selected.ownedPaths,
          selected.planned.workerKind,
        )
      }
    }
  }

  @Suppress("LongParameterList")
  private fun prepareReview(
    input: ParallelReviewPreparationInput,
    hunks: List<ReviewChangedHunk>,
    routes: List<SpecialistRoute>,
    decisions: List<ReviewLaneDecision>,
    revisionId: String,
    budget: ReviewContextBudgetPolicy,
    envelopeValidator: ReviewContextEnvelopeValidator,
  ) = ReviewPreparationService(
    reviewFactPorts(input, hunks, decisions, revisionId),
    envelopeValidator,
    budget,
  ).prepare(
    ReviewPreparationRequest(
      reviewId = "code-review-parallel-$revisionId",
      reviewRevision = ReviewRevision(revisionId, 1),
      criteriaReferences = routes.associate { it.lane to listOf("independent branch-diff specialist review") },
    ),
  )

  private fun reviewFactPorts(
    input: ParallelReviewPreparationInput,
    hunks: List<ReviewChangedHunk>,
    decisions: List<ReviewLaneDecision>,
    revisionId: String,
  ): ReviewFactPorts {
    val scope = ReviewScopeFacts(
      "repo-root-realpath-v1:${input.repoRoot.toRealPath()}",
      "parallel-scope-base-$revisionId",
      "parallel-scope-head-$revisionId",
      "authoritative supplied parallel-review diff",
      hunks,
    )
    val routing = ReviewStackRoutingFacts(
      input.stack,
      input.routedPacks.joinToString("+"),
      decisions.flatMap { it.addOns }.distinct(),
      decisions.flatMap { it.originLayerChains }.flatten().distinct(),
    )
    return ReviewFactPorts(
      scope = object : ReviewScopeResolverPort {
        override fun resolveScope(reviewId: String) = scope
      },
      stackRouting = object : ReviewStackRoutingPort {
        override fun resolveStackRouting(scope: ReviewScopeFacts) = routing
      },
      guidance = object : ReviewGuidancePort {
        override fun resolveMatchedRules(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
          emptyList<skillbill.review.context.model.ReviewRuleReference>()
      },
      learnings = object : ReviewLearningsPort {
        override fun resolveLearnings(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
          emptyList<skillbill.review.context.model.ReviewLearningsReference>()
      },
      buildTestFacts = object : ReviewBuildTestFactsPort {
        override fun resolveBuildTestFacts(scope: ReviewScopeFacts) =
          emptyList<skillbill.review.context.model.ReviewBuildTestFact>()
      },
      laneSelection = object : ReviewLaneSelectionPort {
        override fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = decisions
      },
    )
  }

  private fun launchRequests(
    input: ParallelReviewPreparationInput,
    preparation: skillbill.application.review.model.ReviewPreparationResult,
    routes: List<SpecialistRoute>,
    budget: ReviewContextBudgetPolicy,
  ): List<DelegatedReviewLaunchRequest> {
    val routesByLane = routes.associateBy(SpecialistRoute::lane).also {
      require(it.size == routes.size) { "Prepared specialist routes contain duplicate lane keys." }
    }
    return preparation.assignments.map { assignment ->
      val route = requireNotNull(routesByLane[assignment.lane]) {
        "Prepared assignment '${assignment.lane}' has no selected specialist route."
      }
      require(assignment.laneDecision.specialistSkillName == route.rubric.rubricId) {
        "Prepared assignment '${assignment.lane}' drifted from rubric '${route.rubric.rubricId}'."
      }
      require(assignment.assignedPaths == route.ownedPaths) {
        "Prepared assignment '${assignment.lane}' drifted from resolved ownership."
      }
      DelegatedReviewLaunchRequest(
        packet = preparation.packet,
        assignment = assignment,
        specialistContract = SPECIALIST_CONTRACT,
        rubrics = listOf(route.rubric),
        brokerId = "review-evidence-${assignment.digest}",
        budget = budget,
        agentId = route.agentId,
        workerKind = route.workerKind,
        logicalWorkerName = route.descriptor.skillName.takeIf { route.workerKind == ReviewWorkerKind.PROVIDER_NATIVE },
        repoRoot = input.repoRoot,
      )
    }
  }

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.replace("\r\n", "\n").toByteArray())
    .joinToString("") { "%02x".format(it) }

  private const val SPECIALIST_CONTRACT =
    "Use only the assignment-owned evidence surface. Return only F-XXX risk-register lines."
}

private data class SelectedRubric(val planned: PlannedReviewRubric, val ownedPaths: List<String>)

private data class SpecialistRoute(
  val lane: String,
  val agentId: String,
  val rubric: ReviewRubricProjection,
  val descriptor: ReviewLaunchLane,
  val originLayerChains: List<List<String>>,
  val ownedPaths: List<String>,
  val workerKind: ReviewWorkerKind,
)

internal data class PlannedReviewRubric(
  val descriptor: ReviewLaunchLane,
  val rubric: ReviewRubricProjection,
  val originLayerChains: List<List<String>> = listOf(descriptor.originLayerChain),
  val workerKind: ReviewWorkerKind = ReviewWorkerKind.PROVIDER_NATIVE,
)

internal data class ParallelReviewPreparationInput(
  val diff: String,
  val evidence: ReviewDiffEvidence,
  val stack: String?,
  val agents: List<String>,
  val repoRoot: Path,
  val routedPacks: List<String>,
  val lanes: List<PlannedReviewRubric>,
)
