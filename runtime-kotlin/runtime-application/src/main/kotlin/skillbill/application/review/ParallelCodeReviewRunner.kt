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
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
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
import skillbill.ports.review.model.DelegatedReviewDeadlineScope as LifecycleDeadlineScope
import skillbill.ports.review.model.DelegatedReviewLifecycleMetrics
import skillbill.ports.review.model.DelegatedReviewLifecycleSnapshot
import skillbill.ports.review.model.DelegatedReviewTerminalClassification
import skillbill.ports.review.model.DelegatedReviewWaveRecord
import skillbill.ports.review.model.DelegatedReviewWorkerRecord
import skillbill.ports.review.model.DelegatedReviewWorkerState
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.DelegatedReviewAggregationGate
import skillbill.review.DelegatedReviewAggregationRequest
import skillbill.review.DelegatedReviewAggregationState
import skillbill.review.DelegatedReviewAssignmentOwnership
import skillbill.review.DelegatedReviewFindingEnvelope
import skillbill.review.DelegatedReviewWorkerResult
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
import skillbill.review.plan.DelegatedReviewCapacityPlan
import skillbill.review.plan.DelegatedReviewCapacityRequest
import skillbill.review.plan.DelegatedReviewDeadlinePolicy
import skillbill.review.plan.DelegatedReviewDeadlineScope as PolicyDeadlineScope
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
  private val delegatedReviewDeadlinePolicy: DelegatedReviewDeadlinePolicy = DelegatedReviewDeadlinePolicy.DEFAULT,
  private val monotonicNowNanos: () -> Long = System::nanoTime,
  private val interruptionProbe: () -> Boolean = { Thread.currentThread().isInterrupted },
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
    val startedNanos: Long,
    val capacityPlan: DelegatedReviewCapacityPlan,
    var actualWaves: List<skillbill.review.plan.DelegatedReviewWave> = emptyList(),
    var aggregationStartedNanos: Long? = null,
    var interruptionClassification: DelegatedReviewTerminalClassification =
      DelegatedReviewTerminalClassification.INTERRUPTED_BEFORE_LAUNCH,
  )

  private data class ReviewCompletionResult(
    val accepted: Boolean,
    val terminalRecord: ReviewLifecycleRecord? = null,
  )

  fun run(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    val prepared = prepareRun(originalRequest)
    prepared.recovery?.terminalRecord?.let { terminalRecord ->
      return replayPersistedTerminal(prepared, terminalRecord)
    }
    return try {
      val outcomes = executePreparedRun(prepared)
      if (interruptionProbe()) {
        finishInterrupted(prepared, outcomes, interruptionBoundary(prepared))
      } else {
        recordAggregationStarted(prepared)
        if (interruptionProbe()) {
          finishInterrupted(prepared, outcomes, DelegatedReviewTerminalClassification.INTERRUPTED_DURING_AGGREGATION)
        } else {
          withFailureRecording(
            onFailure = { error ->
              if (error !is InterruptedException) {
                prepared.lifecycleReview?.let(::recordCoordinatorCrash)
              }
            },
            block = { finalizePreparedRun(prepared, outcomes) },
          )
        }
      }
    } catch (interrupted: InterruptedException) {
      parallelLaneRunner.restoreInterruption()
      finishInterrupted(
        prepared,
        interruptedOutcomes(),
        interruptionBoundary(prepared),
      )
    }
  }

  private fun finishInterrupted(
    prepared: PreparedRun,
    outcomes: ParallelReviewLaneRunResult,
    classification: DelegatedReviewTerminalClassification,
  ): ParallelCodeReviewResult {
    val interruptedOutcomes = ParallelReviewLaneRunResult(
      lane1 = outcomes.lane1.interruptedFor(classification),
      lane2 = outcomes.lane2.interruptedFor(classification),
    )
    val boundaryRecords = interruptionBoundaryRecords(prepared, classification)
    persistLifecycleProjection(prepared, interruptedOutcomes, classification, boundaryRecords)
    val result = parallelResult(prepared.initial.agent1Id, prepared.initial.agent2Id, interruptedOutcomes)
    result.accountingSummary?.let { summary ->
      database.transaction { unitOfWork ->
        unitOfWork.reviews.saveAccounting(
          ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary.toBoundedPayload()),
        )
      }
    }
    return result
  }

  private fun interruptedOutcomes() = ParallelReviewLaneRunResult(
    lane1 = emptyDelegatedLaneOutcome("Delegated review interrupted before a lane result was available."),
    lane2 = emptyDelegatedLaneOutcome("Delegated review interrupted before a lane result was available."),
  )

  private fun replayPersistedTerminal(
    prepared: PreparedRun,
    terminalRecord: skillbill.ports.review.model.ReviewLifecycleEvent,
  ): ParallelCodeReviewResult {
    val recovery = requireNotNull(prepared.recovery)
    val recovered = rebuildRecoveredOutcomes(
      prepared.launchRequests,
      emptyList(),
      recovery,
      interruptedOutcomes(),
      prepared.initial.agent1Id,
      prepared.initial.agent2Id,
    )
    val terminalClassification = when (terminalRecord.eventKind) {
      ReviewLifecycleEventKind.TERMINAL_COMPLETED -> DelegatedReviewTerminalClassification.COMPLETED
      ReviewLifecycleEventKind.TERMINAL_TIMED_OUT -> DelegatedReviewTerminalClassification.TIMED_OUT
      ReviewLifecycleEventKind.TERMINAL_CANCELLED -> persistedInterruptionClassification(prepared, terminalRecord)
      else -> null
    }
    val terminalOutcomes = when (terminalRecord.eventKind) {
      ReviewLifecycleEventKind.TERMINAL_COMPLETED -> recovered.copy(
        lane1 = recovered.lane1.copy(success = true, failureReason = null),
        lane2 = recovered.lane2.copy(success = true, failureReason = null),
      )
      ReviewLifecycleEventKind.TERMINAL_CANCELLED -> recovered.copy(
        lane1 = recovered.lane1.interruptedFor(requireNotNull(terminalClassification)),
        lane2 = recovered.lane2.interruptedFor(requireNotNull(terminalClassification)),
      )
      ReviewLifecycleEventKind.TERMINAL_TIMED_OUT -> recovered.copy(
        lane1 = recovered.lane1.copy(
          success = false,
          rawOutput = "",
          failureReason = "The durable delegated review terminal record is timed out.",
        ),
        lane2 = recovered.lane2.copy(
          success = false,
          rawOutput = "",
          failureReason = "The durable delegated review terminal record is timed out.",
        ),
      )
      else -> recovered.copy(
        lane1 = recovered.lane1.copy(
          success = false,
          rawOutput = "",
          failureReason = "The durable delegated review terminal record is authoritative.",
        ),
        lane2 = recovered.lane2.copy(
          success = false,
          rawOutput = "",
          failureReason = "The durable delegated review terminal record is authoritative.",
        ),
      )
    }
    if (recovery.persistedProjection == null) {
      persistLifecycleProjection(
        prepared,
        terminalOutcomes,
        terminalClassificationOverride = terminalClassification,
      )
    }
    return parallelResult(prepared.initial.agent1Id, prepared.initial.agent2Id, terminalOutcomes)
  }

  private fun persistedInterruptionClassification(
    prepared: PreparedRun,
    terminalRecord: ReviewLifecycleEvent,
  ): DelegatedReviewTerminalClassification {
    prepared.recovery?.terminalClassification?.let { return it }
    val diagnosticClassification = terminalRecord.diagnostic?.summary
      ?.substringAfter(" at ", missingDelimiterValue = "")
      ?.removeSuffix(".")
      ?.takeIf(String::isNotBlank)
      ?.let { raw -> runCatching { DelegatedReviewTerminalClassification.valueOf(raw.uppercase()) }.getOrNull() }
    return diagnosticClassification ?: interruptionBoundary(prepared)
  }

  private fun interruptionBoundary(prepared: PreparedRun): DelegatedReviewTerminalClassification {
    val launch = prepared.lifecycleReview ?: return DelegatedReviewTerminalClassification.INTERRUPTED_DURING_WORKER
    val currentAttempts = prepared.launchRequests.associate { it.assignment.digest to it.attempt }
    val events = database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(launch.assignment.reviewId)
    }
    return when {
      events.any { it.eventKind == ReviewLifecycleEventKind.TERMINAL_COMPLETED ||
        it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED } ->
        DelegatedReviewTerminalClassification.INTERRUPTED_BEFORE_TERMINAL_PERSISTENCE
      events.any { it.eventKind == ReviewLifecycleEventKind.AGGREGATION_STARTED } ->
        DelegatedReviewTerminalClassification.INTERRUPTED_DURING_AGGREGATION
      events.any {
        it.eventKind == ReviewLifecycleEventKind.WORKER_CANCELLED &&
          currentAttempts[it.assignmentDigest] == it.attempt &&
          it.processOutcome == ReviewProcessOutcome.INTERRUPTED
      } -> DelegatedReviewTerminalClassification.INTERRUPTED_DURING_WORKER
      prepared.interruptionClassification != DelegatedReviewTerminalClassification.INTERRUPTED_BEFORE_LAUNCH ->
        prepared.interruptionClassification
      prepared.actualWaves.isNotEmpty() -> DelegatedReviewTerminalClassification.INTERRUPTED_BETWEEN_WAVES
      else -> DelegatedReviewTerminalClassification.INTERRUPTED_BEFORE_LAUNCH
    }
  }

  private fun interruptionBoundaryRecords(
    prepared: PreparedRun,
    classification: DelegatedReviewTerminalClassification,
  ): List<ReviewLifecycleRecord> {
    val launch = prepared.lifecycleReview ?: return emptyList()
    val events = database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(launch.assignment.reviewId)
    }
    val latestByAssignment = events
      .filter { it.component == ReviewLifecycleComponent.WORKER && it.assignmentDigest != null }
      .groupBy { it.assignmentDigest }
      .mapValues { (_, values) -> values.maxWithOrNull(compareBy({ it.attempt ?: 0 }, { it.sequence })) }
    val diagnostic = ReviewDiagnosticReference(
      "review-lifecycle/${launch.assignment.reviewId}/interrupt",
      "Delegated review interrupted at ${classification.name.lowercase()}.",
    )
    val boundaryRecords = mutableListOf<ReviewLifecycleRecord>()
    if (classification != DelegatedReviewTerminalClassification.INTERRUPTED_DURING_AGGREGATION &&
      classification != DelegatedReviewTerminalClassification.INTERRUPTED_BEFORE_TERMINAL_PERSISTENCE
    ) {
      prepared.launchRequests.forEach { selected ->
        val latest = latestByAssignment[selected.assignment.digest]
        if (latest?.let { it.attempt == selected.attempt && it.eventKind in WORKER_TERMINAL_EVENT_KINDS } != true) {
          boundaryRecords += ReviewLifecycleRecord(
            reviewId = selected.assignment.reviewId,
            packetDigest = selected.assignment.packetDigest,
            component = ReviewLifecycleComponent.WORKER,
            eventKind = ReviewLifecycleEventKind.WORKER_CANCELLED,
            workerId = selected.assignment.lane,
            providerId = selected.agentId,
            attempt = selected.attempt,
            assignmentDigest = selected.assignment.digest,
            routedArea = selected.assignment.lane,
            state = ReviewWorkerLifecycleState.CANCELLED,
            processOutcome = ReviewProcessOutcome.INTERRUPTED,
            diagnostic = diagnostic,
          )
        }
      }
    }
    if (
      classification == DelegatedReviewTerminalClassification.INTERRUPTED_DURING_AGGREGATION &&
      events.none { it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED }
    ) {
      boundaryRecords += ReviewLifecycleRecord(
          reviewId = launch.assignment.reviewId,
          packetDigest = launch.assignment.packetDigest,
          component = ReviewLifecycleComponent.AGGREGATION,
          eventKind = ReviewLifecycleEventKind.AGGREGATION_FAILED,
          processOutcome = ReviewProcessOutcome.INTERRUPTED,
          diagnostic = diagnostic,
      )
    }
    if (events.none { it.component == ReviewLifecycleComponent.TERMINAL }) {
      boundaryRecords += ReviewLifecycleRecord(
          reviewId = launch.assignment.reviewId,
          packetDigest = launch.assignment.packetDigest,
          component = ReviewLifecycleComponent.TERMINAL,
          eventKind = ReviewLifecycleEventKind.TERMINAL_CANCELLED,
          processOutcome = ReviewProcessOutcome.INTERRUPTED,
          terminalCompletion = ReviewTerminalCompletion(
            lifecycleRecorder.timestamp(),
            ReviewProcessOutcome.INTERRUPTED,
          ),
          diagnostic = diagnostic,
      )
    }
    return boundaryRecords
  }

  private fun prepareRun(originalRequest: ParallelCodeReviewRequest): PreparedRun {
    val startedNanos = monotonicNowNanos()
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
    val capacityPlan = DelegatedReviewCapacityPlanner.plan(
      DelegatedReviewCapacityRequest(
        selectedWorkerIds = launchRequests.map { it.assignment.digest },
        totalProcessSlots = DELEGATED_REVIEW_PROCESS_SLOTS,
      ),
    )
    if (recovery?.terminalRecord == null) {
      lifecycleReview?.let { recordPreparedLifecycle(it, launchRequests) }
    }
    return PreparedRun(
      initial = initial,
      launchRequests = launchRequests,
      relaunchableRequests = launchRequests.filter { recovery?.shouldLaunch(it.assignment.digest) ?: true },
      lifecycleReview = lifecycleReview,
      recovery = recovery,
      startedNanos = startedNanos,
      capacityPlan = capacityPlan,
      actualWaves = recovery?.actualWaves.orEmpty(),
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
    if (interruptionProbe()) {
      throw InterruptedException("Delegated review interrupted before worker launch.")
    }
    val policy = effectiveDeadlinePolicy(prepared.initial.request)
    if (prepared.lifecycleReview != null && deadlineExpired(policy, PolicyDeadlineScope.STARTUP, prepared.startedNanos)) {
      return deadlineExpiredRunOutcomes(
        prepared,
        "deadline_scope=startup; delegated-worker preflight did not start before the startup deadline.",
      )
    }
    withFailureRecording(
      onFailure = { error ->
        if (error !is InterruptedException) {
          prepared.lifecycleReview?.let { recordPreflightFailure(it, prepared.relaunchableRequests) }
        }
      },
      block = {
        preflightDelegatedWorkers(
          prepared.initial.request,
          prepared.initial.resolvedMode,
          prepared.relaunchableRequests,
        )
      },
    )
    if (prepared.lifecycleReview != null && deadlineExpired(policy, PolicyDeadlineScope.STARTUP, prepared.startedNanos)) {
      return deadlineExpiredRunOutcomes(
        prepared,
        "deadline_scope=startup; delegated-worker preflight exceeded the startup deadline.",
      )
    }
    val launchedOutcomes = withFailureRecording(
      onFailure = { error ->
        if (error !is InterruptedException) {
          prepared.lifecycleReview?.let(::recordCoordinatorCrash)
        }
      },
      block = {
        runLanes(
          prepared.initial.request,
          prepared.initial.detection.routed,
          prepared.initial.resolvedMode,
          prepared.launchRequests.groupBy { it.agentId },
          prepared.relaunchableRequests.groupBy { it.agentId },
          prepared.initial.agent1Id,
          prepared.initial.agent2Id,
          prepared.capacityPlan,
          initialActualWaves = prepared.actualWaves,
          actualWaves = { prepared.actualWaves = it },
          workerLaunchBoundary = {
            prepared.interruptionClassification =
              DelegatedReviewTerminalClassification.INTERRUPTED_DURING_WORKER
          },
          waveBoundary = {
            prepared.interruptionClassification =
              DelegatedReviewTerminalClassification.INTERRUPTED_BETWEEN_WAVES
          },
          startedNanos = prepared.startedNanos,
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

  private fun deadlineExpiredRunOutcomes(
    prepared: PreparedRun,
    message: String,
  ): ParallelReviewLaneRunResult {
    val outcomes = prepared.relaunchableRequests.associate { launch ->
      launch.assignment.digest to deadlineExpiredOutcome(launch, message)
    }
    fun lane(agentId: String) = aggregateDelegatedLaneOutcome(
      agentId,
      prepared.launchRequests.filter { it.agentId == agentId }.mapNotNull { outcomes[it.assignment.digest] },
    )
    val timeoutResult = ParallelReviewLaneRunResult(
      lane1 = lane(prepared.initial.agent1Id),
      lane2 = lane(prepared.initial.agent2Id),
    )
    return prepared.recovery?.let { recovery ->
      rebuildRecoveredOutcomes(
        prepared.launchRequests,
        prepared.relaunchableRequests,
        recovery,
        timeoutResult,
        prepared.initial.agent1Id,
        prepared.initial.agent2Id,
      )
    } ?: timeoutResult
  }

  private fun recordAggregationStarted(prepared: PreparedRun) {
    if (prepared.recovery?.terminalRecord != null) return
    prepared.aggregationStartedNanos = monotonicNowNanos()
    prepared.interruptionClassification = DelegatedReviewTerminalClassification.INTERRUPTED_DURING_AGGREGATION
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
    val completion = prepared.lifecycleReview?.let {
      recordReviewCompletion(
        it,
        prepared.recovery,
        outcomes,
        prepared.launchRequests,
        prepared.aggregationStartedNanos ?: monotonicNowNanos(),
        effectiveDeadlinePolicy(prepared.initial.request),
        prepared.startedNanos,
      )
    } ?: ReviewCompletionResult(accepted = true)
    val aggregationAccepted = completion.accepted
    val effectiveOutcomes = outcomes.takeUnless { aggregationAccepted == false }
      ?: outcomes.blockedByAggregation()
    val result = parallelResult(prepared.initial.agent1Id, prepared.initial.agent2Id, effectiveOutcomes)
    result.accountingSummary?.let { summary ->
      database.transaction { unitOfWork ->
        unitOfWork.reviews.saveAccounting(
          ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary.toBoundedPayload()),
        )
      }
    }
    val interruptionClassification = if (aggregationAccepted == false && interruptionProbe()) {
      interruptionBoundary(prepared)
    } else {
      null
    }
    persistLifecycleProjection(
      prepared,
      effectiveOutcomes,
      interruptionClassification,
      completion.terminalRecord?.let(::listOf).orEmpty(),
    )
    return result
  }

  private fun persistLifecycleProjection(
    prepared: PreparedRun,
    outcomes: ParallelReviewLaneRunResult,
    terminalClassificationOverride: DelegatedReviewTerminalClassification? = null,
    boundaryRecords: List<ReviewLifecycleRecord> = emptyList(),
  ) {
    val lifecycleReview = prepared.lifecycleReview ?: return
    val selected = prepared.launchRequests
    if (selected.isEmpty()) return
    val plan = prepared.capacityPlan
    val lifecycleEvents = database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(lifecycleReview.assignment.reviewId)
    }
    fun buildSnapshot(events: List<ReviewLifecycleEvent>): DelegatedReviewLifecycleSnapshot {
    val aggregationCompleted = events.any {
      it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED
    }
    val latestByAssignment = events
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
        area = request.assignment.lane,
        state = projectedState,
        diagnostic = diagnostic?.take(REVIEW_LIFECYCLE_MAX_TEXT_CHARS),
      )
    }
    val actualWavePlan = deriveActualWaves(
      selected,
      events,
      plan.workerSlots,
    )
    require(actualWavePlan.map { it.number } == actualWavePlan.indices.map { it + 1 }) {
      "Persisted actual waves must be contiguous and coordinator-owned."
    }
    require(actualWavePlan.all { it.workerIds.size <= plan.workerSlots }) {
      "Persisted actual wave membership exceeds worker capacity after reserving the coordinator slot."
    }
    require(actualWavePlan.flatMap { it.workerIds }.distinct().size == actualWavePlan.flatMap { it.workerIds }.size) {
      "Persisted actual waves duplicate a selected worker."
    }
    require(actualWavePlan.flatMap { it.workerIds }.all { digest -> selected.any { it.assignment.digest == digest } }) {
      "Persisted actual waves contain an unselected worker."
    }
    val actualWaves = actualWavePlan.map { wave ->
      DelegatedReviewWaveRecord(
        waveNumber = wave.number,
        workerIds = wave.workerIds.map { digest ->
          selected.first { it.assignment.digest == digest }.let { request ->
            "${request.agentId}:${request.assignment.lane}"
          }
        },
      )
    }
    val processStartedAssignments = events
      .filter { it.assignmentDigest != null }
      .filter { event ->
        selected.any {
          it.assignment.digest == event.assignmentDigest && it.attempt == event.attempt
        } &&
        event.livenessObservations.any { observation ->
          observation.kind == ReviewLivenessObservation.Kind.PROCESS_STARTED
        }
      }
      .mapNotNull { it.assignmentDigest }
      .toSet()
    val mcpStartupAssignments = events
      .filter { it.assignmentDigest != null }
      .filter { event ->
        selected.any {
          it.assignment.digest == event.assignmentDigest && it.attempt == event.attempt
        } &&
        event.livenessObservations.any { observation ->
          observation.kind == ReviewLivenessObservation.Kind.MCP_STARTUP
        }
      }
      .mapNotNull { it.assignmentDigest }
      .toSet()
    val processCount = processStartedAssignments.size
    val mcpStartupCount = mcpStartupAssignments.size
    val lifecycleTimes = events.mapNotNull { event ->
      runCatching { Instant.parse(event.occurredAt).toEpochMilli() }.getOrNull()
    }
    val elapsedMs = lifecycleTimes.minOrNull()?.let { start ->
      lifecycleTimes.maxOrNull()?.minus(start)
    } ?: 0L
    val terminalStatus = events.lastOrNull {
      it.component == ReviewLifecycleComponent.TERMINAL
    }?.terminalCompletion?.status
    val terminalClassification = terminalClassificationOverride ?: when {
      terminalStatus == ReviewProcessOutcome.TIMED_OUT -> DelegatedReviewTerminalClassification.TIMED_OUT
      terminalStatus == ReviewProcessOutcome.INTERRUPTED && aggregationCompleted ->
        DelegatedReviewTerminalClassification.INTERRUPTED_BEFORE_TERMINAL_PERSISTENCE
      terminalStatus == ReviewProcessOutcome.INTERRUPTED ->
        DelegatedReviewTerminalClassification.INTERRUPTED_DURING_WORKER
      events.any { it.eventKind == ReviewLifecycleEventKind.WORKER_UNAVAILABLE } ->
        DelegatedReviewTerminalClassification.BLOCKED_UNSUPPORTED
      events.any { it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED } ->
        DelegatedReviewTerminalClassification.BLOCKED_AGGREGATION
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
    val deadlines = effectiveDeadlinePolicy(prepared.initial.request).let { policy ->
      PolicyDeadlineScope.entries.map { scope ->
        DelegatedReviewDeadline(LifecycleDeadlineScope.valueOf(scope.name), policy.limitMs(scope))
      }
    }
    return DelegatedReviewLifecycleSnapshot(
      reviewId = lifecycleReview.assignment.reviewId,
      packetDigest = lifecycleReview.assignment.packetDigest,
      selectedAreaCount = selected.size,
      predictedWaveCount = plan.predictedWaveCount,
      actualWaveCount = actualWaves.size,
      coordinatorSlots = plan.coordinatorSlots,
      workers = allWorkers,
      waves = actualWaves,
      deadlines = deadlines,
      metrics = DelegatedReviewLifecycleMetrics(
        elapsedMs = elapsedMs,
        totalTokens = listOf(outcomes.lane1, outcomes.lane2).flatMap { it.specialistAccounting }
          .sumOf { it.providerUsage?.totalTokens ?: 0L },
        processCount = processCount,
        mcpStartupCount = mcpStartupCount,
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
    }
    if (boundaryRecords.isEmpty()) {
      database.transaction { unitOfWork ->
        unitOfWork.reviews.saveDelegatedReviewLifecycle(buildSnapshot(lifecycleEvents))
      }
    } else {
      lifecycleRecorder.recordAllAndPersist(boundaryRecords) { unitOfWork, events ->
        unitOfWork.reviews.saveDelegatedReviewLifecycle(buildSnapshot(events))
      }
    }
  }

  private fun deriveActualWaves(
    selected: List<DelegatedReviewLaunchRequest>,
    lifecycleEvents: List<ReviewLifecycleEvent>,
    workerSlots: Int,
  ): List<skillbill.review.plan.DelegatedReviewWave> {
    val selectedDigests = selected.map { it.assignment.digest }.toSet()
    val launchedEvents = lifecycleEvents
      .filter { it.eventKind == ReviewLifecycleEventKind.WORKER_LAUNCHED }
      .sortedBy(ReviewLifecycleEvent::sequence)
      .filter { it.assignmentDigest?.let(selectedDigests::contains) == true }
      .distinctBy { it.assignmentDigest }
    val explicitlyRecorded = launchedEvents.all { it.waveNumber != null }
    if (explicitlyRecorded && launchedEvents.isNotEmpty()) {
      return launchedEvents
        .groupBy { requireNotNull(it.waveNumber) }
        .toSortedMap()
        .map { (number, events) ->
          skillbill.review.plan.DelegatedReviewWave(
            number,
            events.mapNotNull { it.assignmentDigest },
          )
        }
    }
    val launched = launchedEvents.mapNotNull { it.assignmentDigest }
    return launched.chunked(workerSlots).mapIndexed { index, workerIds ->
      skillbill.review.plan.DelegatedReviewWave(index + 1, workerIds)
    }
  }

  private fun recordReviewCompletion(
    launch: DelegatedReviewLaunchRequest,
    recovery: ReviewLifecycleRecoverySnapshot?,
    outcomes: ParallelReviewLaneRunResult,
    selectedAssignments: List<DelegatedReviewLaunchRequest>,
    aggregationStartedNanos: Long,
    policy: DelegatedReviewDeadlinePolicy,
    runStartedNanos: Long,
  ): ReviewCompletionResult {
    recovery?.terminalRecord?.let { terminal ->
      return ReviewCompletionResult(terminal.terminalCompletion?.status == ReviewProcessOutcome.ZERO_EXIT)
    }
    val lifecycleEvents = database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(launch.assignment.reviewId)
    }
    val existingAggregation = lifecycleEvents.lastOrNull {
      it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED ||
        it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED
    }
    if (existingAggregation?.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED) {
      return ReviewCompletionResult(
        accepted = false,
        terminalRecord = terminalCompletionRecord(launch, recovery, false),
      )
    }
    val aggregationElapsedMs =
      ((monotonicNowNanos() - aggregationStartedNanos) / 1_000_000L).coerceAtLeast(0)
    val aggregationDeadlineExceeded = policy.isExpired(PolicyDeadlineScope.AGGREGATION, aggregationElapsedMs)
    val wholeReviewDeadlineExceeded = policy.isExpired(
      PolicyDeadlineScope.WHOLE_REVIEW,
      elapsedMillis(runStartedNanos),
    )
    val deadlineScope = when {
      wholeReviewDeadlineExceeded -> LifecycleDeadlineScope.WHOLE_REVIEW
      aggregationDeadlineExceeded -> LifecycleDeadlineScope.AGGREGATION
      else -> null
    }
    val gateFailure = runCatching {
      require(deadlineScope == null) {
        "deadline_scope=${deadlineScope?.name?.lowercase()}; aggregation gate did not start before its deadline."
      }
      val receipt = DelegatedReviewAggregationGate.validate(
        buildAggregationRequest(selectedAssignments, lifecycleEvents),
      )
      require(!policy.isExpired(PolicyDeadlineScope.AGGREGATION, elapsedMillis(aggregationStartedNanos))) {
        "deadline_scope=aggregation; aggregation gate exceeded its deadline."
      }
      require(!policy.isExpired(PolicyDeadlineScope.WHOLE_REVIEW, elapsedMillis(runStartedNanos))) {
        "deadline_scope=whole_review; aggregation gate exceeded the whole-review deadline."
      }
      receipt
    }.exceptionOrNull()
    val aggregationDeadlineExceededAfterGate = policy.isExpired(
      PolicyDeadlineScope.AGGREGATION,
      elapsedMillis(aggregationStartedNanos),
    )
    val wholeReviewDeadlineExceededAfterGate = policy.isExpired(
      PolicyDeadlineScope.WHOLE_REVIEW,
      elapsedMillis(runStartedNanos),
    )
    val successful = outcomes.lane1.success && outcomes.lane2.success && gateFailure == null &&
      !aggregationDeadlineExceededAfterGate && !wholeReviewDeadlineExceededAfterGate
    val failureStatus = when {
      successful -> null
      deadlineScope != null || aggregationDeadlineExceededAfterGate || wholeReviewDeadlineExceededAfterGate ->
        ReviewProcessOutcome.TIMED_OUT
      lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.WORKER_TIMED_OUT } ->
        ReviewProcessOutcome.TIMED_OUT
      lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.WORKER_CANCELLED } ->
        lifecycleEvents.first { it.eventKind == ReviewLifecycleEventKind.WORKER_CANCELLED }
          .processOutcome ?: ReviewProcessOutcome.INTERRUPTED
      lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.WORKER_UNAVAILABLE } ->
        ReviewProcessOutcome.UNAVAILABLE
      else -> null
    }
    if (interruptionProbe()) {
      if (existingAggregation?.eventKind != ReviewLifecycleEventKind.AGGREGATION_COMPLETED) {
        recordAggregationCompletion(
          launch,
          successful = false,
          failureMessage = "Aggregation interrupted before terminal persistence.",
          failureStatus = ReviewProcessOutcome.INTERRUPTED,
        )
      }
      if (recovery?.terminalRecord == null) {
        return ReviewCompletionResult(
          accepted = false,
          terminalRecord = terminalCompletionRecord(launch, recovery, false, ReviewProcessOutcome.INTERRUPTED),
        )
      }
      return ReviewCompletionResult(accepted = false)
    }
    if (existingAggregation?.eventKind != ReviewLifecycleEventKind.AGGREGATION_COMPLETED) {
      recordAggregationCompletion(
        launch,
        successful,
        if (successful) {
          null
        } else {
          gateFailure?.message ?: outcomes.lane1.failureReason ?: outcomes.lane2.failureReason ?:
            "The durable worker outcomes did not satisfy aggregation readiness."
        },
        failureStatus = failureStatus,
      )
    }
    if (interruptionProbe()) {
      if (recovery?.terminalRecord == null) {
        return ReviewCompletionResult(
          accepted = false,
          terminalRecord = terminalCompletionRecord(launch, recovery, false, ReviewProcessOutcome.INTERRUPTED),
        )
      }
      return ReviewCompletionResult(accepted = false)
    }
    return ReviewCompletionResult(
      accepted = successful,
      terminalRecord = terminalCompletionRecord(launch, recovery, successful, failureStatus),
    )
  }

  private fun buildAggregationRequest(
    selectedAssignments: List<DelegatedReviewLaunchRequest>,
    lifecycleEvents: List<ReviewLifecycleEvent>,
  ): DelegatedReviewAggregationRequest {
    val selectedOwnership = selectedAssignments.map { launch ->
      DelegatedReviewAssignmentOwnership(
        workerId = launch.assignment.lane,
        providerId = launch.agentId,
        attempt = launch.attempt,
        assignmentDigest = launch.assignment.digest,
        area = launch.assignment.lane,
      )
    }
    val selectedByDigest = selectedAssignments.associateBy { it.assignment.digest }
    val completedResultEventsByDigest = lifecycleEvents
      .filter {
        it.component == ReviewLifecycleComponent.WORKER &&
          it.eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED &&
          it.resultEnvelope != null
      }
      .groupBy { it.assignmentDigest }
    val currentResultEvents = completedResultEventsByDigest.flatMap { (digest, events) ->
        val launch = selectedByDigest[digest]
        require(launch != null) {
          "Aggregation rejects a completed result for an assignment outside the current selection."
        }
        require(events.all { it.attempt == launch.attempt }) {
          "Aggregation rejects a stale completed result for assignment '$digest'."
        }
        val currentAttemptEvents = events.filter { it.attempt == launch.attempt }
        require(currentAttemptEvents.size <= 1) {
          "Aggregation rejects duplicate completed results for assignment '$digest'."
        }
        currentAttemptEvents
      }
    val workerResults = currentResultEvents.map { event ->
      require(event.processOutcome == ReviewProcessOutcome.ZERO_EXIT) {
        "Aggregation rejects a completed result without a normal zero-exit outcome."
      }
      val identity = DelegatedReviewAssignmentOwnership(
        workerId = requireNotNull(event.workerId),
        providerId = requireNotNull(event.providerId),
        attempt = requireNotNull(event.attempt),
        assignmentDigest = requireNotNull(event.assignmentDigest),
        area = requireNotNull(event.routedArea),
      )
      DelegatedReviewWorkerResult(
        identity = identity,
        state = DelegatedReviewAggregationState.COMPLETED,
        findings = event.resultEnvelope?.findings.orEmpty().map { finding ->
          DelegatedReviewFindingEnvelope(
            severity = finding.severity.name.lowercase(),
            confidence = finding.confidence,
            repositoryPath = finding.repositoryPath ?: finding.location.substringBefore(':'),
            line = finding.line ?: 1,
            description = finding.description,
          )
        },
      )
    }
    val packet = selectedAssignments.first().packet
    require(selectedAssignments.all { it.packet.digest == packet.digest }) {
      "Aggregation selection mixes review packets."
    }
    val declaredAreas = packet.selectedLanes.toSet()
    return DelegatedReviewAggregationRequest(
      selectedAssignments = selectedOwnership,
      declaredAreas = declaredAreas,
      workerResults = workerResults,
    )
  }

  private fun recordAggregationCompletion(
    launch: DelegatedReviewLaunchRequest,
    successful: Boolean,
    failureMessage: String? = null,
    failureStatus: ReviewProcessOutcome? = null,
  ) {
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
        processOutcome = if (successful) {
          ReviewProcessOutcome.ZERO_EXIT
        } else {
          failureStatus ?: ReviewProcessOutcome.AGGREGATION_FAILURE
        },
        terminalCompletion = ReviewTerminalCompletion(
          lifecycleRecorder.timestamp(),
          ReviewProcessOutcome.ZERO_EXIT,
        ).takeIf { successful },
        diagnostic = failureMessage?.let { message ->
          ReviewDiagnosticReference(
            "review-lifecycle/${launch.assignment.reviewId}/aggregation",
            "Aggregation blocked: ${message.take(REVIEW_LIFECYCLE_MAX_TEXT_CHARS - 21)}",
          )
        },
      ),
    )
  }

  private fun terminalCompletionRecord(
    launch: DelegatedReviewLaunchRequest,
    recovery: ReviewLifecycleRecoverySnapshot?,
    successful: Boolean,
    failureStatus: ReviewProcessOutcome? = null,
  ): ReviewLifecycleRecord {
    val terminalCompletion = terminalCompletionFor(recovery, successful, failureStatus)
    val terminalEventKind = when (terminalCompletion.status) {
      ReviewProcessOutcome.ZERO_EXIT -> ReviewLifecycleEventKind.TERMINAL_COMPLETED
      ReviewProcessOutcome.TIMED_OUT -> ReviewLifecycleEventKind.TERMINAL_TIMED_OUT
      ReviewProcessOutcome.INTERRUPTED -> ReviewLifecycleEventKind.TERMINAL_CANCELLED
      else -> ReviewLifecycleEventKind.TERMINAL_FAILED
    }
    return ReviewLifecycleRecord(
      reviewId = launch.assignment.reviewId,
      packetDigest = launch.assignment.packetDigest,
      component = ReviewLifecycleComponent.TERMINAL,
      eventKind = terminalEventKind,
      processOutcome = terminalCompletion.status,
      terminalCompletion = terminalCompletion,
    )
  }

  private inline fun <T> withFailureRecording(onFailure: (Throwable) -> Unit, block: () -> T): T =
    runCatching(block).getOrElse { error ->
      onFailure(error)
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
    selected: Map<String, List<DelegatedReviewLaunchRequest>>,
    relaunchable: Map<String, List<DelegatedReviewLaunchRequest>>,
    agent1Id: String,
    agent2Id: String,
    capacityPlan: DelegatedReviewCapacityPlan,
    initialActualWaves: List<skillbill.review.plan.DelegatedReviewWave>,
    actualWaves: (List<skillbill.review.plan.DelegatedReviewWave>) -> Unit,
    workerLaunchBoundary: () -> Unit,
    waveBoundary: () -> Unit,
    startedNanos: Long,
  ): skillbill.ports.review.model.ParallelReviewLaneRunResult {
    if (resolvedMode == ResolvedReviewExecutionMode.DELEGATED) {
      return launchDelegatedLane(
        selectedLaunchRequests = selected.values.flatten()
          .sortedBy { it.assignment.laneDecision.orderIndex },
        relaunchableLaunchRequests = relaunchable.values.flatten()
          .sortedBy { it.assignment.laneDecision.orderIndex },
        request = request,
        modelOverrides = mapOf(agent2Id to request.agent2Model),
        agent1Id = agent1Id,
        agent2Id = agent2Id,
        capacityPlan = capacityPlan,
        initialActualWaves = initialActualWaves,
        actualWaves = actualWaves,
        workerLaunchBoundary = workerLaunchBoundary,
        waveBoundary = waveBoundary,
        startedNanos = startedNanos,
      )
    }
    val timeoutSec = request.timeout?.inWholeSeconds ?: DEFAULT_TIMEOUT_MINUTES * SECONDS_PER_MINUTE
    return parallelLaneRunner.runTwoLanes(
      ParallelReviewLaneRunRequest(
        lane1 = {
          launchResolvedLane(
            resolvedMode,
            relaunchable[agent1Id].orEmpty(),
            agent1Id,
            routedManifests,
            request,
          )
        },
        lane2 = {
          launchResolvedLane(
            resolvedMode,
            relaunchable[agent2Id].orEmpty(),
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
    failureStatus: ReviewProcessOutcome? = null,
  ): ReviewTerminalCompletion {
    recovery?.terminalRecord?.terminalCompletion?.let { return it }
    if (!successful && failureStatus != null) {
      return skillbill.ports.review.model.ReviewTerminalCompletion(
        lifecycleRecorder.timestamp(),
        failureStatus,
      )
    }
    val persistedAggregation = recovery?.aggregationEvent
    if (persistedAggregation?.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED) {
      return requireNotNull(persistedAggregation.terminalCompletion) {
        "A durable aggregation completion must carry its terminal completion evidence."
      }
    }
    val status = if (successful) ReviewProcessOutcome.ZERO_EXIT else {
      failureStatus ?: ReviewProcessOutcome.AGGREGATION_FAILURE
    }
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
    ResolvedReviewExecutionMode.DELEGATED -> error("Delegated lanes are scheduled by the coordinator.")
  }

  @Suppress("LoopWithTooManyJumpStatements")
  private fun launchDelegatedLane(
    selectedLaunchRequests: List<DelegatedReviewLaunchRequest>,
    relaunchableLaunchRequests: List<DelegatedReviewLaunchRequest>,
    request: ParallelCodeReviewRequest,
    modelOverrides: Map<String, String?>,
    agent1Id: String,
    agent2Id: String,
    capacityPlan: DelegatedReviewCapacityPlan,
    initialActualWaves: List<skillbill.review.plan.DelegatedReviewWave>,
    actualWaves: (List<skillbill.review.plan.DelegatedReviewWave>) -> Unit,
    workerLaunchBoundary: () -> Unit,
    waveBoundary: () -> Unit,
    startedNanos: Long,
  ): skillbill.ports.review.model.ParallelReviewLaneRunResult {
    if (selectedLaunchRequests.isEmpty()) {
      return skillbill.ports.review.model.ParallelReviewLaneRunResult(
        lane1 = emptyDelegatedLaneOutcome("Durable lifecycle recovery withheld every specialist launch."),
        lane2 = emptyDelegatedLaneOutcome("Durable lifecycle recovery withheld every specialist launch."),
      )
    }
    val outcomesByDigest = linkedMapOf<String, ParallelReviewLaneOutcome>()
    val requestByDigest = selectedLaunchRequests.associateBy { it.assignment.digest }
    val relaunchableIds = relaunchableLaunchRequests.map { it.assignment.digest }.toSet()
    val policy = effectiveDeadlinePolicy(request)
    val recordedWaves = initialActualWaves.toMutableList()
    var interrupted = false
    val coordinatorPlan = capacityPlan
    for (wave in coordinatorPlan.predictedWaves) {
      if (interrupted) break
      require(wave.workerIds.size <= coordinatorPlan.workerSlots)
      if (interruptionProbe()) {
        interrupted = true
        break
      }
      val waveLaunchableIds = wave.workerIds.filter(relaunchableIds::contains)
      if (waveLaunchableIds.isEmpty()) continue
      if (deadlineExpired(policy, PolicyDeadlineScope.STARTUP, startedNanos) &&
        outcomesByDigest.isEmpty()
      ) {
        relaunchableLaunchRequests.forEach { launch ->
          outcomesByDigest[launch.assignment.digest] = deadlineExpiredOutcome(
            launch,
            "deadline_scope=startup; the first worker launch missed the startup deadline.",
          )
        }
        break
      }
      if (deadlineExpired(policy, PolicyDeadlineScope.WHOLE_REVIEW, startedNanos)) {
        relaunchableLaunchRequests.filter { it.assignment.digest !in outcomesByDigest }.forEach { launch ->
          outcomesByDigest[launch.assignment.digest] = deadlineExpiredOutcome(
            launch,
            "deadline_scope=whole_review; wave ${wave.number} missed the whole-review deadline.",
          )
        }
        break
      }
      val launchedInWave = ConcurrentHashMap.newKeySet<String>()
      val actualWaveNumber = wave.number
      val executor = Executors.newFixedThreadPool(waveLaunchableIds.size)
      try {
        val futures = executor.invokeAll(
          waveLaunchableIds.map { workerId ->
            Callable {
              val launchRequest = requireNotNull(requestByDigest[workerId])
              val remainingWholeMs = remainingMillis(policy, PolicyDeadlineScope.WHOLE_REVIEW, startedNanos)
              if (remainingWholeMs <= 0) {
                deadlineExpiredOutcome(
                  launchRequest,
                  "deadline_scope=whole_review; worker launch missed the whole-review deadline.",
                )
              } else {
                val workerTimeout = minOf(
                  remainingWholeMs,
                  policy.limitMs(PolicyDeadlineScope.PER_WORKER),
                ).milliseconds
                val workerDeadlineScope = if (remainingWholeMs < policy.limitMs(PolicyDeadlineScope.PER_WORKER)) {
                  LifecycleDeadlineScope.WHOLE_REVIEW
                } else {
                  LifecycleDeadlineScope.PER_WORKER
                }
                workerLaunchBoundary()
                launchSpecialist(
                  launchRequest,
                  request,
                  modelOverrides[launchRequest.agentId],
                  workerTimeout,
                  policy.limitMs(PolicyDeadlineScope.PROGRESS_IDLE).milliseconds,
                  actualWaveNumber,
                  workerDeadlineScope,
                  onLaunchRecorded = { launchedInWave.add(workerId) },
                )
              }
            }
          },
        )
        futures.forEachIndexed { index, future ->
          val workerId = waveLaunchableIds[index]
          val outcome = try {
            future.get()
          } catch (error: ExecutionException) {
            if (error.cause is InterruptedException) {
              interrupted = true
              null
            } else {
              outcomesByDigest[workerId] = workerExecutionExceptionOutcome(
                requireNotNull(requestByDigest[workerId]),
                error.cause ?: error,
              )
              null
            }
          }
          outcome?.let {
            outcomesByDigest[workerId] = it
            if (it.interrupted) interrupted = true
          }
        }
      } finally {
        executor.shutdownNow()
      }
      val actualWaveWorkers = waveLaunchableIds.filter(launchedInWave::contains)
      if (actualWaveWorkers.isNotEmpty()) {
        val existingWaveIndex = recordedWaves.indexOfFirst { it.number == wave.number }
        val priorWorkerIds = recordedWaves.getOrNull(existingWaveIndex)?.workerIds.orEmpty()
        val workerIds = (priorWorkerIds + actualWaveWorkers).distinct()
        val actualWave = skillbill.review.plan.DelegatedReviewWave(
          number = wave.number,
          workerIds = workerIds,
        )
        if (existingWaveIndex >= 0) {
          recordedWaves[existingWaveIndex] = actualWave
        } else {
          recordedWaves += actualWave
          recordedWaves.sortBy(skillbill.review.plan.DelegatedReviewWave::number)
        }
        actualWaves(recordedWaves.toList())
        waveBoundary()
      }
    }
    relaunchableLaunchRequests.filter { it.assignment.digest !in outcomesByDigest }.forEach { launch ->
      outcomesByDigest[launch.assignment.digest] = interruptedOutcome(
        launch,
        "Delegated review stopped before this worker reached its launch boundary.",
      )
    }
    fun laneOutcome(agentId: String) = aggregateDelegatedLaneOutcome(
      agentId,
      selectedLaunchRequests.filter { it.agentId == agentId }
        .mapNotNull { outcomesByDigest[it.assignment.digest] },
    )
    return skillbill.ports.review.model.ParallelReviewLaneRunResult(
      lane1 = laneOutcome(agent1Id),
      lane2 = laneOutcome(agent2Id),
    )
  }

  private fun deadlineExpiredOutcome(
    launchRequest: DelegatedReviewLaunchRequest,
    message: String = "Worker deadline expired before launch; no provider result was admitted.",
  ): ParallelReviewLaneOutcome {
    recordWorkerLifecycle(
      launchRequest,
      ReviewLifecycleEventKind.WORKER_TIMED_OUT,
      ReviewWorkerLifecycleState.TIMED_OUT,
      ReviewProcessOutcome.TIMED_OUT,
      ReviewDiagnosticReference(
        "review-lifecycle/${launchRequest.assignment.reviewId}/" +
          launchRequest.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH),
        message,
      ),
    )
    return ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = message,
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

  private fun workerExecutionExceptionOutcome(
    launchRequest: DelegatedReviewLaunchRequest,
    error: Throwable,
  ): ParallelReviewLaneOutcome {
    val detail = "Worker launch failed with ${error::class.simpleName ?: "unknown exception"}: " +
      (error.message?.takeIf(String::isNotBlank) ?: "no detail")
    recordWorkerLifecycle(
      launchRequest,
      ReviewLifecycleEventKind.WORKER_FAILED,
      ReviewWorkerLifecycleState.FAILED,
      ReviewProcessOutcome.NON_ZERO_EXIT,
      ReviewDiagnosticReference(
        "review-lifecycle/${launchRequest.assignment.reviewId}/" +
          launchRequest.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH),
        detail.take(REVIEW_LIFECYCLE_MAX_TEXT_CHARS),
      ),
    )
    return ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = detail,
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
        terminalStatus = "execution_exception",
      ),
    )
  }

  private fun emptyDelegatedLaneOutcome(reason: String) = ParallelReviewLaneOutcome(
    success = false,
    rawOutput = "",
    failureReason = reason,
  )

  private fun interruptedOutcome(
    launchRequest: DelegatedReviewLaunchRequest,
    reason: String,
  ) = ParallelReviewLaneOutcome(
    success = false,
    rawOutput = "",
    failureReason = reason,
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
      terminalStatus = "interrupted",
    ),
  )

  private fun aggregateDelegatedLaneOutcome(
    agentId: String,
    outcomes: List<ParallelReviewLaneOutcome>,
  ): ParallelReviewLaneOutcome {
    val failed = outcomes.firstOrNull { !it.success }
    return ParallelReviewLaneOutcome(
      success = outcomes.isNotEmpty() && failed == null,
      rawOutput = outcomes.filter { it.success }.joinToString("\n") { it.rawOutput },
      findings = outcomes.filter { it.success }.flatMap { it.findings },
      failureReason = failed?.failureReason,
      tokenUsage = outcomes.singleOrNull()?.tokenUsage,
      budgetOutcome = failed?.budgetOutcome,
      accounting = aggregateAccounting(agentId, outcomes.mapNotNull { it.accounting }),
      specialistAccounting = outcomes.flatMap { it.specialistAccounting },
    )
  }

  private fun effectiveDeadlinePolicy(request: ParallelCodeReviewRequest): DelegatedReviewDeadlinePolicy =
    request.timeout?.inWholeMilliseconds?.let { requestedWholeMs ->
      delegatedReviewDeadlinePolicy.copy(
        wholeReviewMs = minOf(delegatedReviewDeadlinePolicy.wholeReviewMs, requestedWholeMs),
      )
    } ?: delegatedReviewDeadlinePolicy

  private fun deadlineExpired(
    policy: DelegatedReviewDeadlinePolicy,
    scope: PolicyDeadlineScope,
    startedNanos: Long,
  ): Boolean = policy.isExpired(scope, elapsedMillis(startedNanos))

  private fun remainingMillis(
    policy: DelegatedReviewDeadlinePolicy,
    scope: PolicyDeadlineScope,
    startedNanos: Long,
  ): Long = (policy.limitMs(scope) - elapsedMillis(startedNanos)).coerceAtLeast(0)

  private fun elapsedMillis(startedNanos: Long): Long =
    ((monotonicNowNanos() - startedNanos) / 1_000_000L).coerceAtLeast(0)

  @Suppress("LongMethod")
  private fun launchSpecialist(
    launchRequest: DelegatedReviewLaunchRequest,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
    timeout: kotlin.time.Duration = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
    progressIdleTimeout: kotlin.time.Duration? = null,
    waveNumber: Int? = null,
    fallbackDeadlineScope: LifecycleDeadlineScope? = null,
    onLaunchRecorded: () -> Unit = {},
  ): ParallelReviewLaneOutcome {
    recordWorkerLifecycle(
      launchRequest,
      ReviewLifecycleEventKind.WORKER_LAUNCHED,
      ReviewWorkerLifecycleState.LAUNCHED,
      ReviewProcessOutcome.NOT_STARTED,
      waveNumber = waveNumber,
    )
    onLaunchRecorded()
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
          progressIdleTimeout = progressIdleTimeout,
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
        recordWorkerOutcome(
          launchRequest,
          execution,
          outputEnvelopeValid,
          fallbackDeadlineScope = fallbackDeadlineScope,
        )
        ParallelReviewLaneOutcome(
          success = false,
          rawOutput = "",
          failureReason = describeBudgetOutcome(execution.budgetOutcome),
          budgetOutcome = execution.budgetOutcome,
          accounting = execution.accounting,
        )
      }
      is DelegatedReviewExecutionOutcome.Completed ->
        completedSpecialistOutcome(launchRequest, execution, outputEnvelopeValid, fallbackDeadlineScope)
    }
  }

  private fun completedSpecialistOutcome(
    launchRequest: DelegatedReviewLaunchRequest,
    execution: DelegatedReviewExecutionOutcome.Completed,
    outputEnvelopeValid: Boolean?,
    fallbackDeadlineScope: LifecycleDeadlineScope? = null,
  ): ParallelReviewLaneOutcome {
    val worker = execution.worker
    worker.budgetOutcome?.takeIf { worker.facts == null }?.let { budgetOutcome ->
      recordWorkerOutcome(
        launchRequest,
        execution,
        outputEnvelopeValid,
        fallbackDeadlineScope = fallbackDeadlineScope,
      )
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
      fallbackDeadlineScope = fallbackDeadlineScope,
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
    waveNumber: Int? = null,
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
        routedArea = launchRequest.assignment.lane,
        waveNumber = waveNumber,
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
    fallbackDeadlineScope: LifecycleDeadlineScope? = null,
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
      workerDiagnostic(launchRequest, outcome, facts?.liveness?.reason, fallbackDeadlineScope),
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

  private fun workerDiagnostic(
    launchRequest: DelegatedReviewLaunchRequest,
    outcome: ReviewProcessOutcome,
    deadlineReason: String? = null,
    fallbackDeadlineScope: LifecycleDeadlineScope? = null,
  ) =
    ReviewDiagnosticReference(
      "review-lifecycle/${launchRequest.assignment.reviewId}/" +
        launchRequest.assignment.digest.take(DIAGNOSTIC_DIGEST_PREFIX_LENGTH),
      when (outcome) {
        ReviewProcessOutcome.ZERO_EXIT -> "Worker returned a normal zero-exit result."
        ReviewProcessOutcome.TIMED_OUT ->
          "Worker deadline expired at scope=${deadlineScope(deadlineReason, fallbackDeadlineScope)} " +
            "before a terminal result."
        ReviewProcessOutcome.INTERRUPTED -> "Worker was interrupted before a terminal result."
        ReviewProcessOutcome.UNAVAILABLE -> "Provider launch was unavailable for this worker."
        ReviewProcessOutcome.INVALID_OUTPUT -> "Worker output was not admitted as a completed result."
        ReviewProcessOutcome.MISSING_RESULT -> "Worker exited normally without an explicit result envelope."
        ReviewProcessOutcome.NON_ZERO_EXIT -> "Worker exited without a successful process outcome."
        else -> "Worker did not produce a successful lifecycle result."
      },
    )

  private fun deadlineScope(reason: String?, fallback: LifecycleDeadlineScope? = null): String = when (reason) {
    "progress_idle_timeout", "operation_deadline_overrun" -> "progress_idle"
    "wall_clock_timeout" -> fallback?.name?.lowercase() ?: "per_worker"
    else -> fallback?.name?.lowercase() ?: reason?.take(REVIEW_LIFECYCLE_MAX_TEXT_CHARS) ?: "per_worker"
  }

  private fun reviewOutputEnvelopeValid(stdout: String): Boolean {
    val lines = stdout.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    if (lines.isEmpty()) return false
    if (lines.size == 1 && lines.single() == REVIEW_NO_FINDINGS_ENVELOPE) return true
    return lines.all { line -> ParallelReviewFindingParser.parallelFindingPattern.matches(line) }
  }

  private fun livenessObservations(facts: AgentRunLaunchFacts): List<ReviewLivenessObservation> = buildList {
    if (facts.processStarted) {
      addLifecycleObservation(
        ReviewLivenessObservation.Kind.PROCESS_STARTED,
        lifecycleRecorder.timestamp(),
        "process-started",
      )
    }
    if (facts.mcpStartupObserved) {
      addLifecycleObservation(
        ReviewLivenessObservation.Kind.MCP_STARTUP,
        lifecycleRecorder.timestamp(),
        "mcp-startup-observed",
      )
    }
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
    val WORKER_TERMINAL_EVENT_KINDS = setOf(
      ReviewLifecycleEventKind.WORKER_COMPLETED,
      ReviewLifecycleEventKind.WORKER_FAILED,
      ReviewLifecycleEventKind.WORKER_TIMED_OUT,
      ReviewLifecycleEventKind.WORKER_CANCELLED,
      ReviewLifecycleEventKind.WORKER_UNAVAILABLE,
      ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT,
    )
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

private fun ParallelReviewLaneOutcome.interruptedFor(
  classification: DelegatedReviewTerminalClassification,
) = copy(
  success = false,
  rawOutput = "",
  findings = emptyList(),
  failureReason = "Delegated review interrupted at ${classification.name.lowercase()}.",
  interrupted = true,
)

private fun ParallelReviewLaneRunResult.blockedByAggregation() = copy(
  lane1 = lane1.copy(
    success = false,
    rawOutput = "",
    findings = emptyList(),
    failureReason = lane1.failureReason ?: "Aggregation blocked by durable lifecycle validation.",
  ),
  lane2 = lane2.copy(
    success = false,
    rawOutput = "",
    findings = emptyList(),
    failureReason = lane2.failureReason ?: "Aggregation blocked by durable lifecycle validation.",
  ),
)

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
