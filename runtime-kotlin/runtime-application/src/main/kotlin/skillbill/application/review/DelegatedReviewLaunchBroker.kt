package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewLaunch
import skillbill.application.review.model.DelegatedReviewLaunchOutcome
import skillbill.application.review.model.DelegatedReviewLaunchRequest
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewLaunchIsolationResolver
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.review.context.ReviewContextEnvelopeValidator
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
  private val envelopeValidator: ReviewContextEnvelopeValidator,
) {
  fun prepare(request: DelegatedReviewLaunchRequest): DelegatedReviewLaunchOutcome {
    requireBoundedNamedDependencies(request)
    val rubric = requireSingleRubric(request)
    val isolation = requireSupportedIsolation(request)

    parentPacketOutcome(request)?.let { return DelegatedReviewLaunchOutcome.Terminated(it) }

    val launch = try {
      GovernedReviewLaunch(
        assignment = request.assignment,
        packet = request.packet,
        specialistContract = request.specialistContract,
        rubric = rubric.body,
        brokerId = request.brokerId,
        budget = request.budget,
      )
    } catch (error: IllegalArgumentException) {
      reject(request, error.message ?: "The governed launch could not be projected.")
    }
    val envelope = launch.toLaunchEnvelope().asWireMap()
    envelopeValidator.validate(envelope, launchLabel(request))
    if (isolation == ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE) {
      launch.requireCodexForkTurns(isolation.forkTurns)
    }

    val prompt = JsonSupport.mapToJsonString(envelope)
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
        projectedHunks = request.packet.changedHunks.filter { it.hunkId in request.assignment.assignedHunks },
      ),
    )
    val preauthorizedEvidenceRequests = authorizePrelaunchExpansions(request, evidenceBroker)
    return DelegatedReviewLaunchOutcome.Prepared(
      DelegatedReviewLaunch(
        launch = launch,
        prompt = prompt,
        isolation = isolation,
        evidenceBroker = evidenceBroker,
        rubricIsAuthoritative = request.workerKind == ReviewWorkerKind.PROVIDER_NATIVE,
        preauthorizedEvidenceRequests = preauthorizedEvidenceRequests,
      ),
    )
  }

  private fun authorizePrelaunchExpansions(
    request: DelegatedReviewLaunchRequest,
    evidenceBroker: skillbill.ports.review.ReviewEvidenceBroker,
  ): List<ReviewEvidenceRequest> = request.prelaunchExpansions.map { expansion ->
    val authorization = evidenceBroker.authorizeExpansion(expansion)
    ReviewEvidenceRequest(
      lane = expansion.lane,
      path = expansion.path,
      reachabilityReason = expansion.reachabilityReason,
      authorizedExpansion = authorization,
    )
  }.distinctBy { it.path }

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

  private fun reject(request: DelegatedReviewLaunchRequest, reason: String): Nothing =
    throw InvalidReviewContextSchemaError(
      sourceLabel = launchLabel(request),
      reason = reason,
    )

  private fun launchLabel(request: DelegatedReviewLaunchRequest): String =
    "review-launch:${request.assignment.reviewId}:${request.assignment.lane}"
}
