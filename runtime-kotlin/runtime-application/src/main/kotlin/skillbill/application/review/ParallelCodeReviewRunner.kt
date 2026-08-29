package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.model.ParallelReviewParseResult

@Inject
class ParallelCodeReviewRunner(
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val diffResolver: DiffResolverPort,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewRubricResolver: ReviewRubricResolver,
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  private val database: DatabaseSessionFactory,
  private val installedPackCatalog: InstalledPlatformPackCatalogPort = InstalledPlatformPackCatalogPort.NONE,
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort =
    FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE,
  private val specIntentProjectionResolver: SpecIntentProjectionResolver,
  private val reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  private val governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  private val nativeAgentPreflight: ReviewNativeAgentPreflightPort = ReviewNativeAgentPreflightPort.NONE,
  private val reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort = ReviewLaunchAgentStagingPort.NONE,
  private val registerParse: (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  private val failureHelpers = ParallelCodeReviewRunnerFailureHelpers(registerParse)
  private val rubricPlanning = ParallelCodeReviewRunnerRubricPlanning(reviewRubricResolver, installedPackCatalog)
  private val planning = ParallelCodeReviewRunnerPlanning(
    diffResolver,
    repoLocalConfig,
    reviewContextEnvelopeValidator,
    reviewSpecialistContractProvider,
    installedPackCatalog,
    sharedEvidenceResolver,
    sharedEvidenceLocatorReader,
    specIntentProjectionResolver,
    runtimeOwnedPersistence,
    rubricPlanning,
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
    if (originalRequest.suppliedDiff != null && originalRequest.suppliedDiff.isBlank()) {
      return planning.completeEmptySuppliedDelta(originalRequest) { reviewRunId ->
        verificationStages.recordAdjudicationBoundary(reviewRunId)
      }
    }
    val initial = planning.prepareInitialRun(originalRequest)
    verifyNativeWorkers(initial)
    val outcomes = laneLaunch.runLanes(initial)
    resultAssembly.recordLaneDispositions(initial, outcomes)
    val integration = resultAssembly.runIntegrationPass(initial, outcomes)
    val coverage = resultAssembly.coverageReport(initial, outcomes, integration)
    val result = resultAssembly.parallelResult(
      initial.agent1Id,
      outcomes,
      integration,
      coverage,
      initial.compiledLaunchRequests.firstOrNull()?.packet,
      initial.budget,
      resultAssembly.stageResumeReport(initial.request.reviewRunId),
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
