package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.application.review.model.DelegatedReviewExecutionOutcome
import skillbill.application.review.model.DelegatedReviewExecutionRequest
import skillbill.application.review.model.DelegatedReviewLaunchRequest
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.workflow.repoRoot
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTokenOwnership
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.review.InstalledReviewCatalogPort
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewDeclaredSpecialistProgress
import skillbill.ports.review.model.ReviewDiagnosticReference
import skillbill.ports.review.model.ReviewDurableWorkerProgress
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleLedger
import skillbill.ports.review.model.ReviewLivenessObservation
import skillbill.ports.review.model.ReviewNativeAgentAssignment
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewProviderOutputObservation
import skillbill.ports.review.model.ReviewTerminalCompletion
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import skillbill.ports.review.model.ReviewWorkerResultEnvelope
import skillbill.ports.review.model.DelegatedReviewDeadline
import skillbill.ports.review.model.DelegatedReviewDeadlineScope
import skillbill.ports.review.model.DelegatedReviewLifecycleMetrics
import skillbill.ports.review.model.DelegatedReviewLifecycleSnapshot
import skillbill.ports.review.model.DelegatedReviewTerminalClassification
import skillbill.ports.review.model.DelegatedReviewWaveRecord
import skillbill.ports.review.model.DelegatedReviewWorkerRecord
import skillbill.ports.review.model.DelegatedReviewWorkerState
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.TokenOwnership
import skillbill.review.context.model.structuredString
import skillbill.review.context.model.toCodeReviewExecutionMode
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.plan.DelegatedReviewCapacityPlanner
import skillbill.review.plan.DelegatedReviewCapacityRequest
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Inject
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class ParallelCodeReviewRunner(
  private val delegatedReviewExecutionBroker: DelegatedReviewExecutionBroker,
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val diffResolver: DiffResolverPort,
  private val parallelLaneRunner: ParallelReviewLaneRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewRubricResolver: ReviewRubricResolver,
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  private val nativeAgentPreflight: ReviewNativeAgentPreflightPort,
  private val database: DatabaseSessionFactory,
  private val installedReviewCatalog: InstalledReviewCatalogPort = InstalledReviewCatalogPort.NONE,
) {
  private val lifecycleRecorder = ReviewLifecycleRecorder(database)
  private val lifecycleRecovery = ReviewLifecycleRecovery(database)

  private data class InitialRun(
    val request: ParallelCodeReviewRequest,
    val detection: StackDetection,
    val resolvedMode: ResolvedReviewExecutionMode,
    val agent1Id: String,
    val agent2Id: String,
    val preparedLaunchRequests: List<DelegatedReviewLaunchRequest>,
  )

  private data class PreparedRun(
    val initial: InitialRun,
    val launchRequests: List<DelegatedReviewLaunchRequest>,
    val relaunchableRequests: List<DelegatedReviewLaunchRequest>,
    val lifecycleReview: DelegatedReviewLaunchRequest?,
    val recovery: ReviewLifecycleRecoverySnapshot?,
  )

  fun run(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    val prepared = prepareRun(originalRequest)
    val outcomes = executePreparedRun(prepared)
    recordAggregationStarted(prepared)
    return withFailureRecording(
      onFailure = { prepared.lifecycleReview?.let(::recordCoordinatorCrash) },
      block = { finalizePreparedRun(prepared, outcomes) },
    )
  }

  private fun prepareRun(originalRequest: ParallelCodeReviewRequest): PreparedRun {
    val initial = prepareInitialRun(originalRequest)
    val lifecycleReview = initial.preparedLaunchRequests.firstOrNull()?.takeIf {
      initial.resolvedMode == ResolvedReviewExecutionMode.DELEGATED
    }
    val recovery = lifecycleReview?.let {
      lifecycleRecovery.read(it.assignment.reviewId, workerIdentities(initial.preparedLaunchRequests))
    }
    val launchRequests = initial.preparedLaunchRequests.map { launch ->
      launch.copy(attempt = recovery?.attemptFor(launch.assignment.digest) ?: 1)
    }
    lifecycleReview?.let { recordPreparedLifecycle(it, launchRequests) }
    return PreparedRun(
      initial = initial,
      launchRequests = launchRequests,
      relaunchableRequests = launchRequests.filter { recovery?.shouldLaunch(it.assignment.digest) ?: true },
      lifecycleReview = lifecycleReview,
      recovery = recovery,
    )
  }

  private fun prepareInitialRun(originalRequest: ParallelCodeReviewRequest): InitialRun {
    val agent1 = resolveAgent(originalRequest.agent1Id, "--agent1")
    val agent2 = resolveAgent(originalRequest.agent2Id, "--agent2")
    if (agent1.id == agent2.id) {
      throw UsageValidationException(
        "agent1 and agent2 must be different agents; both resolved to '${agent1.id}'.",
      )
    }
    val diffText = resolveDiff(originalRequest)
    val evidence = ReviewDiffEvidence.parse(diffText)
    val detection = detectStack(evidence, originalRequest.repoRoot)
    val budget = repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(originalRequest.repoRoot))
      .config.reviewContextBudget
    val lane1ResolvedMode = resolvedMode(originalRequest)
    // Pin lane 1's depth onto the request before either lane starts, so lane 2 inherits it and a
    // mixed-tier pairing is rejected by the request's own invariant rather than by convention.
    val request = originalRequest.withResolvedTier(lane1ResolvedMode.toCodeReviewExecutionMode())
    val resolvedMode = ReviewExecutionModePolicy.resolve(request.lane2Tier)
    return InitialRun(
      request = request,
      detection = detection,
      resolvedMode = resolvedMode,
      agent1Id = agent1.id,
      agent2Id = agent2.id,
      preparedLaunchRequests = prepare(
        request,
        diffText,
        evidence,
        detection.routed,
        detection.manifests,
        detection.ownedPathsBySlug,
        listOf(agent1.id, agent2.id),
        budget,
      ),
    )
  }

  private fun workerIdentities(launches: List<DelegatedReviewLaunchRequest>) = launches.associate { selected ->
    selected.assignment.digest to ReviewLifecycleWorkerIdentity(
      workerId = selected.assignment.lane,
      providerId = selected.agentId,
    )
  }

  private fun recordPreparedLifecycle(
    lifecycleReview: DelegatedReviewLaunchRequest,
    launchRequests: List<DelegatedReviewLaunchRequest>,
  ) {
    lifecycleRecorder.record(
      ReviewLifecycleRecord(
        reviewId = lifecycleReview.assignment.reviewId,
        packetDigest = lifecycleReview.assignment.packetDigest,
        component = ReviewLifecycleComponent.COORDINATOR,
        eventKind = ReviewLifecycleEventKind.COORDINATOR_PREPARED,
        processOutcome = ReviewProcessOutcome.NOT_STARTED,
      ),
    )
    launchRequests.forEach { selected ->
      recordWorkerLifecycle(
        selected,
        ReviewLifecycleEventKind.WORKER_SELECTED,
        ReviewWorkerLifecycleState.SELECTED,
      )
      recordWorkerLifecycle(
        selected,
        ReviewLifecycleEventKind.WORKER_QUEUED,
        ReviewWorkerLifecycleState.QUEUED,
      )
    }
  }

  private fun executePreparedRun(prepared: PreparedRun): ParallelReviewLaneRunResult {
    withFailureRecording(
      onFailure = {
        prepared.lifecycleReview?.let { recordPreflightFailure(it, prepared.relaunchableRequests) }
      },
      block = {
        preflightDelegatedWorkers(
          prepared.initial.request,
          prepared.initial.resolvedMode,
          prepared.relaunchableRequests,
        )
      },
    )
    val launchedOutcomes = withFailureRecording(
      onFailure = { prepared.lifecycleReview?.let(::recordCoordinatorCrash) },
      block = {
        runLanes(
          prepared.initial.request,
          prepared.initial.detection.routed,
          prepared.initial.resolvedMode,
          prepared.relaunchableRequests.groupBy { it.agentId },
          prepared.initial.agent1Id,
          prepared.initial.agent2Id,
        )
      },
    )
    return prepared.recovery?.let { recovery ->
      rebuildRecoveredOutcomes(
        prepared.launchRequests,
        prepared.relaunchableRequests,
        recovery,
        launchedOutcomes,
        prepared.initial.agent1Id,
        prepared.initial.agent2Id,
      )
    } ?: launchedOutcomes
  }

  private fun recordAggregationStarted(prepared: PreparedRun) {
    prepared.lifecycleReview?.takeUnless { prepared.recovery?.aggregationEvent != null }?.let { launch ->
      lifecycleRecorder.record(
        ReviewLifecycleRecord(
          reviewId = launch.assignment.reviewId,
          packetDigest = launch.assignment.packetDigest,
          component = ReviewLifecycleComponent.AGGREGATION,
          eventKind = ReviewLifecycleEventKind.AGGREGATION_STARTED,
          processOutcome = ReviewProcessOutcome.NOT_STARTED,
        ),
      )
    }
  }

  private fun finalizePreparedRun(
    prepared: PreparedRun,
    outcomes: ParallelReviewLaneRunResult,
  ): ParallelCodeReviewResult {
    val result = parallelResult(prepared.initial.agent1Id, prepared.initial.agent2Id, outcomes)
    result.accountingSummary?.let { summary ->
      database.transaction { unitOfWork ->
        unitOfWork.reviews.saveAccounting(
          ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary.toBoundedPayload()),
        )
      }
    }
    prepared.lifecycleReview?.let {
      recordReviewCompletion(
        it,
        prepared.recovery,
        outcomes,
        prepared.launchRequests.map { launch -> launch.assignment.digest }.toSet(),
      )
    }
    persistLifecycleProjection(prepared, outcomes)
    return result
  }

  private fun persistLifecycleProjection(
    prepared: PreparedRun,
    outcomes: ParallelReviewLaneRunResult,
  ) {
    val lifecycleReview = prepared.lifecycleReview ?: return
    val selected = prepared.launchRequests
    if (selected.isEmpty()) return
    val plan = DelegatedReviewCapacityPlanner.plan(
      DelegatedReviewCapacityRequest(
        selectedWorkerIds = selected.map { it.assignment.digest },
        totalProcessSlots = DELEGATED_REVIEW_PROCESS_SLOTS,
      ),
    )
    val lifecycleEvents = database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(lifecycleReview.assignment.reviewId)
    }
    val aggregationCompleted = lifecycleEvents.any {
      it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED
    }
    val latestByAssignment = lifecycleEvents
      .filter { it.assignmentDigest != null }
      .groupBy { it.assignmentDigest }
      .mapValues { (_, events) -> events.maxWithOrNull(compareBy({ it.attempt ?: 0 }, { it.sequence })) }
    val allWorkers = selected.map { request ->
      val outcome = if (request.agentId == prepared.initial.agent1Id) outcomes.lane1 else outcomes.lane2
      val state = when (latestByAssignment[request.assignment.digest]?.state) {
        ReviewWorkerLifecycleState.SELECTED -> DelegatedReviewWorkerState.SELECTED
        ReviewWorkerLifecycleState.QUEUED -> DelegatedReviewWorkerState.QUEUED
        ReviewWorkerLifecycleState.LAUNCHED -> DelegatedReviewWorkerState.LAUNCHED
        ReviewWorkerLifecycleState.RUNNING -> DelegatedReviewWorkerState.RUNNING
        ReviewWorkerLifecycleState.COMPLETED -> DelegatedReviewWorkerState.COMPLETED
        ReviewWorkerLifecycleState.TIMED_OUT -> DelegatedReviewWorkerState.TIMED_OUT
        ReviewWorkerLifecycleState.CANCELLED -> DelegatedReviewWorkerState.CANCELLED
        ReviewWorkerLifecycleState.FAILED,
        ReviewWorkerLifecycleState.UNAVAILABLE,
        ReviewWorkerLifecycleState.INVALID_OUTPUT,
        null -> if (outcome.success) DelegatedReviewWorkerState.COMPLETED else DelegatedReviewWorkerState.FAILED
      }
      val projectedState = if (aggregationCompleted && state == DelegatedReviewWorkerState.COMPLETED) {
        DelegatedReviewWorkerState.AGGREGATED
      } else {
        state
      }
      val diagnostic = latestByAssignment[request.assignment.digest]?.diagnostic?.summary ?: when (projectedState) {
        DelegatedReviewWorkerState.FAILED -> "Worker failed; durable lifecycle evidence is authoritative."
        DelegatedReviewWorkerState.TIMED_OUT -> "Worker deadline expired; durable lifecycle evidence is authoritative."
        DelegatedReviewWorkerState.CANCELLED -> "Worker was cancelled; durable lifecycle evidence is authoritative."
        else -> null
      }
      DelegatedReviewWorkerRecord(
        workerId = "${request.agentId}:${request.assignment.lane}",
        providerId = request.agentId,
        assignmentDigest = request.assignment.digest,
        attempt = request.attempt,
        area = request.assignment.laneDecision.specialistSkillName ?: request.assignment.lane,
        state = projectedState,
        diagnostic = diagnostic?.take(REVIEW_LIFECYCLE_MAX_TEXT_CHARS),
      )
    }
    val predictedWaves = plan.predictedWaves.map { wave ->
      DelegatedReviewWaveRecord(
        waveNumber = wave.number,
        workerIds = wave.workerIds.map { digest ->
          selected.first { it.assignment.digest == digest }.let { request ->
            "${request.agentId}:${request.assignment.lane}"
          }
        },
      )
    }
    val activeWorkerIds = allWorkers.filter { it.state !in setOf(
      DelegatedReviewWorkerState.SELECTED,
      DelegatedReviewWorkerState.QUEUED,
    ) }.map { it.workerId }.toSet()
    val waves = predictedWaves.filter { wave -> wave.workerIds.any(activeWorkerIds::contains) }
    val lifecycleTimes = lifecycleEvents.mapNotNull { event ->
      runCatching { Instant.parse(event.occurredAt).toEpochMilli() }.getOrNull()
    }
    val elapsedMs = lifecycleTimes.minOrNull()?.let { start ->
      lifecycleTimes.maxOrNull()?.minus(start)
    } ?: 0L
    val terminalClassification = when {
      allWorkers.all {
        it.state == DelegatedReviewWorkerState.COMPLETED ||
          it.state == DelegatedReviewWorkerState.AGGREGATED
      } ->
        DelegatedReviewTerminalClassification.COMPLETED
      allWorkers.any { it.state == DelegatedReviewWorkerState.TIMED_OUT } ->
        DelegatedReviewTerminalClassification.TIMED_OUT
      allWorkers.any { it.state == DelegatedReviewWorkerState.CANCELLED } ->
        DelegatedReviewTerminalClassification.CANCELLED
      outcomes.lane1.interrupted || outcomes.lane2.interrupted ->
        DelegatedReviewTerminalClassification.INTERRUPTED_DURING_WORKER
      else -> DelegatedReviewTerminalClassification.FAILED
    }
    val deadline = prepared.initial.request.timeout?.inWholeMilliseconds?.let {
      DelegatedReviewDeadline(DelegatedReviewDeadlineScope.WHOLE_REVIEW, it)
    }
    val snapshot = DelegatedReviewLifecycleSnapshot(
      reviewId = lifecycleReview.assignment.reviewId,
      packetDigest = lifecycleReview.assignment.packetDigest,
      selectedAreaCount = selected.size,
      predictedWaveCount = plan.predictedWaveCount,
      actualWaveCount = waves.size,
      coordinatorSlots = 1,
      workers = allWorkers,
      waves = waves,
      deadlines = listOfNotNull(deadline),
      metrics = DelegatedReviewLifecycleMetrics(
        elapsedMs = elapsedMs,
        totalTokens = listOf(outcomes.lane1, outcomes.lane2).flatMap { it.specialistAccounting }
          .sumOf { it.providerUsage?.totalTokens ?: 0L },
        processCount = activeWorkerIds.size,
        mcpStartupCount = lifecycleEvents
          .flatMap { it.livenessObservations }
          .count { it.kind == ReviewLivenessObservation.Kind.MCP_HEARTBEAT },
        selectedAreaCount = selected.size,
        completedAreaCount = allWorkers.count {
          it.state == DelegatedReviewWorkerState.COMPLETED ||
            it.state == DelegatedReviewWorkerState.AGGREGATED
        },
        lostWorkerCount = allWorkers.count {
          it.state != DelegatedReviewWorkerState.COMPLETED &&
            it.state != DelegatedReviewWorkerState.AGGREGATED
        },
      ),
      terminalClassification = terminalClassification,
    )
    database.transaction { unitOfWork ->
      unitOfWork.reviews.saveDelegatedReviewLifecycle(snapshot)
    }
  }

  private fun recordReviewCompletion(
    launch: DelegatedReviewLaunchRequest,
    recovery: ReviewLifecycleRecoverySnapshot?,
    outcomes: ParallelReviewLaneRunResult,
    selectedAssignmentDigests: Set<String>,
  ) {
    val durableAggregationReady = database.read { unitOfWork ->
      ReviewLifecycleLedger(unitOfWork.reviews.loadReviewLifecycleEvents(launch.assignment.reviewId))
        .canAggregate(selectedAssignmentDigests)
    }
    val successful = outcomes.lane1.success && outcomes.lane2.success && durableAggregationReady
    if (recovery?.aggregationEvent == null) recordAggregationCompletion(launch, successful)
    if (recovery?.terminalRecord == null) recordTerminalCompletion(launch, recovery, successful)
  }

  private fun recordAggregationCompletion(launch: DelegatedReviewLaunchRequest, successful: Boolean) {
    lifecycleRecorder.record(
      ReviewLifecycleRecord(
        reviewId = launch.assignment.reviewId,
        packetDigest = launch.assignment.packetDigest,
        component = ReviewLifecycleComponent.AGGREGATION,
        eventKind = if (successful) {
          ReviewLifecycleEventKind.AGGREGATION_COMPLETED
        } else {
          ReviewLifecycleEventKind.AGGREGATION_FAILED
        },
        processOutcome = if (successful) ReviewProcessOutcome.ZERO_EXIT else ReviewProcessOutcome.AGGREGATION_FAILURE,
        terminalCompletion = ReviewTerminalCompletion(
          lifecycleRecorder.timestamp(),
          ReviewProcessOutcome.ZERO_EXIT,
        ).takeIf { successful },
      ),
    )
  }

  private fun recordTerminalCompletion(
    launch: DelegatedReviewLaunchRequest,
    recovery: ReviewLifecycleRecoverySnapshot?,
    successful: Boolean,
  ) {
    val terminalCompletion = terminalCompletionFor(recovery, successful)
    lifecycleRecorder.record(
      ReviewLifecycleRecord(
        reviewId = launch.assignment.reviewId,
        packetDigest = launch.assignment.packetDigest,
        component = ReviewLifecycleComponent.TERMINAL,
        eventKind = if (terminalCompletion.status == ReviewProcessOutcome.ZERO_EXIT) {
          ReviewLifecycleEventKind.TERMINAL_COMPLETED
        } else {
          ReviewLifecycleEventKind.TERMINAL_FAILED
        },
        processOutcome = terminalCompletion.status,
        terminalCompletion = terminalCompletion,
      ),
    )
  }

  private inline fun <T> withFailureRecording(onFailure: () -> Unit, block: () -> T): T =
    runCatching(block).getOrElse { error ->
      onFailure()
      throw error
    }

  private fun recordCoordinatorCrash(launch: DelegatedReviewLaunchRequest) {
    lifecycleRecorder.record(
      ReviewLifecycleRecord(
        reviewId = launch.assignment.reviewId,
        packetDigest = launch.assignment.packetDigest,
        component = ReviewLifecycleComponent.COORDINATOR,
        eventKind = ReviewLifecycleEventKind.COORDINATOR_CRASHED,
        processOutcome = ReviewProcessOutcome.COORDINATOR_CRASH,
        diagnostic = ReviewDiagnosticReference(
          "review-lifecycle/${launch.assignment.reviewId}",
          "Coordinator failed before terminal persistence.",
        ),
      ),
    )
  }

  private fun recordPreflightFailure(
    launch: DelegatedReviewLaunchRequest,
    affectedAssignments: List<DelegatedReviewLaunchRequest>,
  ) {
    affectedAssignments.forEach { selected ->
      recordWorkerLifecycle(
        selected,
        ReviewLifecycleEventKind.WORKER_UNAVAILABLE,
        ReviewWorkerLifecycleState.UNAVAILABLE,
        ReviewProcessOutcome.UNAVAILABLE,
        ReviewDiagnosticReference(
          "review-lifecycle/${selected.assignment.reviewId}/" +
            selected.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH),
          "Worker preflight failed before provider launch.",
        ),
      )
    }
    lifecycleRecorder.record(
      ReviewLifecycleRecord(
        reviewId = launch.assignment.reviewId,
        packetDigest = launch.assignment.packetDigest,
        component = ReviewLifecycleComponent.COORDINATOR,
        eventKind = ReviewLifecycleEventKind.COORDINATOR_CRASHED,
        processOutcome = ReviewProcessOutcome.COORDINATOR_CRASH,
        diagnostic = ReviewDiagnosticReference(
          "review-lifecycle/${launch.assignment.reviewId}",
          "Coordinator failed during delegated-worker preflight.",
        ),
      ),
    )
    lifecycleRecorder.record(
      ReviewLifecycleRecord(
        reviewId = launch.assignment.reviewId,
        packetDigest = launch.assignment.packetDigest,
        component = ReviewLifecycleComponent.TERMINAL,
        eventKind = ReviewLifecycleEventKind.TERMINAL_FAILED,
        processOutcome = ReviewProcessOutcome.COORDINATOR_CRASH,
        terminalCompletion = ReviewTerminalCompletion(
          lifecycleRecorder.timestamp(),
          ReviewProcessOutcome.COORDINATOR_CRASH,
        ),
        diagnostic = ReviewDiagnosticReference(
          "review-lifecycle/${launch.assignment.reviewId}",
          "Delegated review stopped before terminal success.",
        ),
      ),
    )
  }

  /**
   * Isolation and launch-boundary preflight only applies to workers this run will actually start in
   * isolated conversations; inline mode runs everything in the parent's own session and must not be
   * rejected for an agent that simply has no specialist isolation strategy.
   */
  private fun preflightDelegatedWorkers(
    request: ParallelCodeReviewRequest,
    resolvedMode: ResolvedReviewExecutionMode,
    launchRequests: List<DelegatedReviewLaunchRequest>,
  ) {
    if (resolvedMode != ResolvedReviewExecutionMode.DELEGATED) return
    val providerNativeAssignments = launchRequests
      .filter { it.workerKind == skillbill.application.review.model.ReviewWorkerKind.PROVIDER_NATIVE }
      .mapNotNull { launch ->
        launch.logicalWorkerName?.let { ReviewNativeAgentAssignment(launch.agentId, it) }
      }
      .distinct()
    if (providerNativeAssignments.isNotEmpty()) {
      nativeAgentPreflight.verify(
        ReviewNativeAgentPreflightRequest(
          repoRoot = request.repoRoot,
          assignments = providerNativeAssignments,
        ),
      )
    }
    delegatedReviewExecutionBroker.preflight(launchRequests)
  }

  private fun resolvedMode(request: ParallelCodeReviewRequest) = ReviewExecutionModePolicy.resolveWithRule(
    // A pinned resolvedTier is lane 1's already-decided depth; honoring it here is what makes both
    // lanes share one tier instead of each re-resolving auto independently.
    requested = request.resolvedTier ?: request.codeReviewMode,
  ).resolvedMode

  private fun runLanes(
    request: ParallelCodeReviewRequest,
    routedManifests: List<PlatformManifest>,
    resolvedMode: ResolvedReviewExecutionMode,
    prepared: Map<String, List<DelegatedReviewLaunchRequest>>,
    agent1Id: String,
    agent2Id: String,
  ): skillbill.ports.review.model.ParallelReviewLaneRunResult {
    val timeoutSec = request.timeout?.inWholeSeconds ?: DEFAULT_TIMEOUT_MINUTES * SECONDS_PER_MINUTE
    return parallelLaneRunner.runTwoLanes(
      ParallelReviewLaneRunRequest(
        lane1 = {
          launchResolvedLane(
            resolvedMode,
            prepared[agent1Id].orEmpty(),
            agent1Id,
            routedManifests,
            request,
          )
        },
        lane2 = {
          launchResolvedLane(
            resolvedMode,
            prepared[agent2Id].orEmpty(),
            agent2Id,
            routedManifests,
            request,
            request.agent2Model,
          )
        },
        timeout = (timeoutSec + TIMEOUT_BUFFER_SECONDS).seconds,
      ),
    )
  }

  private fun terminalCompletionFor(
    recovery: ReviewLifecycleRecoverySnapshot?,
    successful: Boolean,
  ): ReviewTerminalCompletion {
    val persistedAggregation = recovery?.aggregationEvent
    if (persistedAggregation?.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED) {
      return requireNotNull(persistedAggregation.terminalCompletion) {
        "A durable aggregation completion must carry its terminal completion evidence."
      }
    }
    val status = if (successful) ReviewProcessOutcome.ZERO_EXIT else ReviewProcessOutcome.AGGREGATION_FAILURE
    return skillbill.ports.review.model.ReviewTerminalCompletion(lifecycleRecorder.timestamp(), status)
  }

  private fun rebuildRecoveredOutcomes(
    launchRequests: List<DelegatedReviewLaunchRequest>,
    relaunchableRequests: List<DelegatedReviewLaunchRequest>,
    recovery: ReviewLifecycleRecoverySnapshot,
    launchedOutcomes: skillbill.ports.review.model.ParallelReviewLaneRunResult,
    agent1Id: String,
    agent2Id: String,
  ): skillbill.ports.review.model.ParallelReviewLaneRunResult {
    val relaunchableDigests = relaunchableRequests.map { it.assignment.digest }.toSet()

    fun rebuild(
      agentId: String,
      launched: skillbill.ports.review.model.ParallelReviewLaneOutcome,
    ): skillbill.ports.review.model.ParallelReviewLaneOutcome {
      if (
        recovery.aggregationEvent?.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED ||
        recovery.terminalRecord?.eventKind == ReviewLifecycleEventKind.TERMINAL_FAILED
      ) {
        return launched
      }
      val selected = launchRequests.filter { it.agentId == agentId }
      val recovered = selected
        .filterNot { it.assignment.digest in relaunchableDigests }
        .mapNotNull { launch ->
          recovery.completedResults[launch.assignment.digest]
            ?.takeIf { result ->
              result.workerId == launch.assignment.lane && result.providerId == launch.agentId
            }
        }
      if (recovered.isEmpty()) return launched
      val allSelectedRecovered = selected.size == recovered.size &&
        selected.all { it.assignment.digest !in relaunchableDigests }
      return launched.copy(
        success = if (allSelectedRecovered) true else launched.success,
        failureReason = if (allSelectedRecovered) null else launched.failureReason,
        findings = recovered.flatMap { it.resultEnvelope.findings } + launched.findings,
      )
    }

    return skillbill.ports.review.model.ParallelReviewLaneRunResult(
      lane1 = rebuild(agent1Id, launchedOutcomes.lane1),
      lane2 = rebuild(agent2Id, launchedOutcomes.lane2),
    )
  }

  @Suppress("LongMethod")
  private fun prepare(
    request: ParallelCodeReviewRequest,
    diffText: String,
    evidence: ReviewDiffEvidence,
    routedManifests: List<PlatformManifest>,
    manifests: List<PlatformManifest>,
    ownedPathsBySlug: Map<String, Set<String>>,
    agentIds: List<String>,
    budget: skillbill.review.context.model.ReviewContextBudgetPolicy,
  ): List<DelegatedReviewLaunchRequest> {
    val plannedRubrics = resolvePlannedRubrics(evidence, routedManifests, manifests, ownedPathsBySlug)
    val (baseRevision, headRevision) = resolveReviewRevisions(request)
    return ParallelReviewPreparationCompiler.compile(
      input = ParallelReviewPreparationInput(
        diff = diffText,
        evidence = evidence,
        stack = routedManifests.joinToString("+") { it.slug }.ifBlank { null },
        agents = agentIds,
        repoRoot = request.repoRoot,
        routedPacks = routedManifests.map { it.slug },
        lanes = plannedRubrics,
        reviewRunId = request.reviewRunId,
        baseRevision = baseRevision,
        headRevision = headRevision,
        prelaunchExpansions = request.prelaunchExpansions,
        baselineUntrackedPolicy = request.baselineUntrackedPolicy,
      ),
      budget = budget,
      envelopeValidator = reviewContextEnvelopeValidator,
      specialistContract = reviewSpecialistContractProvider.authoritativeContract(),
    )
  }

  private fun resolveReviewRevisions(request: ParallelCodeReviewRequest): Pair<String, String> {
    val head = request.headRevision ?: runDiff(listOf("git", "rev-parse", "HEAD"), request.repoRoot).trim()
    val base = request.baseRevision ?: when (request.scope) {
      ParallelReviewScope.BRANCH -> detectBranchBase(request.repoRoot)
      else -> head
    }
    if (base.isBlank() || head.isBlank()) {
      throw DiffResolutionException("Review base and head revisions must resolve to non-blank immutable identities.")
    }
    return base to head
  }

  private fun horizontalPlannedRubrics(evidence: ReviewDiffEvidence): List<PlannedReviewRubric> {
    val rubric = reviewRubricResolver.resolve(null)
    return listOf(
      PlannedReviewRubric(
        ReviewLaunchLane(
          rubric.rubricId,
          "horizontal",
          rubric.area ?: "generic",
          0,
          listOf("horizontal"),
          true,
          emptyList(),
          0,
          "horizontal base behavior",
          ownedPaths = evidence.hunks.map { it.path }.distinct().sorted(),
          changedHunkIds = evidence.hunks.map { it.hunkId },
        ),
        ReviewRubricProjection(rubric.rubricId, rubric.body, rubric.area),
        workerKind = skillbill.application.review.model.ReviewWorkerKind.GENERIC,
      ),
    )
  }

  @Suppress("LongMethod")
  private fun resolvePlannedRubrics(
    evidence: ReviewDiffEvidence,
    routedManifests: List<PlatformManifest>,
    manifests: List<PlatformManifest>,
    ownedPathsBySlug: Map<String, Set<String>>,
  ): List<PlannedReviewRubric> = if (routedManifests.isEmpty()) {
    val installed = installedReviewCatalog.manifests()
    if (installed.isNotEmpty()) {
      val routing = ReviewStackRouting.route(
        installed,
        evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) },
      )
      if (routing.routedSlugs.isEmpty()) {
        horizontalPlannedRubrics(evidence)
      } else {
        resolvePlannedRubrics(
          evidence,
          installed.filter { it.slug in routing.routedSlugs },
          installed,
          routing.ownedPathsBySlug,
        )
      }
    } else {
      horizontalPlannedRubrics(evidence)
    }
  } else {
    val selectedAreas = manifests.flatMap { it.declaredCodeReviewAreas }.toSet()
    // Each root pack only owns the files that actually routed to it; a required baseline lane
    // must claim exactly that root's routed files, never every changed file across the whole
    // (possibly cross-stack) diff, or a Kotlin required specialist would also claim Python files.
    val flattened = routedManifests.flatMap { root ->
      val rootOwnedPaths = ownedPathsBySlug[root.slug].orEmpty()
      val rootFiles = evidence.files.filter { it.path in rootOwnedPaths }
      ReviewLaunchPlanPolicy.flatten(root.slug, manifests, selectedAreas).lanes.also { lanes ->
        require(lanes.isNotEmpty()) {
          "Routed pack '${root.slug}' resolved no declared flattened specialist worker."
        }
      }.map { lane ->
        val ownedPaths = if (lane.required) rootOwnedPaths.toList() else laneOwnedPaths(lane, rootFiles)
        lane.copy(
          ownedPaths = ownedPaths.distinct().sorted(),
          changedHunkIds = evidence.hunks.filter { it.path in ownedPaths }.map { it.hunkId },
        )
      }
    }
    flattened
      .filter { lane -> lane.ownedPaths.isNotEmpty() }
      .groupBy { it.skillName }
      .values
      .mapIndexed { index, matches ->
        val first = matches.first()
        val lane = first.copy(
          orderIndex = index,
          required = matches.any { it.required },
          ownedPaths = matches.flatMap { it.ownedPaths }.distinct().sorted(),
          changedHunkIds = matches.flatMap { it.changedHunkIds }.distinct(),
        )
        require(
          matches.all {
            it.packSlug == lane.packSlug && it.area == lane.area && it.skillName == lane.skillName &&
              it.addOns == lane.addOns
          },
        ) {
          "Conflicting ownership for specialist '${lane.skillName}'."
        }
        val owner = manifests.single { it.slug == lane.packSlug }
        val ownedEvidence = evidence.ownedFiles(lane.ownedPaths.toSet()).map {
          ReviewOwnedFileEvidence(it.path, it.changedContent)
        }
        val resolvedOwner = reviewRubricResolver.resolve(owner, ownedEvidence, lane.skillName)
        val resolved = resolvedOwner
          .specialists.singleOrNull { it.area == lane.area }
          ?: resolvedOwner
        PlannedReviewRubric(
          descriptor = lane.copy(addOns = resolved.selectedAddOns),
          rubric = ReviewRubricProjection(lane.skillName, resolved.body, resolved.area ?: lane.area),
          originLayerChains = matches.flatMap { it.originLayerChains }.distinct(),
        )
      }
  }

  private fun resolveAgent(agentId: String, label: String): InstallAgent {
    if (agentId.isBlank()) {
      throw UsageValidationException(
        "Option $label is required. Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
      )
    }
    return try {
      InstallAgent.fromNormalizedId(agentId, label = label)
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
      throw UsageValidationException(
        "Unsupported agent '$agentId' for $label. Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
      )
    }
  }

  private fun resolveDiff(request: ParallelCodeReviewRequest): String {
    val diffText = request.suppliedDiff ?: request.suppliedDiffPath?.let { path ->
      diffResolver.readDiff(path, MAX_SUPPLIED_DIFF_BYTES)
        ?: throw DiffResolutionException(
          "--diff-file must name a readable, non-empty regular file no larger than $MAX_SUPPLIED_DIFF_BYTES bytes.",
        )
    } ?: when (request.scope) {
      ParallelReviewScope.STAGED -> runDiff(listOf("git", "diff", "--cached"), request.repoRoot)
      ParallelReviewScope.UNSTAGED -> runDiff(listOf("git", "diff"), request.repoRoot)
      ParallelReviewScope.BRANCH -> {
        val base = detectBranchBase(request.repoRoot)
        runDiff(listOf("git", "diff", "$base...HEAD"), request.repoRoot)
      }
      ParallelReviewScope.PR -> runDiff(listOf("gh", "pr", "diff"), request.repoRoot)
    }
    if (diffText.isBlank()) {
      throw DiffResolutionException("Diff is empty for scope '${request.scope.name.lowercase()}'.")
    }
    return diffText
  }

  private fun detectBranchBase(repoRoot: Path): String {
    val candidates = listOf("main", "master", "origin/main", "origin/master")
    for (candidate in candidates) {
      val result = diffResolver.runProcess(listOf("git", "merge-base", "HEAD", candidate), repoRoot)
      if (result != null) return result.trim()
    }
    throw DiffResolutionException(
      "Could not detect branch base. Tried: ${candidates.joinToString()}.",
    )
  }

  private fun runDiff(args: List<String>, workDir: Path): String = diffResolver.runProcess(args, workDir)
    ?: throw DiffResolutionException(
      "Command failed: ${args.joinToString(" ")}",
    )

  private fun detectStack(evidence: ReviewDiffEvidence, repoRoot: Path): StackDetection {
    val packsRoot = repoRoot.resolve("platform-packs")
    // A missing platform-packs directory yields an empty list (no exception) and degrades to a
    // generic rubric. A directory that exists but is out of contract (corrupt platform.yaml,
    // invalid composition) throws; surface that loudly instead of silently dropping the
    // stack-specific specialists, per the shell's "never silently fall back" contract.
    val manifests = try {
      installedReviewCatalog.manifests().ifEmpty {
        scaffoldCatalogService.discoverPlatformManifests(packsRoot)
      }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      val displayPath = runCatching { repoRoot.relativize(packsRoot) }.getOrDefault(packsRoot)
      throw StackDetectionException(
        "Platform pack discovery failed for $displayPath: ${e.message ?: e.javaClass.simpleName}. " +
          "Repair the platform pack before running parallel review.",
        e,
      )
    }
    if (manifests.isEmpty()) return StackDetection(emptyList(), emptyList(), emptyMap())

    val routing = ReviewStackRouting.route(
      manifests,
      evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) },
    )
    val routed = manifests.filter { it.slug in routing.routedSlugs }
    return StackDetection(routed, manifests, routing.ownedPathsBySlug)
  }

  private fun launchResolvedLane(
    mode: ResolvedReviewExecutionMode,
    launchRequests: List<DelegatedReviewLaunchRequest>,
    agentId: String,
    routedManifests: List<PlatformManifest>,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
  ): ParallelReviewLaneOutcome = when (mode) {
    ResolvedReviewExecutionMode.INLINE ->
      launchInlineParentLane(agentId, launchRequests, routedManifests, request, modelOverride)
    ResolvedReviewExecutionMode.DELEGATED -> launchDelegatedLane(agentId, launchRequests, request, modelOverride)
  }

  @Suppress("LoopWithTooManyJumpStatements")
  private fun launchDelegatedLane(
    agentId: String,
    launchRequests: List<DelegatedReviewLaunchRequest>,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
  ): ParallelReviewLaneOutcome {
    if (launchRequests.isEmpty()) {
      return ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason = "Durable lifecycle recovery withheld every specialist launch for '$agentId'.",
      )
    }
    val timeout = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes
    val started = TimeSource.Monotonic.markNow()
    val outcomes = mutableListOf<ParallelReviewLaneOutcome>()
    val plan = DelegatedReviewCapacityPlanner.plan(
      DelegatedReviewCapacityRequest(
        selectedWorkerIds = launchRequests.map { it.assignment.digest },
        totalProcessSlots = DELEGATED_REVIEW_PROCESS_SLOTS,
      ),
    )
    val requestByDigest = launchRequests.associateBy { it.assignment.digest }
    var interrupted = false
    for (wave in plan.predictedWaves) {
      if (interrupted) break
      for (workerId in wave.workerIds) {
        val launchRequest = requireNotNull(requestByDigest[workerId])
        val remaining = timeout - started.elapsedNow()
        if (remaining <= 0.seconds) {
          outcomes += deadlineExpiredOutcome(launchRequest)
          continue
        }
        val outcome = launchSpecialist(launchRequest, request, modelOverride, remaining)
        outcomes += outcome
        // An interrupted launch signals a parent shutdown/cancellation; continuing to launch the
        // remaining specialists at that point only starts workers that will themselves be
        // immediately torn down.
        if (outcome.interrupted) {
          interrupted = true
          break
        }
      }
    }
    val failed = outcomes.firstOrNull { !it.success }
    return ParallelReviewLaneOutcome(
      success = failed == null,
      rawOutput = outcomes.filter { it.success }.joinToString("\n") { it.rawOutput },
      findings = outcomes.filter { it.success }.flatMap { it.findings },
      failureReason = failed?.failureReason,
      tokenUsage = outcomes.singleOrNull()?.tokenUsage,
      budgetOutcome = failed?.budgetOutcome,
      accounting = aggregateAccounting(agentId, outcomes.mapNotNull { it.accounting }),
      specialistAccounting = outcomes.flatMap { it.specialistAccounting },
    )
  }

  private fun deadlineExpiredOutcome(launchRequest: DelegatedReviewLaunchRequest): ParallelReviewLaneOutcome {
    recordWorkerLifecycle(
      launchRequest,
      ReviewLifecycleEventKind.WORKER_TIMED_OUT,
      ReviewWorkerLifecycleState.TIMED_OUT,
      ReviewProcessOutcome.TIMED_OUT,
      ReviewDiagnosticReference(
        "review-lifecycle/${launchRequest.assignment.reviewId}/" +
          launchRequest.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH),
        "Worker deadline expired before launch; no provider result was admitted.",
      ),
    )
    return ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "shared specialist deadline exhausted before '${launchRequest.assignment.lane}'",
      accounting = ReviewLaneAccounting(
        lane = launchRequest.assignment.lane,
        reviewId = launchRequest.assignment.reviewId,
        packetDigest = launchRequest.assignment.packetDigest,
        assignmentDigest = launchRequest.assignment.digest,
        evidenceBytes = 0,
        expansions = emptyList(),
        toolCalls = 0,
        modelTurns = 0,
        resultBytes = 0,
        terminalStatus = "timeout",
      ),
    )
  }

  @Suppress("LongMethod")
  private fun launchSpecialist(
    launchRequest: DelegatedReviewLaunchRequest,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
    timeout: kotlin.time.Duration = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
  ): ParallelReviewLaneOutcome {
    recordWorkerLifecycle(
      launchRequest,
      ReviewLifecycleEventKind.WORKER_LAUNCHED,
      ReviewWorkerLifecycleState.LAUNCHED,
      ReviewProcessOutcome.NOT_STARTED,
    )
    recordWorkerLifecycle(
      launchRequest,
      ReviewLifecycleEventKind.WORKER_RUNNING,
      ReviewWorkerLifecycleState.RUNNING,
      ReviewProcessOutcome.NOT_STARTED,
    )
    val execution = try {
      delegatedReviewExecutionBroker.execute(
        DelegatedReviewExecutionRequest(
          launchRequest = launchRequest,
          repoRoot = request.repoRoot,
          timeout = timeout,
          modelOverride = modelOverride,
        ),
      )
    } catch (interrupted: InterruptedException) {
      // The broker blocks while the provider process runs. If the coordinator is interrupted at
      // that boundary, the normal terminal facts never reach recordWorkerOutcome; persist the
      // cancellation first so recovery cannot mistake the durable RUNNING event for live work.
      recordWorkerLifecycle(
        launchRequest,
        ReviewLifecycleEventKind.WORKER_CANCELLED,
        ReviewWorkerLifecycleState.CANCELLED,
        ReviewProcessOutcome.INTERRUPTED,
        ReviewDiagnosticReference(
          "review-lifecycle-${launchRequest.assignment.reviewId}/" +
            launchRequest.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH),
          "Coordinator interruption stopped the worker before provider completion evidence returned.",
        ),
      )
      parallelLaneRunner.restoreInterruption()
      throw interrupted
    }
    val outputEnvelopeValid = (execution as? DelegatedReviewExecutionOutcome.Completed)
      ?.worker
      ?.facts
      ?.let { facts -> reviewOutputEnvelopeValid(facts.stdout) }
    return when (execution) {
      is DelegatedReviewExecutionOutcome.Terminated -> {
        recordWorkerOutcome(launchRequest, execution, outputEnvelopeValid)
        ParallelReviewLaneOutcome(
          success = false,
          rawOutput = "",
          failureReason = describeBudgetOutcome(execution.budgetOutcome),
          budgetOutcome = execution.budgetOutcome,
          accounting = execution.accounting,
        )
      }
      is DelegatedReviewExecutionOutcome.Completed ->
        completedSpecialistOutcome(launchRequest, execution, outputEnvelopeValid)
    }
  }

  private fun completedSpecialistOutcome(
    launchRequest: DelegatedReviewLaunchRequest,
    execution: DelegatedReviewExecutionOutcome.Completed,
    outputEnvelopeValid: Boolean?,
  ): ParallelReviewLaneOutcome {
    val worker = execution.worker
    worker.budgetOutcome?.takeIf { worker.facts == null }?.let { budgetOutcome ->
      recordWorkerOutcome(launchRequest, execution, outputEnvelopeValid)
      return ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason = describeBudgetOutcome(budgetOutcome),
        budgetOutcome = budgetOutcome,
        accounting = worker.accounting,
      )
    }
    val outcome = worker.facts ?: return unsupportedSpecialistOutcome(launchRequest, execution)
    val admittedFindings = outputEnvelopeValid.takeIf { it == true }?.let {
      runCatching { parseAdmittedFindings(launchRequest, outcome.stdout) }.getOrNull()
    }
    val resultEnvelope = admittedFindings?.let { runCatching { ReviewWorkerResultEnvelope(it) }.getOrNull() }
    val effectiveEnvelopeValid = outputEnvelopeValid == true && resultEnvelope != null &&
      worker.forbiddenOperation == null
    recordWorkerOutcome(
      launchRequest,
      execution,
      effectiveEnvelopeValid,
      resultEnvelope.takeIf { effectiveEnvelopeValid },
    )
    worker.forbiddenOperation?.let { forbidden ->
      return ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason = "forbidden review operation: ${forbidden.reason}",
        accounting = worker.accounting,
      )
    }
    val reason = completedSpecialistFailureReason(worker.budgetOutcome, outcome, effectiveEnvelopeValid)
    return ParallelReviewLaneOutcome(
      success = reason == null,
      rawOutput = outcome.stdout,
      failureReason = reason,
      tokenUsage = providerTokenUsage(outcome),
      budgetOutcome = worker.budgetOutcome,
      accounting = worker.accounting,
      interrupted = outcome.interrupted,
      findings = if (reason == null) admittedFindings.orEmpty() else emptyList(),
    )
  }

  private fun unsupportedSpecialistOutcome(
    launchRequest: DelegatedReviewLaunchRequest,
    execution: DelegatedReviewExecutionOutcome.Completed,
  ): ParallelReviewLaneOutcome {
    recordWorkerOutcome(launchRequest, execution)
    return ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "unsupported agent: ${execution.worker.unsupportedReason}",
      accounting = execution.worker.accounting,
    )
  }

  private fun completedSpecialistFailureReason(
    budgetOutcome: ReviewBudgetOutcome?,
    outcome: AgentRunLaunchFacts,
    resultEnvelopeValid: Boolean,
  ): String? = budgetOutcome?.takeIf { it.enforceable }?.let(::describeBudgetOutcome)
    ?: laneFailureReason(outcome)
    ?: classifyReviewOutput(outcome, resultEnvelopeValid).takeUnless {
      it.admission == ReviewOutputAdmission.SUCCESS
    }?.let { "invalid review output" }

  private fun parseAdmittedFindings(launchRequest: DelegatedReviewLaunchRequest, stdout: String) =
    ParallelReviewFindingParser.parse(stdout).map { finding ->
      require(finding.repositoryPath in launchRequest.assignment.assignedPaths) {
        "Delegated finding location '${finding.location}' is outside the authoritative assignment ownership."
      }
      val assignedSpecialist = launchRequest.assignment.laneDecision.specialistSkillName
      require(finding.specialistSkillName == null || finding.specialistSkillName == assignedSpecialist) {
        "Delegated finding specialist '${finding.specialistSkillName}' does not match '$assignedSpecialist'."
      }
      finding.copy(
        specialistSkillName = assignedSpecialist,
        originLayerChains = launchRequest.assignment.laneDecision.originLayerChains,
      )
    }

  private fun recordWorkerLifecycle(
    launchRequest: DelegatedReviewLaunchRequest,
    eventKind: ReviewLifecycleEventKind,
    state: ReviewWorkerLifecycleState,
    processOutcome: ReviewProcessOutcome? = null,
    diagnostic: ReviewDiagnosticReference? = null,
    durableProgress: ReviewDurableWorkerProgress? = null,
    livenessObservations: List<ReviewLivenessObservation> = emptyList(),
    providerOutput: ReviewProviderOutputObservation? = null,
    declaredProgress: ReviewDeclaredSpecialistProgress? = null,
    resultEnvelope: ReviewWorkerResultEnvelope? = null,
  ) {
    lifecycleRecorder.record(
      ReviewLifecycleRecord(
        reviewId = launchRequest.assignment.reviewId,
        packetDigest = launchRequest.assignment.packetDigest,
        component = ReviewLifecycleComponent.WORKER,
        eventKind = eventKind,
        workerId = launchRequest.assignment.lane,
        providerId = launchRequest.agentId,
        attempt = launchRequest.attempt,
        assignmentDigest = launchRequest.assignment.digest,
        routedArea = launchRequest.assignment.laneDecision.specialistSkillName ?: launchRequest.assignment.lane,
        state = state,
        processOutcome = processOutcome,
        livenessObservations = livenessObservations,
        providerOutput = providerOutput,
        declaredProgress = declaredProgress,
        durableProgress = durableProgress,
        resultEnvelope = resultEnvelope,
        diagnostic = diagnostic,
      ),
    )
  }

  private fun recordWorkerOutcome(
    launchRequest: DelegatedReviewLaunchRequest,
    execution: DelegatedReviewExecutionOutcome,
    resultEnvelopeValid: Boolean? = null,
    resultEnvelope: ReviewWorkerResultEnvelope? = null,
  ) {
    val facts = (execution as? DelegatedReviewExecutionOutcome.Completed)?.worker?.facts
    val outcome = classifyWorkerOutcome(execution, facts, resultEnvelopeValid)
    val admittedResultEnvelope = resultEnvelope.takeIf {
      outcome == ReviewProcessOutcome.ZERO_EXIT && resultEnvelopeValid == true
    }
    recordAdmittedWorkerProgress(launchRequest, outcome, resultEnvelopeValid)
    val transition = workerTerminalTransition(outcome)
    recordWorkerLifecycle(
      launchRequest,
      transition.eventKind,
      transition.state,
      outcome,
      workerDiagnostic(launchRequest, outcome),
      livenessObservations = facts?.let(::livenessObservations).orEmpty(),
      providerOutput = facts?.let { outputObservation(it, outcome) },
      declaredProgress = facts?.let(::declaredProgress),
      resultEnvelope = admittedResultEnvelope,
    )
  }

  private fun classifyWorkerOutcome(
    execution: DelegatedReviewExecutionOutcome,
    facts: AgentRunLaunchFacts?,
    resultEnvelopeValid: Boolean?,
  ): ReviewProcessOutcome {
    val classified = delegatedReviewExecutionBroker.classifyLifecycleOutcome(execution)
    return when {
      classified == ReviewProcessOutcome.ZERO_EXIT && facts?.stdout?.isBlank() == true ->
        ReviewProcessOutcome.MISSING_RESULT
      classified == ReviewProcessOutcome.ZERO_EXIT && resultEnvelopeValid == false ->
        ReviewProcessOutcome.INVALID_OUTPUT
      else -> classified
    }
  }

  private fun recordAdmittedWorkerProgress(
    launchRequest: DelegatedReviewLaunchRequest,
    outcome: ReviewProcessOutcome,
    resultEnvelopeValid: Boolean?,
  ) {
    if (outcome != ReviewProcessOutcome.ZERO_EXIT || resultEnvelopeValid != true) return
    recordWorkerLifecycle(
      launchRequest,
      ReviewLifecycleEventKind.WORKER_PROGRESS,
      ReviewWorkerLifecycleState.RUNNING,
      ReviewProcessOutcome.ZERO_EXIT,
      durableProgress = ReviewDurableWorkerProgress(
        lifecycleRecorder.timestamp(),
        "result-${launchRequest.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH)}",
        "Specialist result admitted as durable worker progress.",
      ),
    )
  }

  private data class WorkerTerminalTransition(
    val state: ReviewWorkerLifecycleState,
    val eventKind: ReviewLifecycleEventKind,
  )

  private fun workerTerminalTransition(outcome: ReviewProcessOutcome): WorkerTerminalTransition = when (outcome) {
    ReviewProcessOutcome.ZERO_EXIT ->
      WorkerTerminalTransition(ReviewWorkerLifecycleState.COMPLETED, ReviewLifecycleEventKind.WORKER_COMPLETED)
    ReviewProcessOutcome.TIMED_OUT ->
      WorkerTerminalTransition(ReviewWorkerLifecycleState.TIMED_OUT, ReviewLifecycleEventKind.WORKER_TIMED_OUT)
    ReviewProcessOutcome.INTERRUPTED ->
      WorkerTerminalTransition(ReviewWorkerLifecycleState.CANCELLED, ReviewLifecycleEventKind.WORKER_CANCELLED)
    ReviewProcessOutcome.UNAVAILABLE ->
      WorkerTerminalTransition(ReviewWorkerLifecycleState.UNAVAILABLE, ReviewLifecycleEventKind.WORKER_UNAVAILABLE)
    ReviewProcessOutcome.INVALID_OUTPUT ->
      WorkerTerminalTransition(
        ReviewWorkerLifecycleState.INVALID_OUTPUT,
        ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT,
      )
    else -> WorkerTerminalTransition(ReviewWorkerLifecycleState.FAILED, ReviewLifecycleEventKind.WORKER_FAILED)
  }

  private fun workerDiagnostic(launchRequest: DelegatedReviewLaunchRequest, outcome: ReviewProcessOutcome) =
    ReviewDiagnosticReference(
      "review-lifecycle/${launchRequest.assignment.reviewId}/" +
        launchRequest.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH),
      when (outcome) {
        ReviewProcessOutcome.ZERO_EXIT -> "Worker returned a normal zero-exit result."
        ReviewProcessOutcome.TIMED_OUT -> "Worker deadline expired before a terminal result."
        ReviewProcessOutcome.INTERRUPTED -> "Worker was interrupted before a terminal result."
        ReviewProcessOutcome.UNAVAILABLE -> "Provider launch was unavailable for this worker."
        ReviewProcessOutcome.INVALID_OUTPUT -> "Worker output was not admitted as a completed result."
        ReviewProcessOutcome.MISSING_RESULT -> "Worker exited normally without an explicit result envelope."
        ReviewProcessOutcome.NON_ZERO_EXIT -> "Worker exited without a successful process outcome."
        else -> "Worker did not produce a successful lifecycle result."
      },
    )

  private fun reviewOutputEnvelopeValid(stdout: String): Boolean {
    val lines = stdout.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    if (lines.isEmpty()) return false
    if (lines.size == 1 && lines.single() == REVIEW_NO_FINDINGS_ENVELOPE) return true
    return lines.all { line -> ParallelReviewFindingParser.parallelFindingPattern.matches(line) }
  }

  private fun livenessObservations(facts: AgentRunLaunchFacts): List<ReviewLivenessObservation> = buildList {
    val liveness = facts.liveness ?: return@buildList
    liveness.lastOutputAt?.let { observedAt ->
      addLifecycleObservation(
        ReviewLivenessObservation.Kind.PROCESS_HEARTBEAT,
        observedAt,
        liveness.processState.ifBlank { liveness.reason },
      )
    }
    liveness.lastWorkflowSnapshotAt?.let { observedAt ->
      addLifecycleObservation(
        ReviewLivenessObservation.Kind.MCP_HEARTBEAT,
        observedAt,
        liveness.activeOperationName ?: liveness.reason,
      )
    }
  }

  private fun outputObservation(
    facts: AgentRunLaunchFacts,
    outcome: ReviewProcessOutcome,
  ): ReviewProviderOutputObservation = ReviewProviderOutputObservation(
    observedAt = facts.liveness?.lastOutputAt ?: lifecycleRecorder.timestamp(),
    outcome = outcome.name.lowercase(),
    byteSize = facts.stdoutByteSize,
    sha256 = facts.stdoutSha256,
  )

  private fun declaredProgress(facts: AgentRunLaunchFacts): ReviewDeclaredSpecialistProgress? {
    val liveness = facts.liveness ?: return null
    val observedAt = liveness.lastDurableProgressAt ?: return null
    val label = liveness.lastDurableProgressLabel ?: return null
    return runCatching {
      ReviewDeclaredSpecialistProgress(
        observedAt,
        liveness.workflowStep ?: "declared-progress",
        label,
      )
    }.getOrNull()
  }

  private fun MutableList<ReviewLivenessObservation>.addLifecycleObservation(
    kind: ReviewLivenessObservation.Kind,
    observedAt: String,
    status: String,
  ) {
    runCatching {
      ReviewLivenessObservation(kind, observedAt, status.take(REVIEW_LIFECYCLE_MAX_TEXT_CHARS))
    }.getOrNull()?.let(::add)
  }

  @Suppress("LongMethod")
  private fun launchInlineParentLane(
    agentId: String,
    launchRequests: List<DelegatedReviewLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    request: ParallelCodeReviewRequest,
    modelOverride: String?,
  ): ParallelReviewLaneOutcome {
    require(launchRequests.isNotEmpty()) { "Inline review selected no resolved assignments for '$agentId'." }
    val selected = launchRequests.sortedBy { it.assignment.laneDecision.orderIndex }
    val prompt = buildString {
      appendLine("Run one bill-code-review mode:inline parent review at the light depth tier.")
      appendLine("Resolved execution mode: inline")
      appendLine(
        "Depth: reduced. Walk the routed areas below as an explicit checklist, once each, under a " +
          "bounded budget. This is not equivalent coverage to a delegated review and must not be " +
          "presented as one; state that specialist depth was not applied.",
      )
      appendLine("Detected stack: ${routedManifests.joinToString("+") { it.slug }.ifBlank { "generic" }}")
      val rubricLabel = selected.joinToString { launch ->
        val decision = launch.assignment.laneDecision
        "${decision.specialistSkillName}" +
          "[paths=${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }};" +
          "add-ons=${decision.addOns.joinToString("+").ifBlank { "none" }};" +
          "origins=${decision.originLayerChains.joinToString("|") { it.joinToString("->") }}]"
      }.ifBlank { "parallel-code-review" }
      appendLine("Authoritative routed rubric identities: $rubricLabel")
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine()
        appendLine("## Resolved rubric: ${decision.specialistSkillName}")
        appendLine("Owned paths: ${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }}")
        launch.rubrics.forEach { rubric -> appendLine(rubric.body) }
      }
      appendLine("Use the exact diff below as authoritative; do not rediscover or replace its scope.")
      appendLine(
        "Cover each routed rubric above once at reduced depth in this agent context and do not launch " +
          "specialists. Follow only the signals that appear; do not build a case for a marginal finding. " +
          "Depth and budget are lowered here — the severity vocabulary, the finding admission gate, the " +
          "evidence and observable-consequence requirements, the F-XXX register format, and telemetry are " +
          "inherited unchanged.",
      )
      appendLine(
        "Return only '[F-XXX] Severity | Confidence | specialist=<exact resolved rubric identity> | " +
          "path=<JSON string> | line=<positive integer> | description' lines.",
      )
      appendLine()
      // Hunks carry only line-number headers, never their file path, so grouping by path here is
      // the only place that attribution survives; a flat concatenation across files leaves the
      // agent unable to tell which hunk belongs to which changed file.
      launchRequests.first().packet.changedHunks
        .groupBy { it.path }
        .toSortedMap()
        .forEach { (path, hunks) ->
          appendLine("## Changed file: ${structuredString(path)}")
          hunks.forEach { hunk -> appendLine(hunk.content) }
        }
    }
    val outcome = parentReviewLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = agentId,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = "code-review-parallel",
          repoRoot = request.repoRoot,
          timeout = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
          promptOverride = prompt,
          modelOverride = modelOverride,
        ),
      ),
    )
    val inlineAssignment = selected.first().assignment
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason = "unsupported agent: ${outcome.reason}",
        accounting = inlineParentAccounting(agentId, inlineAssignment, prompt, "unsupported_provider", null),
      )
      is AgentRunLaunchFacts -> {
        val reason = laneFailureReason(outcome)
        val findings = if (reason == null) {
          ParallelReviewFindingParser.parse(outcome.stdout).map { finding ->
            val findingPath = requireNotNull(finding.repositoryPath)
            val owners = selected.filter { launch ->
              launch.assignment.assignedPaths.any { path -> path == findingPath }
            }
            require(owners.isNotEmpty()) {
              "Inline finding location '${finding.location}' is outside the authoritative assignment ownership."
            }
            val distinctOwners = owners.distinctBy { it.assignment.laneDecision.specialistSkillName }
            val declaredSpecialist = finding.specialistSkillName
            require(declaredSpecialist != null || distinctOwners.size == 1) {
              "Inline finding location '${finding.location}' has overlapping ownership and must name its specialist."
            }
            val owner = if (declaredSpecialist == null) {
              distinctOwners.single()
            } else {
              distinctOwners.singleOrNull {
                it.assignment.laneDecision.specialistSkillName == declaredSpecialist
              } ?: error(
                "Inline finding specialist '$declaredSpecialist' does not own '${finding.location}'.",
              )
            }
            finding.copy(
              specialistSkillName = owner.assignment.laneDecision.specialistSkillName,
              originLayerChains = owner.assignment.laneDecision.originLayerChains,
            )
          }
        } else {
          emptyList()
        }
        ParallelReviewLaneOutcome(
          success = reason == null,
          rawOutput = outcome.stdout,
          failureReason = reason,
          tokenUsage = providerTokenUsage(outcome),
          accounting = inlineParentAccounting(
            agentId,
            inlineAssignment,
            prompt,
            inlineTerminalStatus(outcome),
            outcome,
          ),
          findings = findings,
        )
      }
    }
  }

  private fun laneOwnedPaths(lane: ReviewLaunchLane, files: List<ReviewChangedFileEvidence>): List<String> {
    return files.filter { file ->
      ReviewLaneInclusionPolicy.ownsChangedFile(lane, file.path, file.changedContent)
    }.map { it.path }
  }

  // Maps a completed launch to a human-readable failure reason, or null when the lane succeeded.
  // timedOut/spawnFailed/interrupted are checked first because they leave exitStatus null.
  // The null == exitStatus guard closes the degenerate case where all flags are false but
  // exitStatus is also null — a combination the init requires prevent but that would otherwise
  // fall through to else->null and silently report an empty-findings lane as succeeded.
  private fun laneFailureReason(facts: AgentRunLaunchFacts): String? = when {
    facts.timedOut -> "agent timed out"
    facts.spawnFailed -> "agent process failed to spawn"
    facts.interrupted -> "agent was interrupted"
    facts.exitStatus == null -> "agent exited with unknown status"
    facts.exitStatus != 0 -> buildString {
      append("agent exited with status ${facts.exitStatus}")
      facts.stderr.trim().lineSequence().firstOrNull { it.isNotBlank() }?.let { line ->
        append(" — ${line.take(STDERR_EXCERPT_MAX_LENGTH)}")
      }
    }
    // A truncated stdout means the retained bytes may not contain the agent's actual result at
    // all; parsing it as if complete risks reporting a false empty success instead of surfacing
    // the truncation.
    facts.stdoutTruncated -> "agent output exceeded the retention cap before completion"
    else -> null
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MINUTES = 30L
    const val DELEGATED_REVIEW_PROCESS_SLOTS = 7
    const val TIMEOUT_BUFFER_SECONDS = 30L
    const val SECONDS_PER_MINUTE = 60L
    const val STDERR_EXCERPT_MAX_LENGTH = 120
    const val MAX_SUPPLIED_DIFF_BYTES = 1_000_000L
    const val REVIEW_LIFECYCLE_MAX_TEXT_CHARS = 500
    const val REVIEW_NO_FINDINGS_ENVELOPE = "NO_FINDINGS"
    const val DIAGNOSTIC_DIGEST_PREFIX_LENGTH = 12
  }

  private data class StackDetection(
    val routed: List<PlatformManifest>,
    val manifests: List<PlatformManifest>,
    val ownedPathsBySlug: Map<String, Set<String>>,
  )
}

private fun parallelResult(
  agent1Id: String,
  agent2Id: String,
  outcomes: skillbill.ports.review.model.ParallelReviewLaneRunResult,
): ParallelCodeReviewResult {
  // A lane's own `findings` already carries the right value in both modes: delegated lanes keep
  // every successful specialist's findings even when a sibling specialist in the same lane failed,
  // and inline lanes carry an empty list on failure. Gating the whole lane's findings on `success`
  // discarded those already-correct successful-sibling findings whenever any specialist failed; the
  // success check is needed only to avoid re-parsing a failed run's raw output as a fallback.
  val lane1Result = ParallelReviewLaneResult(
    agentId = agent1Id,
    findings = outcomes.lane1.findings.ifEmpty {
      if (outcomes.lane1.success) ParallelReviewFindingParser.parse(outcomes.lane1.rawOutput) else emptyList()
    },
  )
  val lane2Result = ParallelReviewLaneResult(
    agentId = agent2Id,
    findings = outcomes.lane2.findings.ifEmpty {
      if (outcomes.lane2.success) ParallelReviewFindingParser.parse(outcomes.lane2.rawOutput) else emptyList()
    },
  )
  return ParallelCodeReviewResult(
    mergeResult = ParallelReviewMerger.merge(lane1Result, lane2Result),
    lane1 = outcomes.lane1.toStatus(agent1Id),
    lane2 = outcomes.lane2.toStatus(agent2Id),
    accountingSummary = parallelAccountingSummary(outcomes),
  )
}

private fun ParallelReviewLaneOutcome.toStatus(agentId: String) = ParallelReviewLaneStatus(
  agentId,
  success,
  failureReason,
  tokenUsage,
  budgetOutcome,
  accounting,
  specialistAccounting,
)

private fun aggregateAccounting(agentId: String, values: List<ReviewLaneAccounting>): ReviewLaneAccounting? {
  if (values.isEmpty()) return null
  return ReviewLaneAccounting(
    lane = agentId,
    reviewId = values.first().reviewId,
    packetDigest = values.first().packetDigest,
    assignmentDigest = sha256HexUtf8(values.joinToString("+") { it.assignmentDigest }),
    launchBytes = values.sumOf { it.launchBytes },
    evidenceBytes = values.sumOf { it.evidenceBytes },
    expansions = values.flatMap { it.expansions },
    toolCalls = values.sumOf { it.toolCalls },
    modelTurns = values.sumOf { it.modelTurns },
    resultBytes = values.sumOf { it.resultBytes },
    terminalStatus = values.firstOrNull { it.terminalStatus != "completed" }?.terminalStatus ?: "completed",
    terminalOutcome = values.firstNotNullOfOrNull { it.terminalOutcome },
  )
}

private fun parallelAccountingSummary(
  outcomes: skillbill.ports.review.model.ParallelReviewLaneRunResult,
): ReviewAccountingSummary? {
  val specialists = listOf(outcomes.lane1, outcomes.lane2).flatMap { it.specialistAccounting }
  if (specialists.isEmpty()) return null
  fun ReviewLaneAccounting.toInput() = ReviewAccountingInput(
    lane = lane,
    assignmentDigest = assignmentDigest,
    counters = ReviewAccountingCounters(
      launchBytes,
      evidenceBytes,
      resultBytes,
      expansions.size,
      toolCalls,
      modelTurns,
    ),
    usage = providerUsage ?: ProviderTokenUsage(),
    terminalOutcome = terminalStatus,
  )
  val roots = listOf(outcomes.lane1, outcomes.lane2).mapIndexed { index, outcome ->
    ReviewAccountingInput(
      lane = "parallel-agent-${index + 1}",
      assignmentDigest = sha256HexUtf8("parallel-agent-${index + 1}"),
      children = outcome.specialistAccounting.map(ReviewLaneAccounting::toInput),
      terminalOutcome = if (outcome.success) "completed" else "partial_failure",
    )
  }
  return ReviewTreeAccounting.summarize(
    reviewId = specialists.first().reviewId,
    packetDigest = specialists.first().packetDigest,
    root = ReviewAccountingInput("parallel-review", sha256HexUtf8("parallel-review"), children = roots),
  )
}

/**
 * An inline lane runs the whole review in one parent session, so its accounting node owns the
 * parent's own launch and result bytes and exactly one model turn. It has no specialist children.
 */
private fun inlineParentAccounting(
  agentId: String,
  assignment: ReviewAssignment,
  prompt: String,
  terminalStatus: String,
  outcome: AgentRunLaunchFacts?,
) = ReviewLaneAccounting(
  lane = agentId,
  reviewId = assignment.reviewId,
  packetDigest = assignment.packetDigest,
  assignmentDigest = assignment.digest,
  launchBytes = prompt.toByteArray(Charsets.UTF_8).size.toLong(),
  evidenceBytes = 0,
  expansions = emptyList(),
  toolCalls = 0,
  modelTurns = 1,
  resultBytes = outcome?.stdout?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0,
  providerUsage = outcome?.let(::providerTokenUsage),
  terminalStatus = terminalStatus,
)

private fun inlineTerminalStatus(facts: AgentRunLaunchFacts): String = when {
  facts.timedOut -> "timeout"
  facts.interrupted -> "interrupted"
  facts.spawnFailed -> "spawn_failure"
  facts.exitStatus != 0 -> "process_failure"
  else -> "completed"
}

private fun describeBudgetOutcome(outcome: ReviewBudgetOutcome): String =
  "${outcome.type}: ${outcome.budgetKind} ${outcome.observedValue} > ${outcome.configuredLimit}"

private fun providerTokenUsage(outcome: AgentRunLaunchFacts): ProviderTokenUsage? {
  val values = listOf(
    outcome.inputTokens,
    outcome.cachedInputTokens,
    outcome.outputTokens,
    outcome.reasoningTokens,
    outcome.totalTokens,
  )
  if (values.none { it != null }) return null
  return ProviderTokenUsage(
    inputTokens = outcome.inputTokens,
    cachedInputTokens = outcome.cachedInputTokens,
    outputTokens = outcome.outputTokens,
    reasoningTokens = outcome.reasoningTokens,
    totalTokens = outcome.totalTokens,
    ownership = if (outcome.tokenOwnership == AgentRunTokenOwnership.INCLUSIVE) {
      TokenOwnership.INCLUSIVE
    } else {
      TokenOwnership.DIRECT
    },
  )
}
