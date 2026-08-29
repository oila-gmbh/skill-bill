package skillbill.application.review

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewLaunchAgentStagingRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.scaffold.model.PlatformManifest
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleEntry
import skillbill.review.context.model.ReviewLaneCompletionState
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal class ParallelCodeReviewRunnerLaneLaunch(
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  private val governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  private val reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort,
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  private val failureHelpers: ParallelCodeReviewRunnerFailureHelpers,
) {
  fun runLanes(initial: ParallelCodeReviewInitialRun): ParallelReviewLaneRunResult {
    val request = initial.request
    val byAgent = initial.preparedLaunchRequests.groupBy { it.agentId }
    val lane1 = parallelCodeReviewCaptureLane {
      launchParentLane(
        initial.agent1Id,
        byAgent[initial.agent1Id].orEmpty(),
        initial.detection.routed,
        initial.budget,
        request,
        null,
        initial.resolvedMode,
      )
    }
    return ParallelReviewLaneRunResult(lane1 = lane1)
  }

  private fun launchParentLane(
    agentId: String,
    launchRequests: List<ReviewSpecialistLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    budget: ReviewContextBudgetPolicy,
    request: ParallelCodeReviewRequest,
    modelOverride: String?,
    resolvedMode: ResolvedReviewExecutionMode,
  ): ParallelReviewLaneOutcome {
    if (launchRequests.isEmpty()) return parallelCodeReviewNoOpResumeOutcome(agentId)
    val selected = launchRequests.sortedBy { it.assignment.laneDecision.orderIndex }
    val bundleStates = selected.map(::parallelCodeReviewGovernedLaunchFor).map { it.completionState }
    val launch = ParallelCodeReviewInlineParentLaunch(
      agentId = agentId,
      selected = selected,
      prompt = ParallelCodeReviewRunnerParentPrompt.build(selected, routedManifests, resolvedMode, agentId),
      bundleState = parallelCodeReviewAggregateBundleCompletion(bundleStates),
    )
    return when (val bound = bindGovernedEvidence(selected, request.repoRoot)) {
      is ParallelCodeReviewGovernedEvidenceBind.Unbound -> unboundParentOutcome(launch, bound)
      is ParallelCodeReviewGovernedEvidenceBind.Bound -> launchedBoundParent(
        launch = launch,
        bound = bound,
        budget = budget,
        request = request,
        modelOverride = modelOverride,
        resolvedMode = resolvedMode,
      )
    }
  }

  private fun launchedBoundParent(
    launch: ParallelCodeReviewInlineParentLaunch,
    bound: ParallelCodeReviewGovernedEvidenceBind.Bound,
    budget: ReviewContextBudgetPolicy,
    request: ParallelCodeReviewRequest,
    modelOverride: String?,
    resolvedMode: ResolvedReviewExecutionMode,
  ): ParallelReviewLaneOutcome {
    if (launch.agentId == "cursor" && resolvedMode == ResolvedReviewExecutionMode.DELEGATED) {
      reviewLaunchAgentStaging.stage(
        ReviewLaunchAgentStagingRequest(
          agentId = launch.agentId,
          reviewLaunchDirectory = bound.endpoint.descriptor.mcpConfigPath.parent,
          logicalWorkerNames = launch.selected
            .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
            .mapNotNull { it.logicalWorkerName }
            .distinct(),
        ),
      )
    }
    val outcome = bound.endpoint.use {
      parentReviewLauncher.launch(
        GoalRunnerSubtaskLaunchRequest(
          invokedAgentId = launch.agentId,
          configuredAgentOverrideId = null,
          skillRunRequest = SkillRunRequest(
            issueKey = "code-review",
            repoRoot = request.repoRoot,
            timeout = request.timeout,
            promptOverride = request.withSelectedAgentAddons(launch.prompt),
            modelOverride = modelOverride,
            conversationIsolation = ConversationIsolation.NONE,
            reviewEvidenceBroker = bound.broker,
            nativeReviewOperations = bound.protocol,
            reviewEvidenceEndpoint = bound.endpoint,
            nativeReviewWorkerName = PARALLEL_REVIEW_INLINE_NATIVE_WORKER
              .takeIf { resolvedMode == ResolvedReviewExecutionMode.INLINE },
            reviewFanOut = resolvedMode == ResolvedReviewExecutionMode.DELEGATED,
          ),
        ),
      )
    }
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> unsupportedParentOutcome(launch, outcome)
      is AgentRunLaunchFacts -> launchedParentOutcome(launch, outcome, budget, bound.broker)
    }
  }

  @Suppress("ThrowsCount")
  private fun bindGovernedEvidence(
    selected: List<ReviewSpecialistLaunchRequest>,
    repoRoot: Path,
  ): ParallelCodeReviewGovernedEvidenceBind {
    val broker = try {
      parentEvidenceBroker(selected, repoRoot)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      return ParallelCodeReviewGovernedEvidenceBind.Unbound(
        ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
        ParallelCodeReviewGovernedEvidenceBindFault.CONSTRUCTION,
      )
    }
    val protocol = try {
      BrokerBackedNativeReviewOperationProtocol(broker)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      return ParallelCodeReviewGovernedEvidenceBind.Unbound(
        ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
        ParallelCodeReviewGovernedEvidenceBindFault.PROTOCOL,
      )
    }
    return try {
      ParallelCodeReviewGovernedEvidenceBind.Bound(
        broker,
        protocol,
        governedEvidenceEndpointBinder.bind(broker.accounting().lane, protocol),
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      ParallelCodeReviewGovernedEvidenceBind.Unbound(
        ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
        ParallelCodeReviewGovernedEvidenceBindFault.ENDPOINT,
      )
    }
  }

  private fun unboundParentOutcome(
    launch: ParallelCodeReviewInlineParentLaunch,
    unbound: ParallelCodeReviewGovernedEvidenceBind.Unbound,
  ): ParallelReviewLaneOutcome {
    val bundleState = launch.bundleState
    return ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "governed evidence broker ${unbound.fault.wireValue} failed",
      accounting = inlineParentAccounting(launch, "unbound_broker", null, null),
      reviewDisposition = bundleState.disposition,
      bundleCompositionDigest = bundleState.bundleCompositionDigest,
      segmentAccounting = bundleState.segments,
      unreviewedSegmentIds = bundleState.unreviewedSegmentIds,
      budgetDimension = bundleState.budgetDimension,
      unreviewedUnits = bundleState.unreviewedUnits,
      unboundSeam = unbound.seam,
    )
  }

  private fun unsupportedParentOutcome(
    launch: ParallelCodeReviewInlineParentLaunch,
    outcome: UnsupportedAgentRunLaunch,
  ): ParallelReviewLaneOutcome {
    val bundleState = launch.bundleState
    return ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "unsupported agent: ${outcome.reason}",
      accounting = inlineParentAccounting(launch, UNSUPPORTED_PROVIDER_TERMINAL_STATUS, null, null),
      reviewDisposition = bundleState.disposition,
      bundleCompositionDigest = bundleState.bundleCompositionDigest,
      segmentAccounting = bundleState.segments,
      unreviewedSegmentIds = bundleState.unreviewedSegmentIds,
      budgetDimension = bundleState.budgetDimension,
      unreviewedUnits = bundleState.unreviewedUnits,
    )
  }

  private fun launchedParentOutcome(
    launch: ParallelCodeReviewInlineParentLaunch,
    outcome: AgentRunLaunchFacts,
    budget: ReviewContextBudgetPolicy,
    evidenceBroker: ReviewEvidenceBroker,
  ): ParallelReviewLaneOutcome {
    val bundleState = launch.bundleState
    val budgetOutcome = ReviewBudgetEvaluator.laneResultOutcome(
      ReviewLaneIdentity.of(launch.assignment),
      budget,
      outcome.stdout.toByteArray().size.toLong(),
    )
    val launchReason = budgetOutcome?.let { ReviewContextBudgetExceededException(it).message }
      ?: failureHelpers.laneFailureReason(outcome)
    val evidenceAccounting = evidenceBroker.accounting()
    val completion = parallelCodeReviewBrokerEvidenceCompletionState(bundleState, evidenceAccounting)
    val softAdmission = if (launchReason == null) {
      failureHelpers.softAdmitFindings(outcome.stdout, launch)
    } else {
      ParallelCodeReviewSoftRegisterAdmission(emptyList(), null, 0)
    }
    return ParallelReviewLaneOutcome(
      success = launchReason == null,
      rawOutput = outcome.stdout,
      failureReason = launchReason,
      droppedCandidateDiagnostic = softAdmission.droppedCandidateDiagnostic,
      budgetOutcome = budgetOutcome,
      accounting = inlineParentAccounting(
        launch,
        parallelCodeReviewInlineTerminalStatus(outcome, completion.disposition),
        outcome,
        evidenceAccounting,
        completion,
      ),
      findings = softAdmission.findings,
      reviewDisposition = completion.disposition,
      bundleCompositionDigest = completion.bundleCompositionDigest,
      segmentAccounting = completion.segments,
      unreviewedSegmentIds = completion.unreviewedSegmentIds,
      budgetDimension = completion.budgetDimension,
      unreviewedUnits = completion.unreviewedUnits,
      rejectedCandidateCount = softAdmission.rejectedCandidateCount,
    )
  }

  private fun parentEvidenceBroker(
    selected: List<ReviewSpecialistLaunchRequest>,
    repoRoot: Path,
  ): ReviewEvidenceBroker = reviewEvidenceBrokerFactory.brokerFor(parentBrokerBinding(selected, repoRoot))

  private fun mergedBudget(selected: List<ReviewSpecialistLaunchRequest>): ReviewContextBudgetPolicy {
    val primary = selected.minByOrNull { it.assignment.laneDecision.orderIndex } ?: selected.first()
    return primary.budget.copy(
      maxLaneEvidenceBytes = selected.sumOf { it.budget.maxLaneEvidenceBytes },
      maxSpecialistToolCalls = primary.budget.maxSpecialistToolCalls * selected.size,
      maxAssignmentExpansions = primary.budget.maxAssignmentExpansions * selected.size,
    )
  }

  private fun mergedBundle(packet: ReviewContextPacket, assignedHunks: Set<String>): ReviewLaneBundle =
    ReviewLaneBundle(
      packet.commitUnits.sortedBy { it.orderIndex }.mapNotNull { unit ->
        unit.hunkIds.filter { it in assignedHunks }
          .takeIf { it.isNotEmpty() }
          ?.let { ReviewLaneBundleEntry(unit.commitSha, unit.orderIndex, it) }
      },
    )

  private fun parentBrokerBinding(
    selected: List<ReviewSpecialistLaunchRequest>,
    repoRoot: Path,
  ): ReviewEvidenceBrokerBinding {
    val primary = selected.minByOrNull { it.assignment.laneDecision.orderIndex } ?: selected.first()
    if (selected.size == 1) return brokerBinding(primary, repoRoot)
    val assignedPaths = selected.flatMap { it.assignment.assignedPaths }.distinct()
    val assignedHunks = selected.flatMap { it.assignment.assignedHunks }.distinct()
    val expansions = selected.flatMap { it.assignment.expansions }.distinctBy { it.expansionId }
    val assigned = assignedHunks.toSet()
    val merged = primary.assignment.copy(
      laneRouting = emptyList(),
      assignedPaths = assignedPaths,
      assignedHunks = assignedHunks,
      assignedBundle = mergedBundle(primary.packet, assigned),
      evidenceTargets = selected.flatMap { it.assignment.evidenceTargets }.distinctBy { it.targetId },
      dependencyAllowlist = ReviewDependencyAllowlist(
        selected.flatMap { it.assignment.dependencyAllowlist.normalized }
          .distinct()
          .filterNot { it in assignedPaths.toSet() },
      ),
      expansions = expansions,
    )
    return ReviewEvidenceBrokerBinding(
      repoRoot = repoRoot,
      assignment = merged,
      laneRubricId = primary.rubrics.first().rubricId,
      budget = mergedBudget(selected),
      namedDependencies = selected.flatMap { it.namedDependencies }.toSet(),
      trustedExpansionLedger = expansions,
      projectedHunks = primary.packet.changedHunks.filter { it.hunkId in assigned },
      locatorReader = sharedEvidenceLocatorReader,
      bodyExtractor = ReviewLocatorHunkBodyExtractor,
    )
  }

  private fun brokerBinding(launch: ReviewSpecialistLaunchRequest, repoRoot: Path): ReviewEvidenceBrokerBinding {
    val assigned = launch.assignment.assignedHunks.toSet()
    return ReviewEvidenceBrokerBinding(
      repoRoot = repoRoot,
      assignment = launch.assignment,
      laneRubricId = launch.rubrics.first().rubricId,
      budget = launch.budget,
      namedDependencies = launch.namedDependencies,
      trustedExpansionLedger = launch.assignment.expansions,
      projectedHunks = launch.packet.changedHunks.filter { it.hunkId in assigned },
      locatorReader = sharedEvidenceLocatorReader,
      bodyExtractor = ReviewLocatorHunkBodyExtractor,
    )
  }
}

private fun inlineParentAccounting(
  launch: ParallelCodeReviewInlineParentLaunch,
  terminalStatus: String,
  outcome: AgentRunLaunchFacts?,
  brokerAccounting: ReviewLaneAccounting?,
  completionState: ReviewLaneCompletionState = launch.bundleState,
) = ReviewLaneAccounting(
  lane = launch.agentId,
  reviewId = launch.assignment.reviewId,
  packetDigest = launch.assignment.packetDigest,
  assignmentDigest = launch.assignment.digest,
  launchBytes = launch.prompt.toByteArray(Charsets.UTF_8).size.toLong(),
  authorizedReadCount = brokerAccounting?.authorizedReadCount ?: 0,
  refusedOperationCount = brokerAccounting?.refusedOperationCount ?: 0,
  refusals = brokerAccounting?.refusals.orEmpty(),
  evidenceBytes = brokerAccounting?.evidenceBytes ?: 0,
  expansions = brokerAccounting?.expansions.orEmpty(),
  toolCalls = brokerAccounting?.toolCalls ?: 0,
  modelTurns = 1,
  resultBytes = outcome?.stdout?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0,
  terminalStatus = terminalStatus,
  terminalOutcome = brokerAccounting?.terminalOutcome,
  reviewDisposition = completionState.disposition,
  bundleCompositionDigest = completionState.bundleCompositionDigest,
  segmentAccounting = completionState.segments,
  unreviewedSegmentIds = completionState.unreviewedSegmentIds,
  budgetDimension = completionState.budgetDimension,
  unreviewedUnits = completionState.unreviewedUnits,
)
