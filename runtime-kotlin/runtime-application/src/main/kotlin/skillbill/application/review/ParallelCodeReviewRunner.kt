package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunnerDeps
import skillbill.application.review.model.ParallelReviewScope
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.review.ParallelReviewMerger
import skillbill.review.context.model.ResolvedReviewExecutionMode

@Inject
class ParallelCodeReviewRunner(deps: ParallelCodeReviewRunnerDeps) {
  private val parentReviewLauncher = deps.parentReviewLauncher
  private val diffResolver = deps.diffResolver
  private val repoLocalConfig = deps.repoLocalConfig
  private val reviewContextEnvelopeValidator = deps.reviewContextEnvelopeValidator
  private val reviewRubricResolver = deps.reviewRubricResolver
  private val reviewSpecialistContractProvider = deps.reviewSpecialistContractProvider
  private val database = deps.database
  private val installedPackCatalog = deps.installedPackCatalog
  private val sharedEvidenceResolver = deps.sharedEvidenceResolver
  private val sharedEvidenceLocatorReader = deps.sharedEvidenceLocatorReader
  private val specIntentProjectionResolver = deps.specIntentProjectionResolver
  private val reviewEvidenceBrokerFactory = deps.reviewEvidenceBrokerFactory
  private val governedEvidenceEndpointBinder = deps.governedEvidenceEndpointBinder
  private val nativeAgentPreflight = deps.nativeAgentPreflight
  private val reviewLaunchAgentStaging = deps.reviewLaunchAgentStaging
  private val registerParse = deps.registerParse
  private val diagnostics = deps.diagnostics
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  private val failureHelpers = ParallelCodeReviewRunnerFailureHelpers(registerParse)
  private val rubricPlanning = ParallelCodeReviewRunnerRubricPlanning(reviewRubricResolver, installedPackCatalog)
  private val planning = ParallelCodeReviewRunnerPlanning(
    ParallelCodeReviewRunnerPlanningDeps(
      diffResolver = diffResolver,
      repoLocalConfig = repoLocalConfig,
      reviewContextEnvelopeValidator = reviewContextEnvelopeValidator,
      reviewSpecialistContractProvider = reviewSpecialistContractProvider,
      installedPackCatalog = installedPackCatalog,
      sharedEvidenceResolver = sharedEvidenceResolver,
      sharedEvidenceLocatorReader = sharedEvidenceLocatorReader,
      specIntentProjectionResolver = specIntentProjectionResolver,
      runtimeOwnedPersistence = runtimeOwnedPersistence,
      rubricPlanning = rubricPlanning,
    ),
  )
  private val laneLaunch = ParallelCodeReviewRunnerLaneLaunch(
    parentReviewLauncher,
    reviewEvidenceBrokerFactory,
    governedEvidenceEndpointBinder,
    reviewLaunchAgentStaging,
    sharedEvidenceLocatorReader,
    failureHelpers,
  )
  private val resultAssembly = ParallelCodeReviewRunnerResultAssembly(
    parentReviewLauncher,
    reviewContextEnvelopeValidator,
    runtimeOwnedPersistence,
  )
  private val verificationStages = ParallelCodeReviewRunnerVerificationStages(
    parentReviewLauncher,
    reviewContextEnvelopeValidator,
    runtimeOwnedPersistence,
  )

  fun run(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    earlyEmptyDelta(originalRequest)?.let { return it }
    val initial = planning.prepareInitialRun(originalRequest)
    verifyNativeWorkers(initial)
    val outcomes = laneLaunch.runLanes(initial)
    resultAssembly.recordLaneDispositions(initial, outcomes)
    val integration = resultAssembly.runIntegrationPass(initial, outcomes)
    val coverage = resultAssembly.coverageReport(initial, outcomes, integration)
    val result = resultAssembly.parallelResult(
      ParallelResultArgs(
        agent1Id = initial.agent1Id,
        outcomes = outcomes,
        integration = integration,
        coverage = coverage,
        packet = initial.compiledLaunchRequests.firstOrNull()?.packet,
        budget = initial.budget,
        stageResume = resultAssembly.stageResumeReport(initial.request.reviewRunId),
      ),
    )
    resultAssembly.persistReviewPassClaims(
      initial.request.reviewRunId,
      result.mergeResult.findings,
      persistEmpty = true,
    )
    resultAssembly.recordReviewStageBoundary(
      initial.request.reviewRunId,
      integration,
      result.mergeResult.findings,
    )
    resultAssembly.recordMergedFindingLanes(initial.request.reviewRunId)
    val verificationVerdicts = verificationStages.runClaimVerification(initial, result)
    val adjudicationVerdicts = verificationStages.runSpecAdjudication(initial, result)
    val recordedVerdicts = verificationStages.recordedFindingVerdicts(
      initial.request.reviewRunId,
      verificationVerdicts + adjudicationVerdicts,
    )
    resultAssembly.emitReviewStageDegradations(initial.request.reviewRunId, outcomes)
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
      stageResume = resultAssembly.stageResumeReport(initial.request.reviewRunId),
    )
  }

  private fun earlyEmptyDelta(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult? {
    if (originalRequest.suppliedDiff != null && originalRequest.suppliedDiff.isBlank()) {
      return planning.completeEmptySuppliedDelta(originalRequest) { reviewRunId ->
        verificationStages.recordAdjudicationBoundary(reviewRunId)
      }
    }
    if (
      originalRequest.scope == ParallelReviewScope.WORKTREE_FROM_BASE &&
      !planning.hasSuppliedDiff(originalRequest)
    ) {
      val revisions = planning.resolveReviewRevisions(originalRequest)
      if (planning.resolveDiff(originalRequest, revisions).isBlank()) {
        return planning.completeEmptySuppliedDelta(originalRequest) { reviewRunId ->
          verificationStages.recordAdjudicationBoundary(reviewRunId)
        }
      }
    }
    return null
  }

  private fun verifyNativeWorkers(initial: ParallelCodeReviewInitialRun) {
    val nativeNames = initial.compiledLaunchRequests
      .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
      .mapNotNull { it.logicalWorkerName }
    val logicalNames = buildList {
      addAll(nativeNames)
      if (initial.resolvedMode == ResolvedReviewExecutionMode.INLINE) {
        add(PARALLEL_REVIEW_INLINE_NATIVE_WORKER)
      }
    }.distinct()
    if (logicalNames.isEmpty()) return
    nativeAgentPreflight.verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = initial.request.repoRoot,
        agentIds = listOf(initial.agent1Id),
        logicalNames = logicalNames,
      ),
    )
  }
}
