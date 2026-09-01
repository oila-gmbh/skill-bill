package skillbill.application.review

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewLaunchAgentStagingRequest
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleEntry
import skillbill.review.context.model.ReviewLaneCompletionState
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal class ParallelCodeReviewRunnerLaneLaunch(
  deps: ParallelCodeReviewRunnerLaneLaunchDeps,
) {
  private val parentReviewLauncher = deps.parentReviewLauncher
  private val reviewEvidenceBrokerFactory = deps.reviewEvidenceBrokerFactory
  private val governedEvidenceEndpointBinder = deps.governedEvidenceEndpointBinder
  private val reviewLaunchAgentStaging = deps.reviewLaunchAgentStaging
  val sharedEvidenceLocatorReader = deps.sharedEvidenceLocatorReader
  private val failureHelpers = deps.failureHelpers
  private val activityStampWriter = deps.activityStampWriter

  internal fun runLanes(initial: ParallelCodeReviewInitialRun): ParallelReviewLaneRunResult {
    val request = initial.request
    val byAgent = initial.preparedLaunchRequests.groupBy { it.agentId }
    val lane1 = parallelCodeReviewCaptureLane {
      launchParentLane(
        LaunchParentLaneArgs(
          agentId = initial.agent1Id,
          launchRequests = byAgent[initial.agent1Id].orEmpty(),
          routedManifests = initial.detection.routed,
          budget = initial.budget,
          request = request,
          modelOverride = null,
          resolvedMode = initial.resolvedMode,
        ),
      )
    }
    return ParallelReviewLaneRunResult(lane1 = lane1)
  }

  private fun launchParentLane(args: LaunchParentLaneArgs): ParallelReviewLaneOutcome {
    if (args.launchRequests.isEmpty()) return parallelCodeReviewNoOpResumeOutcome(args.agentId)
    val selected = args.launchRequests.sortedBy { it.assignment.laneDecision.orderIndex }
    val bundleStates = selected.map(::parallelCodeReviewGovernedLaunchFor).map { it.completionState }
    val launch = ParallelCodeReviewInlineParentLaunch(
      agentId = args.agentId,
      selected = selected,
      prompt = ParallelCodeReviewRunnerParentPrompt.build(
        selected,
        args.routedManifests,
        args.resolvedMode,
        args.agentId,
      ),
      bundleState = parallelCodeReviewAggregateBundleCompletion(bundleStates),
    )
    return when (val bound = bindGovernedEvidence(selected, args.request)) {
      is ParallelCodeReviewGovernedEvidenceBind.Unbound -> unboundParentOutcome(launch, bound)
      is ParallelCodeReviewGovernedEvidenceBind.Bound -> launchedBoundParent(
        LaunchedBoundParentArgs(
          launch = launch,
          bound = bound,
          budget = args.budget,
          request = args.request,
          modelOverride = args.modelOverride,
          resolvedMode = args.resolvedMode,
        ),
      )
    }
  }

  private fun launchedBoundParent(args: LaunchedBoundParentArgs): ParallelReviewLaneOutcome {
    if (args.launch.agentId == "cursor" && args.resolvedMode == ResolvedReviewExecutionMode.DELEGATED) {
      reviewLaunchAgentStaging.stage(
        ReviewLaunchAgentStagingRequest(
          agentId = args.launch.agentId,
          reviewLaunchDirectory = args.bound.endpoint.descriptor.mcpConfigPath.parent,
          logicalWorkerNames = args.launch.selected
            .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
            .mapNotNull { it.logicalWorkerName }
            .distinct(),
        ),
      )
    }
    val outcome = args.bound.endpoint.use {
      parentReviewLauncher.launch(
        GoalRunnerSubtaskLaunchRequest(
          invokedAgentId = args.launch.agentId,
          configuredAgentOverrideId = null,
          skillRunRequest = SkillRunRequest(
            issueKey = "code-review",
            repoRoot = args.request.repoRoot,
            timeout = args.request.timeout,
            promptOverride = args.request.withSelectedAgentAddons(args.launch.prompt),
            modelOverride = args.modelOverride,
            conversationIsolation = ConversationIsolation.NONE,
            reviewEvidenceBroker = args.bound.broker,
            nativeReviewOperations = args.bound.protocol,
            reviewEvidenceEndpoint = args.bound.endpoint,
            nativeReviewWorkerName = PARALLEL_REVIEW_INLINE_NATIVE_WORKER
              .takeIf { args.resolvedMode == ResolvedReviewExecutionMode.INLINE },
            reviewFanOut = args.resolvedMode == ResolvedReviewExecutionMode.DELEGATED,
          ),
        ),
      )
    }
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> unsupportedParentOutcome(args.launch, outcome)
      is AgentRunLaunchFacts -> launchedParentOutcome(args.launch, outcome, args.budget, args.bound.broker)
    }
  }

  private fun bindGovernedEvidence(
    selected: List<ReviewSpecialistLaunchRequest>,
    request: ParallelCodeReviewRequest,
  ): ParallelCodeReviewGovernedEvidenceBind {
    val broker = runCatching { parentEvidenceBroker(selected, request.repoRoot) }
      .getOrElseRethrowingCancellation {
        return ParallelCodeReviewGovernedEvidenceBind.Unbound(
          ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
          ParallelCodeReviewGovernedEvidenceBindFault.CONSTRUCTION,
        )
      }
    val protocol = runCatching { BrokerBackedNativeReviewOperationProtocol(broker) }
      .getOrElseRethrowingCancellation {
        return ParallelCodeReviewGovernedEvidenceBind.Unbound(
          ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
          ParallelCodeReviewGovernedEvidenceBindFault.PROTOCOL,
        )
      }
    return runCatching {
      val onEvidenceRead = request.activityWorkflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
        {
          activityStampWriter.recordEvidenceRead(
            workflowId = workflowId,
            parentWorkflowId = request.activityParentWorkflowId,
            dbOverride = null,
          )
        }
      }
      ParallelCodeReviewGovernedEvidenceBind.Bound(
        broker,
        protocol,
        governedEvidenceEndpointBinder.bind(broker.accounting().lane, protocol, onEvidenceRead),
      )
    }.getOrElseRethrowingCancellation {
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

  fun parentEvidenceBroker(selected: List<ReviewSpecialistLaunchRequest>, repoRoot: Path): ReviewEvidenceBroker =
    reviewEvidenceBrokerFactory.brokerFor(parentBrokerBinding(selected, repoRoot))
}

private inline fun <T> Result<T>.getOrElseRethrowingCancellation(onFailure: () -> T): T {
  exceptionOrNull()?.let { if (it is CancellationException) throw it }
  return getOrElse { onFailure() }
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

internal fun ParallelCodeReviewRunnerLaneLaunch.mergedBudget(
  selected: List<ReviewSpecialistLaunchRequest>,
): ReviewContextBudgetPolicy {
  val primary = selected.minByOrNull { it.assignment.laneDecision.orderIndex } ?: selected.first()
  return primary.budget.copy(
    maxLaneEvidenceBytes = selected.sumOf { it.budget.maxLaneEvidenceBytes },
    maxSpecialistToolCalls = primary.budget.maxSpecialistToolCalls * selected.size,
    maxAssignmentExpansions = primary.budget.maxAssignmentExpansions * selected.size,
  )
}

internal fun ParallelCodeReviewRunnerLaneLaunch.mergedBundle(
  packet: ReviewContextPacket,
  assignedHunks: Set<String>,
): ReviewLaneBundle = ReviewLaneBundle(
  packet.commitUnits.sortedBy { it.orderIndex }.mapNotNull { unit ->
    unit.hunkIds.filter { it in assignedHunks }
      .takeIf { it.isNotEmpty() }
      ?.let { ReviewLaneBundleEntry(unit.commitSha, unit.orderIndex, it) }
  },
)

internal fun ParallelCodeReviewRunnerLaneLaunch.parentBrokerBinding(
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

internal fun ParallelCodeReviewRunnerLaneLaunch.brokerBinding(
  launch: ReviewSpecialistLaunchRequest,
  repoRoot: Path,
): ReviewEvidenceBrokerBinding {
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
