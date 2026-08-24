package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.evidence.SharedReviewEvidenceCommits
import skillbill.application.evidence.SharedReviewEvidenceProjection
import skillbill.application.evidence.SharedReviewEvidenceQuery
import skillbill.application.evidence.SharedReviewEvidenceResolution
import skillbill.application.featuretask.RuntimeOwnedFindingVerdictsReadResolution
import skillbill.application.featuretask.RuntimeOwnedFactUnavailable
import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.featuretask.RuntimeOwnedReviewPassClaimsReadResolution
import skillbill.application.featuretask.resolveRuntimeOwnedFindingVerdicts
import skillbill.application.featuretask.resolveRuntimeOwnedReviewPassClaims
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.goalrunner.agentFailureExcerpt
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.ReviewLaneIntegrationInput
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.application.review.model.ReviewClaimVerificationOutcome
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewSpecAdjudicationOutcome
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.application.workflow.repoRoot
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.persistence.model.ReviewIntegrationPassRecord
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.ReviewLaneAggregation
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.ReviewStageDegradationSelection
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewCommitRoutingAccounting
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewIntegrationAccounting
import skillbill.review.context.model.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.ReviewLaneAssembledBundle
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleEntry
import skillbill.review.context.model.ReviewLaneCompletionState
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.ReviewParentAnalysisConsumption
import skillbill.review.context.model.ReviewRegisterParseSeamException
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.context.model.asFailedLaneRun
import skillbill.review.context.model.structuredString
import skillbill.review.context.model.toCodeReviewExecutionMode
import skillbill.review.context.model.withBrokerEvidenceRefusal
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneAggregationInput
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import skillbill.review.model.ReviewStageResumeReport
import skillbill.review.plan.ReviewCrossRootLaneReconciliation
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewPerAreaFallbackExclusion
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Inject
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class ParallelCodeReviewRunner(
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val diffResolver: DiffResolverPort,
  private val parallelLaneRunner: ParallelReviewLaneRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewRubricResolver: ReviewRubricResolver,
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  private val database: DatabaseSessionFactory,
  private val installedPackCatalog: InstalledPlatformPackCatalogPort = InstalledPlatformPackCatalogPort.NONE,
  /**
   * Absent in a fixture that wires no store: the review then derives its evidence in line exactly as
   * it did before the hoist, which is also the degradation path a cache miss takes.
   */
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort =
    FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE,
  private val specIntentProjectionResolver: SpecIntentProjectionResolver,
  private val reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  private val governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  private val nativeAgentPreflight: ReviewNativeAgentPreflightPort = ReviewNativeAgentPreflightPort.NONE,
  private val registerParse: (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)

  private data class InitialRun(
    val request: ParallelCodeReviewRequest,
    val detection: StackDetection,
    val resolvedMode: ResolvedReviewExecutionMode,
    val agent1Id: String,
    val agent2Id: String?,
    val preparedLaunchRequests: List<ReviewSpecialistLaunchRequest>,
    /**
     * Every lane the packet compiled, including lanes this resume did not re-launch because they
     * already hold a durable result. The integration pass reads this rather than the run set: a
     * resume that crashed between specialist completion and integration must still find the packet
     * and every lane summary, instead of reporting that there was nothing to integrate.
     */
    val compiledLaunchRequests: List<ReviewSpecialistLaunchRequest>,
    val budget: ReviewContextBudgetPolicy,
    val specIntentResolution: SpecIntentResolution,
  )

  /** The compiled lane set and the subset this attempt actually launches. */
  private data class CompiledLaunches(
    val all: List<ReviewSpecialistLaunchRequest>,
    val toRun: List<ReviewSpecialistLaunchRequest>,
    val specIntentResolution: SpecIntentResolution,
  )

  fun run(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    if (originalRequest.suppliedDiff != null && originalRequest.suppliedDiff.isBlank()) {
      return completeEmptySuppliedDelta(originalRequest)
    }
    val initial = prepareInitialRun(originalRequest)
    verifyNativeWorkers(initial)
    val outcomes = runLanes(initial)
    recordLaneDispositions(initial, outcomes)
    val integration = runIntegrationPass(initial, outcomes)
    val coverage = coverageReport(initial, outcomes, integration)
    val result = parallelResult(
      initial.agent1Id,
      initial.agent2Id,
      outcomes,
      integration,
      coverage,
      initial.compiledLaunchRequests.firstOrNull()?.packet,
      initial.budget,
      stageResumeReport(initial.request.reviewRunId),
    )
    persistReviewPassClaims(initial.request.reviewRunId, result.mergeResult.findings)
    recordReviewStageBoundary(initial.request.reviewRunId, integration, result.mergeResult.findings)
    recordMergedFindingLanes(initial.request.reviewRunId)
    val verificationVerdicts = runClaimVerification(initial, result)
    val adjudicationVerdicts = runSpecAdjudication(initial, result)
    val recordedVerdicts = recordedFindingVerdicts(
      initial.request.reviewRunId,
      verificationVerdicts + adjudicationVerdicts,
    )
    emitReviewStageDegradations(initial.request.reviewRunId, outcomes)
    val prose = result.output
    val assembled = ParallelReviewMerger.withRecordedVerdicts(result.mergeResult, recordedVerdicts)
      .copy(formattedOutput = prose)
    result.accountingSummary?.let { summary ->
      runtimeOwnedPersistence.requiredWrite(
        seam = "ParallelCodeReviewRunner.saveAccounting",
        expected = "runtime-owned review accounting",
      ) { unitOfWork ->
        unitOfWork.reviews.saveAccounting(
          ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary.toBoundedPayload()),
        )
      }
    }
    return result.copy(
      mergeResult = assembled,
      stageResume = stageResumeReport(initial.request.reviewRunId),
    )
  }

  private fun prepareInitialRun(originalRequest: ParallelCodeReviewRequest): InitialRun {
    val agent1 = resolveAgent(originalRequest.agent1Id, "--agent1")
    if (!originalRequest.agent2Id.isNullOrBlank()) {
      throw UsageValidationException(
        "Dual-agent parallel lanes are disconnected. Omit agent2Id; single-agent review uses agent1 only.",
      )
    }
    val revisions = resolveReviewRevisions(originalRequest)
    val sharedEvidence = SharedReviewEvidenceResolution(sharedEvidenceResolver, diffResolver).resolve(
      SharedReviewEvidenceQuery(
        repoRoot = originalRequest.repoRoot,
        workflowId = originalRequest.reviewRunId ?: SHARED_EVIDENCE_WORKFLOW_ID,
        scope = originalRequest.scope,
        range = ReviewCommitRange(revisions.first, revisions.second),
        suppliedDiff = hasSuppliedDiff(originalRequest),
      ),
    ) { resolveDiff(originalRequest, revisions) }
    val diffText = sharedEvidence.aggregateDiff
    val evidence = ReviewDiffEvidence.parse(diffText)
    val detection = detectStack(evidence)
    val budget = repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(originalRequest.repoRoot))
      .config.reviewContextBudget
    val lane1ResolvedMode = resolvedMode(originalRequest)
    val request = originalRequest.withResolvedTier(lane1ResolvedMode.toCodeReviewExecutionMode())
    val resolvedMode = ReviewExecutionModePolicy.resolve(request.lane2Tier)
    val compiled = prepare(
      request,
      revisions,
      diffText,
      evidence,
      sharedEvidence.sequence,
      detection.routed,
      detection.manifests,
      detection.ownedPathsBySlug,
      listOf(agent1.id),
      budget,
      sharedEvidence.storePath,
    )
    return InitialRun(
      request = request,
      detection = detection,
      resolvedMode = resolvedMode,
      agent1Id = agent1.id,
      agent2Id = null,
      preparedLaunchRequests = compiled.toRun,
      compiledLaunchRequests = compiled.all,
      budget = budget,
      specIntentResolution = compiled.specIntentResolution,
    )
  }

  private fun resolvedMode(request: ParallelCodeReviewRequest) = ReviewExecutionModePolicy.resolveWithRule(
    // A pinned resolvedTier is lane 1's already-decided depth; honoring it here is what makes both
    // lanes share one tier instead of each re-resolving auto independently.
    requested = request.resolvedTier ?: request.codeReviewMode,
  ).resolvedMode

  private fun runLanes(initial: InitialRun): ParallelReviewLaneRunResult {
    val request = initial.request
    val byAgent = initial.preparedLaunchRequests.groupBy { it.agentId }
    val lane1 = {
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
    val agent2Id = initial.agent2Id ?: return ParallelReviewLaneRunResult(
      lane1 = captureLane(lane1),
      lane2 = ParallelReviewLaneOutcome(success = true, rawOutput = ""),
    )
    val timeoutSec = request.timeout?.inWholeSeconds ?: DEFAULT_TIMEOUT_MINUTES * SECONDS_PER_MINUTE
    return parallelLaneRunner.runTwoLanes(
      ParallelReviewLaneRunRequest(
        lane1 = { captureLane(lane1) },
        lane2 = {
          captureLane {
            launchParentLane(
              agent2Id,
              byAgent[agent2Id].orEmpty(),
              initial.detection.routed,
              initial.budget,
              request,
              request.agent2Model,
              initial.resolvedMode,
            )
          }
        },
        timeout = (timeoutSec + TIMEOUT_BUFFER_SECONDS).seconds,
      ),
    )
  }

  private fun verifyNativeWorkers(initial: InitialRun) {
    val nativeNames = initial.compiledLaunchRequests
      .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
      .mapNotNull { it.logicalWorkerName }
    val logicalNames = buildList {
      addAll(nativeNames)
      if (initial.resolvedMode == ResolvedReviewExecutionMode.INLINE) {
        add(INLINE_NATIVE_WORKER)
      }
    }.distinct()
    if (logicalNames.isEmpty()) return
    nativeAgentPreflight.verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = initial.request.repoRoot,
        agentIds = listOfNotNull(initial.agent1Id, initial.agent2Id),
        logicalNames = logicalNames,
      ),
    )
  }

  private fun prepare(
    request: ParallelCodeReviewRequest,
    revisions: Pair<String, String>,
    diffText: String,
    evidence: ReviewDiffEvidence,
    sharedSequence: SharedReviewEvidenceCommits,
    routedManifests: List<PlatformManifest>,
    manifests: List<PlatformManifest>,
    ownedPathsBySlug: Map<String, Set<String>>,
    agentIds: List<String>,
    budget: skillbill.review.context.model.ReviewContextBudgetPolicy,
    evidenceStorePath: String?,
  ): CompiledLaunches {
    if (
      sharedEvidenceLocatorReader !== FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE &&
      evidenceStorePath.isNullOrBlank()
    ) {
      throw ReviewHunkEvidenceLocatorMissingError(evidenceStorePath.orEmpty())
    }
    val plannedRubrics = resolvePlannedRubrics(evidence, routedManifests, manifests, ownedPathsBySlug)
    val (baseRevision, headRevision) = revisions
    // Every specialist lane the compiler routes reads this one projection of the shared evidence, so
    // N lanes over one checkpoint never provoke a second derivation.
    val commitSequence = SharedReviewEvidenceProjection.project(sharedSequence, evidence)
    val specIntentResolution = resolveSpecIntent(request, evidence, budget)
    val compiled = ParallelReviewPreparationCompiler.compile(
      input = ParallelReviewPreparationInput(
        diff = diffText,
        evidence = evidence,
        commitSequence = commitSequence,
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
        specIntentResolution = specIntentResolution,
        evidenceStorePath = evidenceStorePath,
      ),
      budget = budget,
      envelopeValidator = reviewContextEnvelopeValidator,
      specialistContract = reviewSpecialistContractProvider.authoritativeContract(),
      hunkLocatorReader = sharedEvidenceLocatorReader,
    )
    val selected = selectLaunchesForResume(request.reviewRunId, compiled)
    recordPlannedLanes(request.reviewRunId, plannedRubrics, selected)
    recordSpecIntent(request.reviewRunId, specIntentResolution)
    return CompiledLaunches(all = compiled, toRun = selected, specIntentResolution = specIntentResolution)
  }

  /**
   * A resume re-runs only lanes whose durable disposition is not complete. Fresh runs keep the full
   * compiled set; completed durable results are never re-launched. A compiled lane that holds no
   * durable row at all is new routing since the last attempt, so it must launch: skipping it would
   * leave a selected lane with neither a run nor a durable result, which aggregation rejects.
   */
  private fun selectLaunchesForResume(
    reviewRunId: String?,
    launches: List<ReviewSpecialistLaunchRequest>,
  ): List<ReviewSpecialistLaunchRequest> {
    if (reviewRunId == null || launches.isEmpty()) return launches
    val existing = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.selectLaunchesForResume",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (existing.isEmpty()) return launches
    val completeNames = existing
      .filter { it.reviewDisposition == ReviewRunLaneResolver.COMPLETE_DISPOSITION }
      .map { it.laneSkillName }
      .toSet()
    return launches.filterNot { launch ->
      launch.assignment.laneDecision.specialistSkillName in completeNames
    }
  }

  /**
   * Records the launch plan for a runtime-launched review at the moment it is resolved, so the run's
   * lane attribution comes from the plan itself rather than round-tripping through review text.
   * Disposition stays non-complete until [recordLaneDispositions] observes a durable single-pass
   * result, except segmentation-unreviewable entries which are incomplete immediately.
   */
  private fun recordPlannedLanes(
    reviewRunId: String?,
    plannedRubrics: List<PlannedReviewRubric>,
    launches: List<ReviewSpecialistLaunchRequest>,
  ) {
    if (reviewRunId == null || launches.isEmpty()) return
    val existing = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordPlannedLanes.read",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    val preservedComplete = existing.filter {
      it.reviewDisposition == ReviewRunLaneResolver.COMPLETE_DISPOSITION
    }
    val completionBySkill = launches.associate { launch ->
      requireNotNull(launch.assignment.laneDecision.specialistSkillName) to
        governedLaunchFor(launch).completionState
    }
    val relaunchNames = completionBySkill.keys
    val pending = plannedRubrics
      .filter { it.descriptor.skillName in relaunchNames }
      .map { planned ->
        val completion = completionBySkill.getValue(planned.descriptor.skillName)
        ReviewRunLane(
          laneSkillName = planned.descriptor.skillName,
          packSlug = planned.descriptor.packSlug,
          area = planned.descriptor.area,
          depth = planned.descriptor.depth,
          required = planned.descriptor.required,
          orderIndex = planned.descriptor.orderIndex,
          originLayerChain = planned.descriptor.originLayerChain,
          resolutionState = ReviewRunLaneResolver.RESOLVED,
          reviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE.wireValue,
          bundleCompositionDigest = completion.bundleCompositionDigest,
          segmentAccountingJson = ReviewRunLaneSegmentAccountingJson.encode(completion.segments),
          unreviewedSegmentIds = completion.unreviewedSegmentIds,
          budgetDimension = completion.budgetDimension,
        )
      }
    val merged = preservedComplete.filter { it.laneSkillName !in relaunchNames } + pending
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordPlannedLanes.write",
      expected = "runtime-owned review lane plan",
    ) { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, merged) }
  }

  /**
   * Runs the one bounded integration pass, after every specialist lane has reached a terminal
   * state. Exactly one pass runs no matter how many commits the sequence carries, and it launches
   * no specialist rubric. A resume that already holds a durable integration result does not re-run
   * it; a resume that holds durable lane results but no integration result runs only this pass.
   */
  private fun runIntegrationPass(
    initial: InitialRun,
    outcomes: ParallelReviewLaneRunResult,
  ): ReviewIntegrationPassOutcome {
    val packet = initial.compiledLaunchRequests.firstOrNull()?.packet
      ?: return ReviewIntegrationPassOutcome.skipped(
        NO_SEQUENCE_DIGEST,
        "the review compiled no specialist lane, so there is no commit sequence to integrate over",
      )
    // Inline reviews are done by the parent itself, so there is no delegated commit-focused
    // sequencing to integrate over. Reported, not silently omitted.
    if (initial.resolvedMode == ResolvedReviewExecutionMode.INLINE) {
      return ReviewIntegrationPassOutcome.skipped(
        packet.commitSequenceDigest,
        "this review ran inline, so commit-focused delegated sequencing does not apply",
      )
    }
    durableIntegrationOutcome(initial.request.reviewRunId, packet.commitSequenceDigest)?.let { return it }
    val findingsByLane = (outcomes.lane1.findings + outcomes.lane2.findings)
      .groupingBy { it.specialistSkillName.orEmpty() }.eachCount()
    val lanes = initial.compiledLaunchRequests.map { launch ->
      ReviewLaneIntegrationInput(
        launch = launch,
        completion = effectiveCompletionState(launch, outcomes),
        findingCount = findingsByLane[launch.assignment.laneDecision.specialistSkillName] ?: 0,
      )
    }
    val outcome = ReviewIntegrationPassRunner(parentReviewLauncher, reviewContextEnvelopeValidator).run(
      packet = packet,
      lanes = lanes,
      budget = initial.budget,
      brokerId = initial.agent1Id,
      repoRoot = initial.request.repoRoot,
      timeout = initial.request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
      promptSuffix = initial.request.selectedAgentAddonsSection,
    )
    recordIntegrationBoundary(initial.request.reviewRunId, outcome)
    return outcome
  }

  /**
   * A durable integration result is reusable only for the sequence it was minted against; a resume
   * whose sequence moved re-runs the pass rather than reporting a state that describes other code.
   */
  @Suppress("ReturnCount") // each early return is a distinct reason the durable result is unusable
  private fun durableIntegrationOutcome(
    reviewRunId: String?,
    commitSequenceDigest: String,
  ): ReviewIntegrationPassOutcome? {
    if (reviewRunId == null) return null
    val record = runtimeOwnedPersistence.optionalRead(
      seam = "ParallelCodeReviewRunner.durableIntegrationOutcome",
      expected = "optional durable review integration result",
      fallback = null,
    ) { unitOfWork -> unitOfWork.reviews.fetchIntegrationPass(reviewRunId) }
      ?: return null
    if (record.commitSequenceDigest != commitSequenceDigest) return null
    val terminal = ReviewIntegrationTerminalOutcome.entries
      .firstOrNull { it.wireValue == record.terminalOutcome } ?: return null
    if (!terminal.isDurablyComplete) return null
    return ReviewIntegrationPassOutcome(
      commitSequenceDigest = commitSequenceDigest,
      terminalOutcome = ReviewIntegrationTerminalOutcome.NO_OP_RESUME,
      summarizedLaneCount = 0,
    )
  }

  private fun recordIntegrationBoundary(reviewRunId: String?, outcome: ReviewIntegrationPassOutcome) {
    if (reviewRunId == null) return
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordIntegrationBoundary",
      expected = "runtime-owned review integration result",
    ) { unitOfWork ->
      unitOfWork.reviews.recordIntegrationPass(
        reviewRunId,
        ReviewIntegrationPassRecord(outcome.commitSequenceDigest, outcome.terminalOutcome.wireValue),
      )
    }
  }

  private fun resolveSpecIntent(
    request: ParallelCodeReviewRequest,
    evidence: ReviewDiffEvidence,
    budget: ReviewContextBudgetPolicy,
  ): SpecIntentResolution {
    return specIntentProjectionResolver.resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = request.repoRoot,
        explicitSpecPath = request.specPath,
        branchName = currentHeadBranchName(request.repoRoot),
        changedPaths = evidence.files.map { it.path },
        budget = budget,
      ),
    )
  }

  private fun currentHeadBranchName(repoRoot: Path): String = diffResolver.runProcess(
    listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
    repoRoot,
  )?.trim().orEmpty()

  private fun recordSpecIntent(reviewRunId: String?, resolution: SpecIntentResolution) {
    if (reviewRunId == null) return
    val reference = when (resolution) {
      is SpecIntentResolution.Resolved -> ReviewSpecProjectionReference(
        specPath = resolution.projection.provenance.specPath,
        contentDigest = resolution.projection.provenance.contentDigest,
      )
      is SpecIntentResolution.None -> ReviewSpecProjectionReference(absenceReason = resolution.reason.wireValue)
    }
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordSpecIntent",
      expected = "runtime-owned review spec projection reference",
    ) { unitOfWork ->
      unitOfWork.reviews.recordSpecProjectionReference(reviewRunId, reference)
      if (resolution is SpecIntentResolution.None) {
        unitOfWork.reviews.recordStageBoundary(
          reviewRunId,
          ReviewStageBoundary(
            stage = ReviewStage.ADJUDICATION,
            reached = ReviewStageReached.NOT_REACHED,
            recordedAt = Instant.now().toString(),
            contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
          ),
        )
      }
    }
  }

  private fun recordedFindingVerdicts(
    reviewRunId: String?,
    inMemory: List<ReviewFindingVerdict>,
  ): List<ReviewFindingVerdict> {
    if (reviewRunId == null) return inMemory
    return when (val resolution = runtimeOwnedFindingVerdicts(reviewRunId)) {
      is RuntimeOwnedFindingVerdictsReadResolution.Present -> resolution.verdicts
      is RuntimeOwnedFindingVerdictsReadResolution.ReadError -> {
        recordRuntimeOwnedReadFailure(
          "ParallelCodeReviewRunner.recordedFindingVerdicts",
          "runtime-owned finding verdicts",
          resolution.cause,
          used = "in_memory",
        )
        inMemory
      }
    }
  }

  private fun runtimeOwnedFindingVerdicts(
    reviewRunId: String,
  ): RuntimeOwnedFindingVerdictsReadResolution =
    runtimeOwnedPersistence.resolvingRead(
      seam = "ParallelCodeReviewRunner.runtimeOwnedFindingVerdicts",
      expected = "runtime-owned finding verdicts",
      onPersistenceFailure = RuntimeOwnedFindingVerdictsReadResolution::ReadError,
    ) { unitOfWork ->
      unitOfWork.resolveRuntimeOwnedFindingVerdicts(reviewRunId)
    }

  private fun reviewPassClaims(
    reviewRunId: String,
    seam: String,
  ): RuntimeOwnedReviewPassClaimsReadResolution = when (
    val resolution = runtimeOwnedReviewPassClaims(reviewRunId)
  ) {
    is RuntimeOwnedReviewPassClaimsReadResolution.Present -> resolution
    is RuntimeOwnedReviewPassClaimsReadResolution.Absent -> {
      recordRuntimeOwnedReadFailure(seam, "runtime-owned review pass claims", resolution.cause, used = "absent")
      resolution
    }
    is RuntimeOwnedReviewPassClaimsReadResolution.ReadError -> {
      recordRuntimeOwnedReadFailure(seam, "runtime-owned review pass claims", resolution.cause, used = "read_error")
      resolution
    }
  }

  private fun runtimeOwnedReviewPassClaims(
    reviewRunId: String,
  ): RuntimeOwnedReviewPassClaimsReadResolution =
    runtimeOwnedPersistence.resolvingRead(
      seam = "ParallelCodeReviewRunner.runtimeOwnedReviewPassClaims",
      expected = "runtime-owned review pass claims",
      onPersistenceFailure = RuntimeOwnedReviewPassClaimsReadResolution::ReadError,
    ) { unitOfWork ->
      unitOfWork.resolveRuntimeOwnedReviewPassClaims(reviewRunId)
    }

  private fun requiredReviewPassClaims(
    reviewRunId: String,
    seam: String,
  ): ReviewPassClaimSnapshot = when (val resolution = runtimeOwnedReviewPassClaims(reviewRunId)) {
    is RuntimeOwnedReviewPassClaimsReadResolution.Present -> resolution.snapshot
    is RuntimeOwnedReviewPassClaimsReadResolution.Absent -> {
      recordRuntimeOwnedReadFailure(seam, "runtime-owned review pass claims", resolution.cause, used = "absent")
      throw RuntimeOwnedFactUnavailable(
        "Runtime-owned review pass claims are unavailable for '$reviewRunId' at $seam.",
      )
    }
    is RuntimeOwnedReviewPassClaimsReadResolution.ReadError -> {
      recordRuntimeOwnedReadFailure(seam, "runtime-owned review pass claims", resolution.cause, used = "read_error")
      throw RuntimeOwnedFactUnavailable(
        "Runtime-owned review pass claims could not be read for '$reviewRunId' at $seam: ${resolution.cause}",
      )
    }
  }

  private fun reviewFindingVerdicts(
    reviewRunId: String,
    seam: String,
  ): List<ReviewFindingVerdict> = when (val resolution = runtimeOwnedFindingVerdicts(reviewRunId)) {
    is RuntimeOwnedFindingVerdictsReadResolution.Present -> resolution.verdicts
    is RuntimeOwnedFindingVerdictsReadResolution.ReadError -> {
      recordRuntimeOwnedReadFailure(seam, "runtime-owned finding verdicts", resolution.cause, used = "empty")
      emptyList()
    }
  }

  private fun recordRuntimeOwnedReadFailure(
    seam: String,
    expected: String,
    cause: String,
    used: String = "none",
  ) {
    runCatching {
      diagnostics.warning("seam=$seam value_expected=$expected value_used=$used cause=$cause")
    }
  }

  private fun runClaimVerification(initial: InitialRun, result: ParallelCodeReviewResult): List<ReviewFindingVerdict> {
    val reviewRunId = initial.request.reviewRunId
    val boundaries = reviewStageBoundaries(reviewRunId)
    val claims = claimVerificationClaims(reviewRunId, boundaries, result.mergeResult.findings)
    val existing = if (reviewRunId == null) {
      emptyList()
    } else {
      reviewFindingVerdicts(reviewRunId, "ParallelCodeReviewRunner.runClaimVerification")
    }
    val verifiedRefs = existing
      .filter { it.stage == ReviewStage.VERIFICATION }
      .map { it.findingRef }
      .toSet()
    if (claims.isNotEmpty() && claims.all { it.fNumber in verifiedRefs }) {
      if (reviewRunId != null) recordVerificationBoundary(reviewRunId)
      return existing
    }
    val verificationInput = verificationReviewOutput(result.output, claims)
    if (claims.isEmpty()) {
      emptyClaimsVerificationShortCircuit(reviewRunId, boundaries, verificationInput, existing)?.let { return it }
    }
    val outcome = ReviewClaimVerificationRunner(parentReviewLauncher, reviewContextEnvelopeValidator).run(
      packet = initial.compiledLaunchRequests.firstOrNull()?.packet,
      reviewOutput = verificationInput,
      findings = claims,
      existingVerdicts = existing,
      mode = initial.resolvedMode,
      budget = initial.budget,
      brokerId = initial.agent1Id,
      repoRoot = initial.request.repoRoot,
      timeout = initial.request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
      promptSuffix = initial.request.selectedAgentAddonsSection,
    )
    return persistClaimVerificationOutcome(reviewRunId, claims, existing, outcome)
  }

  private fun reviewStageBoundaries(reviewRunId: String?): List<ReviewStageBoundary> = if (reviewRunId == null) {
    emptyList()
  } else {
    runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.reviewStageBoundaries",
      expected = "runtime-owned review stage boundaries",
    ) { unitOfWork -> unitOfWork.reviews.fetchStageBoundaries(reviewRunId) }
  }

  private fun claimVerificationClaims(
    reviewRunId: String?,
    boundaries: List<ReviewStageBoundary>,
    mergedFindings: List<ParallelReviewMergedFinding>,
  ): List<ParallelReviewMergedFinding> = if (reviewRunId == null) {
    mergedFindings
  } else {
    val reviewReached = boundaries.any {
      it.stage == ReviewStage.REVIEW && it.reached == ReviewStageReached.REACHED
    }
    if (!reviewReached) {
      emptyList()
    } else {
      requiredReviewPassClaims(reviewRunId, "ParallelCodeReviewRunner.claimVerificationClaims").findings
    }
  }

  private fun emptyClaimsVerificationShortCircuit(
    reviewRunId: String?,
    boundaries: List<ReviewStageBoundary>,
    verificationInput: String,
    existing: List<ReviewFindingVerdict>,
  ): List<ReviewFindingVerdict>? {
    if (
      reviewRunId != null &&
      boundaries.any { it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED }
    ) {
      return existing
    }
    if (!reviewOutputNeedsProseVerification(verificationInput)) {
      if (reviewRunId != null) recordVerificationBoundary(reviewRunId)
      return existing
    }
    return null
  }

  private fun persistClaimVerificationOutcome(
    reviewRunId: String?,
    claims: List<ParallelReviewMergedFinding>,
    existing: List<ReviewFindingVerdict>,
    outcome: ReviewClaimVerificationOutcome,
  ): List<ReviewFindingVerdict> {
    if (reviewRunId == null) return existing + outcome.verdicts
    if (outcome.verdicts.isNotEmpty()) {
      runtimeOwnedPersistence.requiredWrite(
        seam = "ParallelCodeReviewRunner.persistClaimVerificationOutcome",
        expected = "runtime-owned finding verification verdicts",
      ) { unitOfWork ->
        unitOfWork.reviews.recordFindingVerdicts(reviewRunId, outcome.verdicts)
      }
    }
    val recordedRefs = (existing + outcome.verdicts)
      .filter { it.stage == ReviewStage.VERIFICATION }
      .map { it.findingRef }
      .toSet()
    if (claims.isNotEmpty() && claims.all { it.fNumber in recordedRefs }) {
      recordVerificationBoundary(reviewRunId)
    } else if (claims.isEmpty() && outcome.skipReason == null) {
      recordVerificationBoundary(reviewRunId)
    }
    return existing + outcome.verdicts
  }

  private fun recordVerificationBoundary(reviewRunId: String) {
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordVerificationBoundary",
      expected = "runtime-owned verification stage boundary",
    ) { unitOfWork ->
      unitOfWork.reviews.recordStageBoundary(
        reviewRunId,
        ReviewStageBoundary(
          stage = ReviewStage.VERIFICATION,
          reached = ReviewStageReached.REACHED,
          recordedAt = Instant.now().toString(),
          contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        ),
      )
    }
  }

  private fun runSpecAdjudication(initial: InitialRun, result: ParallelCodeReviewResult): List<ReviewFindingVerdict> {
    val reviewRunId = initial.request.reviewRunId
    durableAdjudication(reviewRunId)?.let { return it }
    val projection = (initial.specIntentResolution as? SpecIntentResolution.Resolved)?.projection
    val claims = if (reviewRunId == null) {
      result.mergeResult.findings
    } else {
      requiredReviewPassClaims(reviewRunId, "ParallelCodeReviewRunner.runSpecAdjudication").findings
    }
    val existing = if (reviewRunId == null) {
      emptyList()
    } else {
      reviewFindingVerdicts(reviewRunId, "ParallelCodeReviewRunner.runSpecAdjudication")
    }
    val outcome = ReviewSpecAdjudicationRunner(parentReviewLauncher, reviewContextEnvelopeValidator).run(
      packet = initial.compiledLaunchRequests.firstOrNull()?.packet,
      findings = claims,
      existingVerdicts = existing,
      projection = projection,
      budget = initial.budget,
      brokerId = initial.agent1Id,
      repoRoot = initial.request.repoRoot,
      timeout = initial.request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
      promptSuffix = initial.request.selectedAgentAddonsSection,
    )
    return persistAdjudication(reviewRunId, outcome)
  }

  private fun durableAdjudication(reviewRunId: String?): List<ReviewFindingVerdict>? {
    if (reviewRunId == null) return null
    val boundaries = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.durableAdjudication",
      expected = "runtime-owned adjudication stage boundaries",
    ) { unitOfWork ->
      unitOfWork.reviews.fetchStageBoundaries(reviewRunId)
    }
    val verificationReached = boundaries.any {
      it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED
    }
    if (!verificationReached) return emptyList()
    val adjudicationReached = boundaries.any {
      it.stage == ReviewStage.ADJUDICATION && it.reached == ReviewStageReached.REACHED
    }
    if (!adjudicationReached) return null
    return reviewFindingVerdicts(reviewRunId, "ParallelCodeReviewRunner.durableAdjudication")
  }

  private fun persistAdjudication(
    reviewRunId: String?,
    outcome: ReviewSpecAdjudicationOutcome,
  ): List<ReviewFindingVerdict> {
    if (reviewRunId == null) return outcome.verdicts
    if (outcome.skipReason == ReviewSpecAdjudicationRunner.SPEC_CONTEXT_NONE) return emptyList()
    if (outcome.verdicts.isNotEmpty()) {
      runtimeOwnedPersistence.requiredWrite(
        seam = "ParallelCodeReviewRunner.persistAdjudication",
        expected = "runtime-owned adjudication verdicts",
      ) { unitOfWork ->
        unitOfWork.reviews.recordFindingVerdicts(reviewRunId, outcome.verdicts)
      }
    }
    recordAdjudicationBoundary(reviewRunId)
    return outcome.verdicts
  }

  private fun recordAdjudicationBoundary(reviewRunId: String) {
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordAdjudicationBoundary",
      expected = "runtime-owned adjudication stage boundary",
    ) { unitOfWork ->
      unitOfWork.reviews.recordStageBoundary(
        reviewRunId,
        ReviewStageBoundary(
          stage = ReviewStage.ADJUDICATION,
          reached = ReviewStageReached.REACHED,
          recordedAt = Instant.now().toString(),
          contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        ),
      )
    }
  }

  private fun emitReviewStageDegradations(reviewRunId: String?, outcomes: ParallelReviewLaneRunResult) {
    if (reviewRunId == null) return
    val evidenceBoundaries = evidenceBoundaryAccountings(outcomes)
    val degradationInputs: Pair<ReviewSpecProjectionReference?, List<ReviewStageBoundary>>? =
      runtimeOwnedPersistence.optionalRead(
        seam = "ParallelCodeReviewRunner.emitReviewStageDegradations.read",
        expected = "optional review stage degradation inputs",
        fallback = null,
      ) { unitOfWork ->
        unitOfWork.reviews.fetchSpecProjectionReference(reviewRunId) to
          unitOfWork.reviews.fetchStageBoundaries(reviewRunId)
      }
    if (degradationInputs == null) return
    val (spec, boundaries) = degradationInputs
    val verdicts = reviewFindingVerdicts(reviewRunId, "ParallelCodeReviewRunner.emitReviewStageDegradations")
    val claims = when (
      val resolution = reviewPassClaims(reviewRunId, "ParallelCodeReviewRunner.emitReviewStageDegradations")
    ) {
      is RuntimeOwnedReviewPassClaimsReadResolution.Present -> resolution.snapshot
      is RuntimeOwnedReviewPassClaimsReadResolution.Absent,
      is RuntimeOwnedReviewPassClaimsReadResolution.ReadError,
      -> null
    }
    runtimeOwnedPersistence.optionalWrite(
      seam = "ParallelCodeReviewRunner.emitReviewStageDegradations.write",
      expected = "optional review stage degradation telemetry",
      fallback = Unit,
    ) { unitOfWork ->
      ReviewStageDegradationSelection.select(
        reviewRunId = reviewRunId,
        spec = spec,
        boundaries = boundaries,
        verdicts = verdicts,
        claims = claims,
        evidenceBoundaries = evidenceBoundaries,
      ).forEach { unitOfWork.lifecycleTelemetry.reviewStageDegradation(it) }
    }
  }

  private fun evidenceBoundaryAccountings(
    outcomes: ParallelReviewLaneRunResult,
  ): List<ReviewEvidenceBoundaryAccounting> = listOf(outcomes.lane1, outcomes.lane2).mapNotNull(::laneEvidenceBoundary)

  private fun laneEvidenceBoundary(outcome: ParallelReviewLaneOutcome): ReviewEvidenceBoundaryAccounting? {
    if (outcome.accounting?.terminalStatus == UNSUPPORTED_PROVIDER_TERMINAL_STATUS) return null
    if (outcome.accounting == null && outcome.unboundSeam == null) return null
    val accounting = outcome.accounting
    return ReviewEvidenceBoundaryAccounting(
      governedLaunchCount = if (accounting != null && accounting.terminalStatus != NO_OP_RESUME_TERMINAL_STATUS) {
        1
      } else {
        0
      },
      authorizedReadCount = accounting?.authorizedReadCount ?: 0,
      refusedOperationCount = accounting?.refusedOperationCount ?: 0,
      refusedCategories = accounting?.refusals.orEmpty().map { it.category },
      evidenceBytes = accounting?.evidenceBytes ?: 0,
      expansionCount = accounting?.expansions?.size ?: 0,
      rejectedCandidateCount = outcome.rejectedCandidateCount,
      unboundSeam = outcome.unboundSeam,
    )
  }

  private fun persistReviewPassClaims(
    reviewRunId: String?,
    findings: List<ParallelReviewMergedFinding>,
  ) {
    if (reviewRunId == null) return
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.persistReviewPassClaims",
      expected = "runtime-owned review pass claims",
    ) { unitOfWork ->
      val recorded = when (
        val resolution = unitOfWork.resolveRuntimeOwnedReviewPassClaims(reviewRunId)
      ) {
        is RuntimeOwnedReviewPassClaimsReadResolution.Present -> resolution.snapshot
        is RuntimeOwnedReviewPassClaimsReadResolution.Absent -> null
        is RuntimeOwnedReviewPassClaimsReadResolution.ReadError -> {
          recordRuntimeOwnedReadFailure(
            "ParallelCodeReviewRunner.persistReviewPassClaims",
            "runtime-owned review pass claims",
            resolution.cause,
            used = "read_error",
          )
          throw RuntimeOwnedFactUnavailable(
            "Runtime-owned review pass claims could not be read for '$reviewRunId': ${resolution.cause}",
          )
        }
      }
      val existing = recorded?.findings.orEmpty()
      val unioned = unionReviewPassClaims(existing, findings)
      if (recorded != null && existing == unioned) return@requiredWrite
      unitOfWork.reviews.recordReviewPassClaims(reviewRunId, unioned)
    }
  }

  private fun recordReviewStageBoundary(
    reviewRunId: String?,
    integration: ReviewIntegrationPassOutcome,
    findings: List<ParallelReviewMergedFinding>,
  ) {
    if (reviewRunId == null || !integration.durable) return
    val lanes = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordReviewStageBoundary.read",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (lanes.isEmpty() || lanes.any { it.reviewDisposition != ReviewRunLaneResolver.COMPLETE_DISPOSITION }) {
      return
    }
    persistReviewPassClaims(reviewRunId, findings)
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordReviewStageBoundary.write",
      expected = "runtime-owned review stage boundary",
    ) { unitOfWork ->
      unitOfWork.reviews.recordStageBoundary(
        reviewRunId,
        ReviewStageBoundary(
          stage = ReviewStage.REVIEW,
          reached = ReviewStageReached.REACHED,
          recordedAt = Instant.now().toString(),
          contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        ),
      )
    }
  }

  private fun stageResumeReport(reviewRunId: String?): ReviewStageResumeReport? {
    if (reviewRunId == null) return null
    return runtimeOwnedPersistence.optionalRead(
      seam = "ParallelCodeReviewRunner.stageResumeReport",
      expected = "optional runtime-owned review resume report",
      fallback = null,
    ) { unitOfWork ->
        val verdicts = when (val resolution = unitOfWork.resolveRuntimeOwnedFindingVerdicts(reviewRunId)) {
          is RuntimeOwnedFindingVerdictsReadResolution.Present -> resolution.verdicts
          is RuntimeOwnedFindingVerdictsReadResolution.ReadError -> {
            recordRuntimeOwnedReadFailure(
              "ParallelCodeReviewRunner.stageResumeReport",
              "runtime-owned finding verdicts",
              resolution.cause,
              used = "read_error",
            )
            return@optionalRead null
          }
        }
        ReviewStageResumeSelection.select(
          unitOfWork.reviews.fetchStageBoundaries(reviewRunId),
          verdicts,
        )
    }
  }

  /**
   * Aggregation gate plus coverage honesty: a missing, duplicated, or sequence-mismatched lane
   * result fails loudly here rather than merging into a register that looks complete.
   */
  private fun coverageReport(
    initial: InitialRun,
    outcomes: ParallelReviewLaneRunResult,
    integration: ReviewIntegrationPassOutcome,
  ): ReviewCoverageReport? {
    val packet = initial.compiledLaunchRequests.firstOrNull()?.packet ?: return null
    val ranThisPass = initial.preparedLaunchRequests.map { launch ->
      val completion = effectiveCompletionState(launch, outcomes)
      ReviewLaneAggregationInput(
        lane = launch.assignment.lane,
        commitSequenceDigest = packet.commitSequenceDigest,
        disposition = completion.disposition,
        unreviewedUnits = completion.unreviewedUnits,
      )
    }
    // A resume launches only non-complete lanes, so the lanes it skipped are accounted for by their
    // durable results. Expected stays the packet's full selection either way: that is what makes a
    // lane silently vanishing between attempts a loud aggregation failure rather than clean coverage.
    val results = ranThisPass + durablyCompleteLanes(initial, packet, ranThisPass)
    val bothAgentsSucceeded = outcomes.lane1.success &&
      (initial.agent2Id == null || outcomes.lane2.success)
    return ReviewLaneAggregation.requireCompleteLaneResults(
      expectedLanes = packet.selectedLanes,
      results = results,
      commitSequenceDigest = packet.commitSequenceDigest,
    ).copy(
      integrationCompleted = integration.completed && bothAgentsSucceeded,
      integrationNotApplicableReason = integration.skipReason,
    )
  }

  /**
   * A packet lane is `agentId:specialistSkillName`, while durable rows are keyed by skill name
   * alone because both parallel agents review the same skill. A selected lane this pass did not run
   * counts as covered exactly when its skill holds a durable complete result.
   */
  private fun durablyCompleteLanes(
    initial: InitialRun,
    packet: ReviewContextPacket,
    ranThisPass: List<ReviewLaneAggregationInput>,
  ): List<ReviewLaneAggregationInput> {
    val reviewRunId = initial.request.reviewRunId ?: return emptyList()
    val alreadyRan = ranThisPass.map { it.lane }.toSet()
    val notRun = packet.selectedLanes.filterNot { it in alreadyRan }
    if (notRun.isEmpty()) return emptyList()
    val completeSkills = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.durablyCompleteLanes",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
      .filter { it.reviewDisposition == ReviewRunLaneResolver.COMPLETE_DISPOSITION }
      .map { it.laneSkillName }
      .toSet()
    return notRun.filter { it.substringAfter(':') in completeSkills }.map { lane ->
      ReviewLaneAggregationInput(
        lane = lane,
        commitSequenceDigest = packet.commitSequenceDigest,
        disposition = ReviewLaneReviewDisposition.COMPLETE,
      )
    }
  }

  /** Writes final per-lane disposition after the parallel pass so resume stays lane-granular. */
  private fun recordLaneDispositions(initial: InitialRun, outcomes: ParallelReviewLaneRunResult) {
    val reviewRunId = initial.request.reviewRunId ?: return
    val existing = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordLaneDispositions.read",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (existing.isEmpty()) return
    val completionBySkill = initial.preparedLaunchRequests.associate { launch ->
      requireNotNull(launch.assignment.laneDecision.specialistSkillName) to
        effectiveCompletionState(launch, outcomes)
    }
    val updated = existing.map { lane ->
      val completion = completionBySkill[lane.laneSkillName] ?: return@map lane
      val durableComplete = completion.disposition == ReviewLaneReviewDisposition.COMPLETE
      lane.copy(
        reviewDisposition = if (durableComplete) {
          ReviewRunLaneResolver.COMPLETE_DISPOSITION
        } else {
          ReviewLaneReviewDisposition.INCOMPLETE.wireValue
        },
        bundleCompositionDigest = completion.bundleCompositionDigest,
        segmentAccountingJson = ReviewRunLaneSegmentAccountingJson.encode(completion.segments),
        unreviewedSegmentIds = completion.unreviewedSegmentIds,
        budgetDimension = completion.budgetDimension,
      )
    }
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordLaneDispositions.write",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, updated) }
  }

  private fun recordMergedFindingLanes(reviewRunId: String?) {
    if (reviewRunId == null) return
    val claims = when (
      val resolution = reviewPassClaims(reviewRunId, "ParallelCodeReviewRunner.recordMergedFindingLanes")
    ) {
      is RuntimeOwnedReviewPassClaimsReadResolution.Present -> resolution.snapshot.findings
      is RuntimeOwnedReviewPassClaimsReadResolution.Absent,
      is RuntimeOwnedReviewPassClaimsReadResolution.ReadError,
      -> return
    }
    val attribution = claims.mapNotNull { finding ->
      finding.specialistSkillNames.firstOrNull()?.let { finding.fNumber to it }
    }.toMap()
    if (attribution.isEmpty()) return
    runtimeOwnedPersistence.optionalWrite(
      seam = "ParallelCodeReviewRunner.recordMergedFindingLanes",
      expected = "optional runtime-owned finding lane attribution",
      fallback = Unit,
    ) { unitOfWork -> unitOfWork.reviews.recordFindingLaneAttribution(reviewRunId, attribution) }
  }

  /**
   * Both revisions are canonicalized to immutable commit SHAs before anything compares them, so a
   * symbolic base or head cannot make a correct commit sequence fail the base-to-head equivalence
   * fact, and the aggregate delta and the sequence are guaranteed to span the same range.
   */
  private fun resolveReviewRevisions(request: ParallelCodeReviewRequest): Pair<String, String> {
    val (base, head) = if (spansCommitRange(request)) canonicalRange(request) else declaredRange(request)
    if (base.isBlank() || head.isBlank()) {
      throw DiffResolutionException("Review base and head revisions must resolve to non-blank immutable identities.")
    }
    return base to head
  }

  /**
   * Only a range scope without an exact supplied diff derives its delta from a Git revision range.
   * Canonicalizing anywhere else would shell out for labels nothing compares, and would fail a
   * supplied-diff or working-tree review whose declared revisions are not commits in this checkout.
   */
  private fun spansCommitRange(request: ParallelCodeReviewRequest): Boolean = !hasSuppliedDiff(request) &&
    (request.scope == ParallelReviewScope.BRANCH || request.scope == ParallelReviewScope.PR)

  private fun hasSuppliedDiff(request: ParallelCodeReviewRequest): Boolean =
    request.suppliedDiff != null || request.suppliedDiffPath != null

  private fun completeEmptySuppliedDelta(request: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    val budget = repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(request.repoRoot))
      .config.reviewContextBudget
    val specIntent = specIntentProjectionResolver.resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = request.repoRoot,
        explicitSpecPath = request.specPath,
        branchName = currentHeadBranchName(request.repoRoot),
        changedPaths = emptyList(),
        budget = budget,
      ),
    )
    recordSpecIntent(request.reviewRunId, specIntent)
    request.reviewRunId?.let { runId ->
      persistReviewPassClaims(runId, emptyList())
      if (specIntent is SpecIntentResolution.Resolved) {
        recordAdjudicationBoundary(runId)
      }
    }
    return ParallelCodeReviewResult(
      mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = "NO_FINDINGS"),
      lane1 = ParallelReviewLaneStatus(agentId = request.agent1Id, success = true),
      lane2 = ParallelReviewLaneStatus(agentId = request.agent2Id.orEmpty(), success = true),
    )
  }

  private fun canonicalRange(request: ParallelCodeReviewRequest): Pair<String, String> {
    val head = canonicalRevision(request.headRevision ?: HEAD_REVISION, request.repoRoot)
    val base = request.baseRevision?.let { canonicalRevision(it, request.repoRoot) } ?: when (request.scope) {
      ParallelReviewScope.PR -> detectPrBase(request.repoRoot)
      else -> detectBranchBase(request.repoRoot)
    }
    return base to head
  }

  /**
   * A working-tree review still pins the commit its delta was taken against; an exact supplied diff
   * has no repository range to pin and must not reach for Git at all.
   */
  private fun declaredRange(request: ParallelCodeReviewRequest): Pair<String, String> {
    val head = request.headRevision
      ?: if (hasSuppliedDiff(request)) HEAD_REVISION else canonicalRevision(HEAD_REVISION, request.repoRoot)
    return (request.baseRevision ?: head) to head
  }

  private fun canonicalRevision(revision: String, repoRoot: Path): String =
    diffResolver.runProcess(listOf("git", "rev-parse", "--verify", "$revision^{commit}"), repoRoot)
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?: throw DiffResolutionException("Review revision '$revision' does not resolve to a commit here.")

  /**
   * A PR spans its own base branch, not HEAD..HEAD; aliasing base to head would collapse every PR
   * review to a single synthetic unit. The merge base against the PR's base commit is the real one.
   */
  private fun detectPrBase(repoRoot: Path): String {
    val baseRefOid = diffResolver
      .runProcess(listOf("gh", "pr", "view", "--json", "baseRefOid", "--jq", ".baseRefOid"), repoRoot)
      ?.trim()
      ?.takeIf { it.isNotBlank() }
    val merged = baseRefOid?.let {
      diffResolver.runProcess(listOf("git", "merge-base", "HEAD", it), repoRoot)?.trim()
    }
    return merged?.takeIf { it.isNotBlank() } ?: detectBranchBase(repoRoot)
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
    val installed = installedPackCatalog.manifests()
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
    // Each root pack only owns the files that actually routed to it; a required baseline lane
    // must claim exactly that root's routed files, never every changed file across the whole
    // (possibly cross-stack) diff, or a Kotlin required specialist would also claim Python files.
    val depthOffsets = ReviewCrossRootLaneReconciliation
      .compositionDepthOffsets(routedManifests.map { it.slug }, manifests)
    val rootLanes = routedManifests.map { root ->
      val rootOwnedPaths = ownedPathsBySlug[root.slug].orEmpty()
      val rootFiles = evidence.files.filter { it.path in rootOwnedPaths }
      val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(root.slug, manifests)
      val lanes = ReviewLaunchPlanPolicy.flatten(root.slug, manifests, selectedAreas).lanes.also { lanes ->
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
      ReviewRootLanes(depthOffsets[root.slug] ?: 0, lanes)
    }
    val exclusion = ReviewPerAreaFallbackExclusion.partition(rootLanes, manifests)
    ReviewCrossRootLaneReconciliation
      .reconcile(exclusion.roots, exclusion.excludedFallbackLanesByArea)
      .filter { it.lane.ownedPaths.isNotEmpty() }
      .map { reconciled ->
        val lane = reconciled.lane
        require(
          reconciled.inputs.filter { it.packSlug == lane.packSlug }.all {
            it.area == lane.area && it.skillName == lane.skillName && it.addOns == lane.addOns
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
          originLayerChains = reconciled.inputs.flatMap { it.originLayerChains }.distinct(),
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

  private fun resolveDiff(request: ParallelCodeReviewRequest, revisions: Pair<String, String>): String {
    if (request.suppliedDiff != null) {
      return request.suppliedDiff
    }
    val (base, head) = revisions
    val diffText = request.suppliedDiffPath?.let { path ->
      diffResolver.readDiff(path, MAX_SUPPLIED_DIFF_BYTES)
        ?: throw DiffResolutionException(
          "--diff-file must name a readable, non-empty regular file no larger than $MAX_SUPPLIED_DIFF_BYTES bytes.",
        )
    } ?: when (request.scope) {
      ParallelReviewScope.STAGED -> runDiff(listOf("git", "diff", "--cached"), request.repoRoot)
      ParallelReviewScope.UNSTAGED -> runDiff(listOf("git", "diff"), request.repoRoot)
      ParallelReviewScope.BRANCH -> runDiff(listOf("git", "diff", base, head), request.repoRoot)
      ParallelReviewScope.PR -> diffResolver.runProcess(listOf("git", "diff", base, head), request.repoRoot)
        ?: runDiff(listOf("gh", "pr", "diff"), request.repoRoot)
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

  private fun detectStack(evidence: ReviewDiffEvidence): StackDetection {
    // Packs come from the Skill Bill installation, never from the repository under review: that
    // directory belongs to the reviewed project and may hold packs for a different contract.
    // No installation yields an empty list (no exception) and degrades to a generic rubric.
    // Installed packs that exist but are out of contract throw; surface that loudly instead of
    // silently dropping the stack-specific specialists, per the shell's "never silently fall back"
    // contract.
    val manifests = try {
      installedPackCatalog.manifests()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      throw StackDetectionException(
        "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
          "Repair the installed platform packs before running parallel review.",
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

  private fun launchParentLane(
    agentId: String,
    launchRequests: List<ReviewSpecialistLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    budget: ReviewContextBudgetPolicy,
    request: ParallelCodeReviewRequest,
    modelOverride: String?,
    resolvedMode: ResolvedReviewExecutionMode,
  ): ParallelReviewLaneOutcome {
    if (launchRequests.isEmpty()) return noOpResumeOutcome(agentId)
    val selected = launchRequests.sortedBy { it.assignment.laneDecision.orderIndex }
    val bundleStates = selected.map(::governedLaunchFor).map { it.completionState }
    val launch = InlineParentLaunch(
      agentId = agentId,
      selected = selected,
      prompt = parentPrompt(selected, routedManifests, resolvedMode),
      bundleState = aggregateBundleCompletion(bundleStates),
    )
    return when (val bound = bindGovernedEvidence(selected, request.repoRoot)) {
      is GovernedEvidenceBind.Unbound -> unboundParentOutcome(launch, bound)
      is GovernedEvidenceBind.Bound -> launchedBoundParent(
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
    launch: InlineParentLaunch,
    bound: GovernedEvidenceBind.Bound,
    budget: ReviewContextBudgetPolicy,
    request: ParallelCodeReviewRequest,
    modelOverride: String?,
    resolvedMode: ResolvedReviewExecutionMode,
  ): ParallelReviewLaneOutcome {
    val outcome = bound.endpoint.use {
      parentReviewLauncher.launch(
        GoalRunnerSubtaskLaunchRequest(
          invokedAgentId = launch.agentId,
          configuredAgentOverrideId = null,
          skillRunRequest = SkillRunRequest(
            issueKey = "code-review-parallel",
            repoRoot = request.repoRoot,
            timeout = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
            promptOverride = request.withSelectedAgentAddons(launch.prompt),
            modelOverride = modelOverride,
            conversationIsolation = ConversationIsolation.NONE,
            reviewEvidenceBroker = bound.broker,
            nativeReviewOperations = bound.protocol,
            reviewEvidenceEndpoint = bound.endpoint,
            nativeReviewWorkerName = INLINE_NATIVE_WORKER
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
  ): GovernedEvidenceBind {
    val broker = try {
      parentEvidenceBroker(selected, repoRoot)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      return GovernedEvidenceBind.Unbound(
        ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
        GovernedEvidenceBindFault.CONSTRUCTION,
      )
    }
    val protocol = try {
      BrokerBackedNativeReviewOperationProtocol(broker)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      return GovernedEvidenceBind.Unbound(
        ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
        GovernedEvidenceBindFault.PROTOCOL,
      )
    }
    return try {
      GovernedEvidenceBind.Bound(
        broker,
        protocol,
        governedEvidenceEndpointBinder.bind(broker.accounting().lane, protocol),
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      GovernedEvidenceBind.Unbound(
        ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
        GovernedEvidenceBindFault.ENDPOINT,
      )
    }
  }

  private fun unboundParentOutcome(
    launch: InlineParentLaunch,
    unbound: GovernedEvidenceBind.Unbound,
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
    launch: InlineParentLaunch,
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
    launch: InlineParentLaunch,
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
      ?: laneFailureReason(outcome)
    val evidenceAccounting = evidenceBroker.accounting()
    val completion = brokerEvidenceCompletionState(bundleState, evidenceAccounting)
    val softAdmission = if (launchReason == null) {
      softAdmitFindings(outcome.stdout, launch)
    } else {
      SoftRegisterAdmission(emptyList(), null, 0)
    }
    return ParallelReviewLaneOutcome(
      success = launchReason == null,
      rawOutput = outcome.stdout,
      failureReason = launchReason,
      droppedCandidateDiagnostic = softAdmission.droppedCandidateDiagnostic,
      budgetOutcome = budgetOutcome,
      accounting = inlineParentAccounting(
        launch,
        inlineTerminalStatus(outcome, completion.disposition),
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

  private data class SoftRegisterAdmission(
    val findings: List<ParallelReviewRawFinding>,
    val droppedCandidateDiagnostic: String?,
    val rejectedCandidateCount: Int,
  )

  private fun softAdmitFindings(stdout: String, launch: InlineParentLaunch): SoftRegisterAdmission = try {
    val parsed = parseLaneRegisterSeam(stdout, launch.assignment.lane, registerParse)
    SoftRegisterAdmission(
      findings = attributeInlineFindings(parsed, launch.selected),
      droppedCandidateDiagnostic = rejectedCandidateDiagnostic(parsed),
      rejectedCandidateCount = parsed.rejections.size,
    )
  } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    SoftRegisterAdmission(emptyList(), null, 0)
  }

  private fun modeFraming(resolvedMode: ResolvedReviewExecutionMode): String = buildString {
    if (resolvedMode == ResolvedReviewExecutionMode.INLINE) {
      appendLine("Run exactly one bill-code-review mode:inline review prompt in this context.")
      appendLine("Resolved execution mode: inline")
      appendLine(
        "Depth: reduced. Merge the routed areas below into one combined checklist and traverse the " +
          "diff exactly once against it, holding all areas in mind simultaneously, under a bounded " +
          "budget. Never re-walk the diff once per area; coverage is accounted per area in your " +
          "output, not by separate passes. This is not equivalent coverage to a full per-specialist " +
          "review and must not be presented as one; state that specialist depth was not applied.",
      )
    } else {
      appendLine("Run one bill-code-review mode:delegated review over the routed specialist fan-out.")
      appendLine("Resolved execution mode: delegated")
      appendLine(
        "Depth: full. Launch one specialist worker per resolved rubric below. Pass each specialist's " +
          "raw return through unchanged — do not require a register shape from them. You alone author " +
          "the final review prose and verdict from whatever they returned.",
      )
    }
  }

  private fun parentPrompt(
    selected: List<ReviewSpecialistLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    resolvedMode: ResolvedReviewExecutionMode,
  ): String {
    val inline = resolvedMode == ResolvedReviewExecutionMode.INLINE
    return buildString {
      append(modeFraming(resolvedMode))
      appendLine("Detected stack: ${routedManifests.joinToString("+") { it.slug }.ifBlank { "generic" }}")
      val rubricLabel = selected.joinToString { launch ->
        val decision = launch.assignment.laneDecision
        "${decision.specialistSkillName}" +
          "[paths=${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }};" +
          "add-ons=${decision.addOns.joinToString("+").ifBlank { "none" }};" +
          "origins=${decision.originLayerChains.joinToString("|") { it.joinToString("->") }}]"
      }.ifBlank { "code-review" }
      appendLine("Authoritative routed rubric identities: $rubricLabel")
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine()
        appendLine("## Resolved rubric: ${decision.specialistSkillName}")
        appendLine("Owned paths: ${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }}")
        launch.rubrics.forEach { rubric -> appendLine(rubric.body) }
      }
      appendLine(
        "Use the assigned bundle below as authoritative. Fetch every body through the bound broker " +
          "by calling read_evidence with an owned repository-relative path exactly as spelled in " +
          "'Owned paths'. The evidence_locator store_path and payload_file identify a hunk inside " +
          "the broker's own store; they are not read_evidence arguments and passing one is refused.",
      )
      appendLine(if (inline) INLINE_DEPTH_DIRECTIVE else DELEGATED_DEPTH_DIRECTIVE)
      appendLine(
        "Return free-form review prose and end with an explicit verdict line: " +
          "`verdict: approved` or `verdict: changes_requested` (needs_fix is accepted as changes_requested). " +
          "There is no findings-register format gate and no $NO_FINDINGS_TOKEN requirement — " +
          "missing or imperfect register lines never fail the review.",
      )
      appendLine(
        "When you have concrete defects, also emit optional `[F-XXX]` register lines so claim " +
          "verification can re-check them: " +
          "'[F-XXX] Severity | Confidence | specialist=<skill name from Resolved rubric> | " +
          "commits=<sha>[,<sha>] | path=\"<repo-relative path>\" | line=<positive integer> | description'. " +
          "Use only the bare skill name for specialist — never copy the [paths=...;add-ons=...;origins=...] " +
          "annotation from the routed rubric catalog. Imperfect lines remain part of the prose result " +
          "and never block settlement; parsed lines are optional verification enrichment.",
      )
      appendLine()
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine("## Assigned bundle: ${decision.specialistSkillName}")
        appendLine("Owned paths: ${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }}")
        appendAssignedBundleEvidence(launch)
      }
    }
  }

  private fun StringBuilder.appendAssignedBundleEvidence(launch: ReviewSpecialistLaunchRequest) {
    governedLaunchFor(launch).deliveredEntries.forEach { entry ->
      val hunk = entry.hunk
      val locator = hunk.evidenceLocator
      appendLine(
        "### Commit ${structuredString(entry.commitSha)} (order=${entry.orderIndex}, " +
          "path=${structuredString(hunk.path)})",
      )
      appendLine("Subject: ${structuredString(entry.subject.replace("\r\n", "\n"))}")
      appendLine("hunk_id: ${hunk.hunkId}")
      appendLine("spans: -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount}")
      appendLine("content_digest: ${hunk.contentDigest}")
      appendLine(
        "evidence_locator: store_path=${structuredString(locator.storePath)} " +
          "payload_file=${structuredString(locator.payloadFile)} " +
          "hunk_header=${structuredString(locator.hunkHeader)}",
      )
    }
  }

  /**
   * One parent session reaches the broker through one endpoint, and that endpoint stamps a single
   * lane on every request it forwards. Binding a lane-keyed fan-out here would therefore expose
   * only the first lane's assignment and refuse every other routed area's owned paths as
   * unassigned, so the parent binds one surface: the union of the areas it was selected to review.
   */
  private fun parentEvidenceBroker(
    selected: List<ReviewSpecialistLaunchRequest>,
    repoRoot: Path,
  ): ReviewEvidenceBroker = reviewEvidenceBrokerFactory.brokerFor(parentBrokerBinding(selected, repoRoot))

  /**
   * Parent broker allowance is the sum of each selected specialist's derived lane evidence budget,
   * so [ReviewContextBudgetPolicy.maxLaneEvidenceBytes] means the same cumulative read_evidence cap
   * at both seams. Per-read and per-turn caps do not scale with lane count.
   */
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
      // The merged surface is every routed area's union, so it is no longer any single routed
      // lane's column: it carries no routing, and commits one area skipped are still owned here.
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

  private fun governedLaunchFor(request: ReviewSpecialistLaunchRequest): GovernedReviewLaunch = GovernedReviewLaunch(
    assignment = request.assignment,
    packet = request.packet,
    specialistContract = request.specialistContract,
    rubric = request.rubrics.first().body,
    brokerId = request.brokerId,
    budget = request.budget,
  )

  /**
   * The lane's coverage as it actually ended. Bundle segmentation only says whether the assembled
   * bundle fit the budget; both parallel agents review every selected skill, so a lane whose parent
   * agent run did not succeed reviewed nothing durable and is incomplete regardless of bundle fit.
   * Durable rows, the coverage report, and the integration pass's lane summaries all read this.
   */
  private fun effectiveCompletionState(
    launch: ReviewSpecialistLaunchRequest,
    outcomes: ParallelReviewLaneRunResult,
  ): ReviewLaneCompletionState {
    val governed = governedLaunchFor(launch)
    val runCompletion = if (outcomes.lane1.success && outcomes.lane2.success) {
      governed.completionState
    } else {
      governed.completionState.asFailedLaneRun(
        governed.assembledBundle.entries.map { "${it.commitSha}@${it.hunk.path}" },
      )
    }
    val assignedUnits = governed.assembledBundle.entries
      .map { "${it.commitSha}@${it.hunk.path}" }
      .toSet()
    val deniedUnits = listOf(outcomes.lane1, outcomes.lane2)
      .flatMap { outcome ->
        val fromAccounting = (outcome.specialistAccounting + listOfNotNull(outcome.accounting))
          .filter { it.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION }
          .flatMap { it.unreviewedUnits }
        val fromOutcome = outcome.takeIf { it.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION }
          ?.unreviewedUnits
          .orEmpty()
        fromAccounting + fromOutcome
      }
      .filter { it in assignedUnits }
      .distinct()
    return if (deniedUnits.isEmpty()) {
      runCompletion
    } else {
      runCompletion.withBrokerEvidenceRefusal(deniedUnits)
    }
  }

  private fun brokerEvidenceCompletionState(
    completion: ReviewLaneCompletionState,
    accounting: ReviewLaneAccounting,
  ): ReviewLaneCompletionState = if (accounting.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION) {
    completion.withBrokerEvidenceRefusal(accounting.unreviewedUnits)
  } else {
    completion
  }

  private fun aggregateBundleCompletion(states: List<ReviewLaneCompletionState>): ReviewLaneCompletionState {
    if (states.isEmpty()) {
      return ReviewLaneCompletionState(
        disposition = ReviewLaneReviewDisposition.COMPLETE,
        bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
        segments = emptyList(),
      )
    }
    val incomplete = states.filter { it.disposition == ReviewLaneReviewDisposition.INCOMPLETE }
    if (incomplete.isEmpty()) {
      return states.first()
    }
    return ReviewLaneCompletionState(
      disposition = ReviewLaneReviewDisposition.INCOMPLETE,
      bundleCompositionDigest = incomplete.first().bundleCompositionDigest,
      segments = incomplete.flatMap { it.segments }.distinctBy { it.segmentId },
      unreviewedSegmentIds = incomplete.flatMap { it.unreviewedSegmentIds }.distinct(),
      unreviewedUnits = incomplete.flatMap { it.unreviewedUnits }.distinct(),
      budgetDimension = incomplete.first().budgetDimension,
    )
  }

  private fun captureLane(lane: () -> ParallelReviewLaneOutcome): ParallelReviewLaneOutcome = try {
    lane()
  } catch (seam: ReviewRegisterParseSeamException) {
    throw seam
  } catch (@Suppress("TooGenericExceptionCaught") thrown: Exception) {
    ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "lane launch threw ${thrown::class.simpleName}: ${thrown.message ?: "no detail"}",
    )
  }

  private fun attributeInlineFindings(
    parsed: ParallelReviewParseResult,
    selected: List<ReviewSpecialistLaunchRequest>,
  ): List<ParallelReviewRawFinding> {
    val fallbackLane = selected.minByOrNull { it.assignment.laneDecision.orderIndex }
    val fallbackPath = fallbackLane?.assignment?.assignedPaths?.firstOrNull()
      ?: ParallelReviewFindingParser.UNASSIGNED_REPOSITORY_PATH
    return parsed.findings.map { finding ->
      val findingPath = finding.repositoryPath
      val pathOwners = selected.filter { launch ->
        findingPath != null && launch.assignment.assignedPaths.any { path -> path == findingPath }
      }.distinctBy { it.assignment.laneDecision.specialistSkillName }
      val owner = resolveInlineFindingOwner(finding.specialistSkillName, pathOwners, selected)
        ?: fallbackLane
      val path = when {
        findingPath != null &&
          findingPath != ParallelReviewFindingParser.UNASSIGNED_REPOSITORY_PATH -> findingPath
        else -> fallbackPath
      }
      val line = finding.line ?: FIRST_SOURCE_LINE
      finding.copy(
        specialistSkillName = owner?.assignment?.laneDecision?.specialistSkillName
          ?: finding.specialistSkillName,
        originLayerChains = owner?.assignment?.laneDecision?.originLayerChains.orEmpty(),
        repositoryPath = path,
        line = line,
        location = "$path:$line",
      )
    }
  }

  private fun resolveInlineFindingOwner(
    declaredSpecialist: String?,
    pathOwners: List<ReviewSpecialistLaunchRequest>,
    selected: List<ReviewSpecialistLaunchRequest>,
  ): ReviewSpecialistLaunchRequest? {
    val selectedByName = selected.distinctBy { it.assignment.laneDecision.specialistSkillName }
    if (declaredSpecialist != null) {
      selectedByName.singleOrNull { it.assignment.laneDecision.specialistSkillName == declaredSpecialist }
        ?.let { return it }
    }
    return pathOwners.minByOrNull { it.assignment.laneDecision.orderIndex }
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
    facts.spawnFailed -> buildString {
      append("agent process failed to spawn")
      agentFailureExcerpt(facts.stderr, facts.stdout, STDERR_EXCERPT_MAX_LENGTH)?.let { excerpt ->
        append(" — ${excerpt.lineSequence().first().take(STDERR_EXCERPT_MAX_LENGTH)}")
      }
    }
    facts.interrupted -> "agent was interrupted"
    facts.exitStatus == null -> "agent exited with unknown status"
    facts.exitStatus != 0 -> buildString {
      append("agent exited with status ${facts.exitStatus}")
      agentFailureExcerpt(facts.stderr, facts.stdout, STDERR_EXCERPT_MAX_LENGTH)?.let { excerpt ->
        append(" — ${excerpt.lineSequence().first().take(STDERR_EXCERPT_MAX_LENGTH)}")
      }
    }
    // A truncated stdout means the retained bytes may not contain the agent's actual result at
    // all; parsing it as if complete risks reporting a false empty success instead of surfacing
    // the truncation.
    facts.stdoutTruncated -> "agent output exceeded the retention cap before completion"
    else -> null
  }

  private fun rejectedCandidateDiagnostic(parsed: ParallelReviewParseResult): String? {
    val rejection = parsed.rejections.firstOrNull() ?: return null
    return "dropped ${parsed.rejections.size} of ${parsed.candidateCount} [F-XXX] candidate line(s); " +
      "first at line ${rejection.linePosition} rejected as ${rejection.reason.wireValue}: " +
      rejection.lineText.take(REGISTER_ABSENCE_EXCERPT_MAX_LENGTH)
  }

  private sealed class GovernedEvidenceBind {
    class Bound(
      val broker: ReviewEvidenceBroker,
      val protocol: NativeReviewOperationProtocol,
      val endpoint: GovernedReviewEvidenceEndpointHandle,
    ) : GovernedEvidenceBind()

    class Unbound(
      val seam: String,
      val fault: GovernedEvidenceBindFault,
    ) : GovernedEvidenceBind()
  }

  private enum class GovernedEvidenceBindFault(val wireValue: String) {
    CONSTRUCTION("construction"),
    PROTOCOL("protocol"),
    ENDPOINT("endpoint"),
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MINUTES = 30L
    const val TIMEOUT_BUFFER_SECONDS = 30L
    const val SECONDS_PER_MINUTE = 60L
    const val STDERR_EXCERPT_MAX_LENGTH = 120
    const val REGISTER_ABSENCE_EXCERPT_MAX_LENGTH = 800
    const val MAX_SUPPLIED_DIFF_BYTES = 1_000_000L
    const val FIRST_SOURCE_LINE = 1
    const val HEAD_REVISION = "HEAD"

    // A standalone review carries no run id; its evidence is still addressed by checkpoint
    // fingerprint, so one shared slot per repo is correct rather than a collision.
    const val SHARED_EVIDENCE_WORKFLOW_ID = "code-review"
    const val INLINE_NATIVE_WORKER = "bill-code-review-inline"
    const val NO_SEQUENCE_DIGEST = "no-commit-sequence"
    const val NO_FINDINGS_TOKEN = "NO_FINDINGS"
  }

  private data class StackDetection(
    val routed: List<PlatformManifest>,
    val manifests: List<PlatformManifest>,
    val ownedPathsBySlug: Map<String, Set<String>>,
  )
}

@Suppress("LongParameterList") // assembles the full result record; every part is required
private fun parallelResult(
  agent1Id: String,
  @Suppress("UnusedParameter") agent2Id: String?,
  outcomes: skillbill.ports.review.model.ParallelReviewLaneRunResult,
  integration: ReviewIntegrationPassOutcome,
  coverage: ReviewCoverageReport?,
  packet: ReviewContextPacket?,
  budget: ReviewContextBudgetPolicy,
  stageResume: ReviewStageResumeReport?,
): ParallelCodeReviewResult {
  val prose = outcomes.lane1.rawOutput.ifBlank { "Review completed with no prose body." }
  val lane1Result = ParallelReviewLaneResult(
    agentId = agent1Id,
    findings = outcomes.lane1.findings,
  )
  val merged = ParallelReviewMerger.merge(
    lane1Result,
    ParallelReviewLaneResult(agentId = "", findings = emptyList()),
    integration.findings.takeIf { it.isNotEmpty() }
      ?.let { ParallelReviewLaneResult(ReviewIntegrationPassRunner.INTEGRATION_LANE, it) },
  )
  return ParallelCodeReviewResult(
    mergeResult = merged.copy(formattedOutput = prose),
    lane1 = outcomes.lane1.toStatus(agent1Id),
    lane2 = ParallelReviewLaneStatus(agentId = "", success = true),
    accountingSummary = parallelAccountingSummary(outcomes, includeLane2 = false)
      ?.withCommitFocusedAccounting(packet, budget, integration, coverage),
    integration = integration,
    coverage = coverage,
    stageResume = stageResume,
  )
}

private fun ParallelReviewLaneOutcome.toStatus(agentId: String) = ParallelReviewLaneStatus(
  agentId,
  success,
  failureReason,
  droppedCandidateDiagnostic,
  budgetOutcome,
  accounting,
  specialistAccounting,
)

private fun parallelAccountingSummary(
  outcomes: skillbill.ports.review.model.ParallelReviewLaneRunResult,
  includeLane2: Boolean,
): ReviewAccountingSummary? {
  val accountedLanes = listOfNotNull(outcomes.lane1, outcomes.lane2.takeIf { includeLane2 })
  val specialists = accountedLanes.flatMap { it.specialistAccounting }
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
    terminalOutcome = terminalStatus,
    bundleCompositionDigest = bundleCompositionDigest,
    segmentAccounting = segmentAccounting,
    unreviewedSegmentIds = unreviewedSegmentIds,
  )
  val roots = accountedLanes.mapIndexed { index, outcome ->
    ReviewAccountingInput(
      lane = "parallel-agent-${index + 1}",
      assignmentDigest = sha256HexUtf8("parallel-agent-${index + 1}"),
      children = outcome.specialistAccounting.map(ReviewLaneAccounting::toInput),
      terminalOutcome = laneTerminalOutcome(outcome),
      bundleCompositionDigest = outcome.bundleCompositionDigest,
      segmentAccounting = outcome.segmentAccounting,
      unreviewedSegmentIds = outcome.unreviewedSegmentIds,
    )
  }
  return ReviewTreeAccounting.summarize(
    reviewId = specialists.first().reviewId,
    packetDigest = specialists.first().packetDigest,
    root = ReviewAccountingInput("parallel-review", sha256HexUtf8("parallel-review"), children = roots),
  )
}

/**
 * Attaches the commit-focused accounting the routing decision and the integration pass produced.
 * Every value here is identity, a count, or a lane name — never a commit subject, a path, or diff
 * text — so the durable accounting record stays free of code content.
 */
private fun ReviewAccountingSummary.withCommitFocusedAccounting(
  packet: ReviewContextPacket?,
  budget: ReviewContextBudgetPolicy,
  integration: ReviewIntegrationPassOutcome,
  coverage: ReviewCoverageReport?,
): ReviewAccountingSummary {
  if (packet == null) return this
  val matrix = packet.routingMatrix
  val focusedCommits = matrix.decisions.filter { it.focused }.map { it.commitSha }.distinct()
  return copy(
    commitRouting = ReviewCommitRoutingAccounting(
      commitSequenceDigest = packet.commitSequenceDigest,
      routingDigest = matrix.routingDigest,
      commitCount = matrix.commitShas.size,
      laneCount = matrix.lanes.size,
      focusedCommitCount = focusedCommits.size,
      skippedCommitCount = matrix.commitShas.size - focusedCommits.size,
      focusedPairCount = matrix.focusedPairCount,
      skippedPairCount = matrix.analyzedPairCount - matrix.focusedPairCount,
      incompleteLanes = coverage?.incompleteLanes?.map { it.lane }?.sorted().orEmpty(),
    ),
    parentAnalysis = ReviewParentAnalysisConsumption(
      analyzedPairs = matrix.analyzedPairCount,
      analyzedBytes = matrix.canonical.toByteArray(Charsets.UTF_8).size.toLong(),
      maxAnalysisPairs = budget.maxRoutingAnalysisPairs,
      maxAnalysisBytes = budget.maxRoutingAnalysisBytes,
    ),
    integration = ReviewIntegrationAccounting(
      commitSequenceDigest = integration.commitSequenceDigest,
      terminalOutcome = integration.terminalOutcome.wireValue,
      summarizedLaneCount = integration.summarizedLaneCount,
      findingCount = integration.findings.size,
      counters = ReviewAccountingCounters(
        launchBytes = integration.launchBytes,
        resultBytes = integration.resultBytes,
        modelTurns = integration.modelTurns,
      ),
      skipReason = integration.skipReason?.takeIf { it.isNotBlank() }
        ?: coverage?.integrationNotApplicableReason?.takeIf { it.isNotBlank() }
        ?: "the review compiled commit routing without recording why integration was not applicable"
          .takeIf {
            integration.terminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE
          },
    ),
  )
}

private fun laneTerminalOutcome(outcome: ParallelReviewLaneOutcome): String = when {
  outcome.reviewDisposition == ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
  outcome.success -> "completed"
  else -> "partial_failure"
}

/** One parent-session review pass: the lanes it covers, the prompt it sent, and its bundle state. */
private class InlineParentLaunch(
  val agentId: String,
  val selected: List<ReviewSpecialistLaunchRequest>,
  val prompt: String,
  val bundleState: ReviewLaneCompletionState,
) {
  val assignment: ReviewAssignment get() = selected.first().assignment
}

/**
 * An inline lane runs the whole review in one parent session, so its accounting node owns the
 * parent's own launch and result bytes and exactly one model turn. It has no specialist children.
 */
private fun inlineParentAccounting(
  launch: InlineParentLaunch,
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

private const val INLINE_DEPTH_DIRECTIVE: String =
  "Merge every routed rubric above into one combined checklist, then traverse the diff exactly " +
    "once against it at reduced depth in this agent context, holding all rubrics in mind " +
    "simultaneously, and do not launch specialists. Never re-walk the diff once per rubric. " +
    "Write free-form prose findings. Optional register lines are best-effort verification hints."

private const val DELEGATED_DEPTH_DIRECTIVE: String =
  "Assign each routed rubric above to its own specialist worker over that rubric's owned paths. " +
    "Accept each specialist's raw return as-is with no shape check. Synthesize the final review " +
    "prose and verdict yourself from those returns."

/** Terminal status of a resume pass that had no incomplete lane left to launch a worker for. */
internal const val NO_OP_RESUME_TERMINAL_STATUS: String = "no_op_resume"
internal const val UNSUPPORTED_PROVIDER_TERMINAL_STATUS: String = "unsupported_provider"

internal const val INLINE_FINDING_PARSE_SEAM: String = "attributeInlineFindings"

/**
 * The single seam every inline lane register parse routes through. A parser fault escapes as a typed
 * error naming the seam and lane instead of degrading into an empty finding list, so no lane outcome
 * and no register-absent accounting row is built for what is actually a parser defect.
 */
internal fun parseLaneRegisterSeam(
  stdout: String,
  lane: String,
  parse: (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse,
): ParallelReviewParseResult = try {
  parse(stdout)
} catch (@Suppress("TooGenericExceptionCaught") thrown: RuntimeException) {
  throw ReviewRegisterParseSeamException(seam = INLINE_FINDING_PARSE_SEAM, lane = lane, cause = thrown)
}

/**
 * A parent pass that launched no worker because every lane already held a durable result. It records
 * an explicit no-op instead of returning silently, so a run that reviewed nothing is distinguishable
 * from one that reviewed everything cleanly.
 */
private fun noOpResumeOutcome(agentId: String) = ParallelReviewLaneOutcome(
  success = true,
  rawOutput = "",
  accounting = ReviewLaneAccounting(
    lane = agentId,
    evidenceBytes = 0,
    expansions = emptyList(),
    toolCalls = 0,
    modelTurns = 0,
    resultBytes = 0,
    terminalStatus = NO_OP_RESUME_TERMINAL_STATUS,
    reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
    bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
  ),
  reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
  bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
)

private fun inlineTerminalStatus(facts: AgentRunLaunchFacts, disposition: ReviewLaneReviewDisposition): String = when {
  disposition == ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
  facts.timedOut -> "timeout"
  facts.interrupted -> "interrupted"
  facts.spawnFailed -> "spawn_failure"
  facts.exitStatus != 0 -> "process_failure"
  else -> "completed"
}
