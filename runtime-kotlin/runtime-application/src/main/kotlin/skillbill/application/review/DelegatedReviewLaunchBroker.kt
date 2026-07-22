package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewLaunch
import skillbill.application.review.model.DelegatedReviewLaunchOutcome
import skillbill.application.review.model.DelegatedReviewLaunchRequest
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewLaunchIsolationResolver
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewContextBudgetExceeded
import skillbill.review.context.model.ReviewLaneIdentity
import java.nio.charset.StandardCharsets

/**
 * The only production constructor of a delegated review launch. It validates the assignment against
 * its parent packet, enforces the packet and launch byte budgets, projects exactly the permitted
 * fields, and hands back the per-lane evidence broker the worker must act through.
 */
@Inject
class DelegatedReviewLaunchBroker(
  private val evidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  private val isolationResolver: ReviewLaunchIsolationResolver,
) {
  fun prepare(request: DelegatedReviewLaunchRequest): DelegatedReviewLaunchOutcome {
    requireBoundedNamedDependencies(request)
    val rubric = requireSingleRubric(request)
    val isolation = requireSupportedIsolation(request)

    parentPacketOutcome(request)?.let { return DelegatedReviewLaunchOutcome.Terminated(it) }

    val launch = GovernedReviewLaunch(
      assignment = request.assignment,
      packet = request.packet,
      specialistContract = request.specialistContract,
      rubric = rubric.body,
      brokerId = request.brokerId,
      budget = request.budget,
    )
    if (isolation == ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE) {
      launch.requireCodexForkTurns(isolation.forkTurns)
    }

    val prompt = projectPrompt(launch, isolation, request.workerKind)
    ReviewBudgetEvaluator.exceededOrNull(
      ReviewLaneIdentity.of(request.assignment),
      "lane_launch_bytes",
      request.budget.maxLaneLaunchBytes,
      prompt.toByteArray(StandardCharsets.UTF_8).size.toLong(),
    )?.let { return DelegatedReviewLaunchOutcome.Terminated(it) }

    val evidenceBroker = evidenceBrokerFactory.brokerFor(
      ReviewEvidenceBrokerBinding(
        repoRoot = request.repoRoot,
        assignment = request.assignment,
        laneRubricId = rubric.rubricId,
        budget = request.budget,
        namedDependencies = request.namedDependencies,
        trustedExpansionLedger = request.packet.expansionLedger,
      ),
    )
    return DelegatedReviewLaunchOutcome.Prepared(
      DelegatedReviewLaunch(
        launch = launch,
        prompt = prompt,
        isolation = isolation,
        evidenceBroker = evidenceBroker,
        rubricIsAuthoritative = request.workerKind == ReviewWorkerKind.PROVIDER_NATIVE,
      ),
    )
  }

  private fun requireBoundedNamedDependencies(request: DelegatedReviewLaunchRequest) {
    val allowed = request.assignment.dependencyAllowlist.normalized.toSet()
    val requested = request.namedDependencies.toSet()
    val escaping = requested - allowed
    if (escaping.isNotEmpty()) {
      reject(
        request,
        "Named dependencies escape the validated assignment dependency allowlist: ${escaping.sorted()}.",
      )
    }
  }

  private fun requireSingleRubric(request: DelegatedReviewLaunchRequest): ReviewRubricProjection {
    if (request.rubrics.size != 1) {
      reject(
        request,
        "A delegated launch carries exactly one rubric; lane '${request.assignment.lane}' was given " +
          "${request.rubrics.size}.",
      )
    }
    return request.rubrics.single().also { rubric ->
      val specialist = request.assignment.laneDecision.specialistSkillName
      if (rubric.rubricId != specialist) {
        reject(
          request,
          "Delegated launch rubric '${rubric.rubricId}' does not match assignment specialist '$specialist'.",
        )
      }
    }
  }

  private fun requireSupportedIsolation(request: DelegatedReviewLaunchRequest): ReviewLaunchIsolationStrategy {
    val isolation = isolationResolver.isolationFor(request.agentId)
    if (!isolation.supported) {
      reject(
        request,
        "Agent '${request.agentId}' exposes no conversation-isolation strategy, so a governed specialist " +
          "cannot be launched in a fresh context. Delegated review is unavailable for this agent.",
      )
    }
    return isolation
  }

  private fun parentPacketOutcome(request: DelegatedReviewLaunchRequest): ReviewContextBudgetExceeded? {
    val identity = ReviewLaneIdentity.of(request.assignment)
    val observed = request.packet.canonicalBytes
    return if (observed > request.budget.maxParentPacketBytes) {
      ReviewContextBudgetExceeded(
        lane = identity.lane,
        budgetKind = "parent_packet_bytes",
        configuredLimit = request.budget.maxParentPacketBytes,
        observedValue = observed,
        packetDigest = identity.packetDigest,
        assignmentDigest = identity.assignmentDigest,
        enforceable = true,
      )
    } else {
      null
    }
  }

  /**
   * The launch payload is the whole of what the worker sees. Nothing about parent scope, status,
   * routing, learnings, guidance, telemetry, or ceremony is projected, and no parent transcript.
   */
  private fun projectPrompt(
    launch: GovernedReviewLaunch,
    isolation: ReviewLaunchIsolationStrategy,
    workerKind: ReviewWorkerKind,
  ): String = buildString {
    appendLine(launch.canonicalPayload)
    appendLine(
      "finding_protocol: [F-NNN] Severity | Confidence | optional specialist=<exact identity> | " +
        "path=<JSON string> | line=<positive integer> | description",
    )
    appendLine("isolation: ${isolation.name.lowercase()}")
    appendLine(
      when (workerKind) {
        ReviewWorkerKind.PROVIDER_NATIVE ->
          "rubric_authority: embedded governed rubric is authoritative; do not read any rubric sidecar."
        ReviewWorkerKind.GENERIC ->
          "rubric_authority: the single rubric projected above is authoritative; read no other rubric."
      },
    )
    append("evidence_surface: all repository access goes through broker ${launch.brokerId}; it is measured.")
  }

  private fun reject(request: DelegatedReviewLaunchRequest, reason: String): Nothing =
    throw InvalidReviewContextSchemaError(
      sourceLabel = "review-launch:${request.assignment.reviewId}:${request.assignment.lane}",
      reason = reason,
    )
}
