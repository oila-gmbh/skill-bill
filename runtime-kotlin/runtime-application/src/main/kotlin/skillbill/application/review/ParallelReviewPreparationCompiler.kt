package skillbill.application.review

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.ports.review.ReviewBuildTestFactsPort
import skillbill.ports.review.ReviewGuidancePort
import skillbill.ports.review.ReviewLaneSelectionPort
import skillbill.ports.review.ReviewLearningsPort
import skillbill.ports.review.ReviewScopeResolverPort
import skillbill.ports.review.ReviewStackRoutingPort
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewFactPorts
import skillbill.ports.review.model.ReviewLaneSelection
import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewRevision
import skillbill.review.plan.ReviewCommitLaneRoutingPolicy
import skillbill.review.plan.ReviewRoutedLane
import skillbill.review.plan.model.ReviewLaunchLane
import java.nio.file.Path
import java.security.MessageDigest

/** Compiles the already-resolved parallel-review facts into validated assignment-owned launches. */
internal object ParallelReviewPreparationCompiler {
  fun compile(
    input: ParallelReviewPreparationInput,
    budget: ReviewContextBudgetPolicy,
    envelopeValidator: ReviewContextEnvelopeValidator,
    specialistContract: String,
  ): List<ReviewSpecialistLaunchRequest> {
    // Commit units are the authoritative hunk surface: the packet's flat changedHunks is exactly
    // their union, so every hunk stays attributable to the commit that introduced it.
    val hunks = input.commitSequence.units.flatMap { it.hunks }
    val candidates = specialistRoutes(input)
    val routingMatrix = ReviewCommitLaneRoutingPolicy.route(
      input.commitSequence.units,
      candidates.map { ReviewRoutedLane(it.lane, it.descriptor) },
      budget,
    )
    val routes = narrowToFocusedCommits(input, candidates, routingMatrix)
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
    val revisionId = digest("${input.baseRevision}\u0000${input.headRevision}\u0000${input.diff}")
    val selection = ReviewLaneSelection(decisions, routingMatrix)
    val preparation = prepareReview(input, hunks, routes, selection, revisionId, budget, envelopeValidator)
    return launchRequests(input, preparation, routes, budget, specialistContract)
  }

  /**
   * Sparse selection: a lane survives only where routing focused at least one commit, and it then
   * owns exactly what those commits changed under its previously resolved path ownership. Required
   * baseline lanes focus every commit, so this can only ever narrow an optional lane.
   */
  private fun narrowToFocusedCommits(
    input: ParallelReviewPreparationInput,
    candidates: List<SpecialistRoute>,
    routingMatrix: ReviewCommitLaneRoutingMatrix,
  ): List<SpecialistRoute> {
    val routes = candidates.mapNotNull { candidate ->
      val focused = routingMatrix.focusedCommits(candidate.lane).toSet()
      val owned = candidate.ownedPaths.toSet()
      val ownedPaths = input.commitSequence.units
        .filter { it.commitSha in focused }
        .flatMap { unit -> unit.hunks.map { it.path }.filter { it in owned } }
        .distinct()
        .sorted()
      candidate.copy(ownedPaths = ownedPaths).takeIf { ownedPaths.isNotEmpty() }
    }
    require(routes.isNotEmpty()) { "Commit/lane routing focused no specialist lane onto any commit." }
    val surviving = routes.map { it.lane }.toSet()
    val droppedRequired = candidates.filter { it.descriptor.required && it.lane !in surviving }.map { it.lane }
    require(droppedRequired.isEmpty()) {
      "Sparse routing dropped required baseline lanes: ${droppedRequired.sorted()}."
    }
    return routes
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
    selection: ReviewLaneSelection,
    revisionId: String,
    budget: ReviewContextBudgetPolicy,
    envelopeValidator: ReviewContextEnvelopeValidator,
  ) = ReviewPreparationService(
    reviewFactPorts(input, hunks, selection),
    envelopeValidator,
    budget,
  ).prepare(
    ReviewPreparationRequest(
      reviewId = input.reviewRunId ?: "code-review-parallel-$revisionId",
      reviewRevision = ReviewRevision(revisionId, 1),
      criteriaReferences = routes.associate { it.lane to listOf("independent branch-diff specialist review") },
      baselineUntrackedPolicy = input.baselineUntrackedPolicy,
    ),
  )

  private fun reviewFactPorts(
    input: ParallelReviewPreparationInput,
    hunks: List<ReviewChangedHunk>,
    selection: ReviewLaneSelection,
  ): ReviewFactPorts {
    val decisions = selection.decisions
    val scope = ReviewScopeFacts(
      "repo-root-realpath-v1:${input.repoRoot.toRealPath()}",
      input.baseRevision,
      input.headRevision,
      "authoritative supplied parallel-review diff",
      hunks,
      input.commitSequence.units,
      input.commitSequence.coverageFact,
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
        override fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = selection
      },
    )
  }

  private fun launchRequests(
    input: ParallelReviewPreparationInput,
    preparation: skillbill.application.review.model.ReviewPreparationResult,
    routes: List<SpecialistRoute>,
    budget: ReviewContextBudgetPolicy,
    specialistContract: String,
  ): List<ReviewSpecialistLaunchRequest> {
    val routesByLane = routes.associateBy(SpecialistRoute::lane).also {
      require(it.size == routes.size) { "Prepared specialist routes contain duplicate lane keys." }
    }
    val validExpansionSelectors = routes.flatMap { route ->
      listOf(route.lane, route.lane.substringAfter(':'))
    }.toSet() + PARALLEL_REVIEW_SELECTOR
    input.prelaunchExpansions.forEach { expansion ->
      require(expansion.lane in validExpansionSelectors) {
        "Prelaunch expansion selector '${expansion.lane}' does not match '$PARALLEL_REVIEW_SELECTOR', " +
          "a prepared assignment lane, or a prepared specialist skill."
      }
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
      ReviewSpecialistLaunchRequest(
        packet = preparation.packet,
        assignment = assignment,
        specialistContract = specialistContract,
        rubrics = listOf(route.rubric),
        brokerId = "review-evidence-${assignment.digest}",
        budget = budget,
        agentId = route.agentId,
        workerKind = route.workerKind,
        logicalWorkerName = route.descriptor.skillName.takeIf {
          route.workerKind == ReviewWorkerKind.PROVIDER_NATIVE
        },
        repoRoot = input.repoRoot,
        prelaunchExpansions = input.prelaunchExpansions
          .filter {
            it.lane == PARALLEL_REVIEW_SELECTOR ||
              it.lane == assignment.lane ||
              it.lane == assignment.lane.substringAfter(':')
          }
          .map { ReviewExpansionAuthorizationRequest(assignment.lane, it.path, it.reachabilityReason) },
      )
    }
  }

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.replace("\r\n", "\n").toByteArray())
    .joinToString("") { "%02x".format(it) }

  private const val PARALLEL_REVIEW_SELECTOR = "parallel-code-review"
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
  val commitSequence: ResolvedCommitSequence,
  val stack: String?,
  val agents: List<String>,
  val repoRoot: Path,
  val routedPacks: List<String>,
  val lanes: List<PlannedReviewRubric>,
  // review_finished telemetry resolves accounting by the caller's run id, but the row is keyed by the
  // packet review id, so the packet must adopt that run id whenever the caller supplies one.
  val reviewRunId: String? = null,
  val baseRevision: String,
  val headRevision: String,
  val prelaunchExpansions: List<skillbill.application.model.ReviewPrelaunchExpansion> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
)
