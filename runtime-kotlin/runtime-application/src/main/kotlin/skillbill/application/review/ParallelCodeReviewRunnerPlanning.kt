package skillbill.application.review
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.reviewevidence.ReviewCommitRange
import skillbill.application.reviewevidence.ReviewDiffEvidence
import skillbill.application.reviewevidence.SharedReviewEvidenceProjection
import skillbill.application.reviewevidence.SharedReviewEvidenceQuery
import skillbill.application.reviewevidence.SharedReviewEvidenceResolution
import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.context.model.toCodeReviewExecutionMode
import skillbill.review.model.ParallelReviewMergeResult
import java.nio.file.Path

internal class ParallelCodeReviewRunnerPlanning(
  val diffResolver: DiffResolverPort,
  val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  val installedPackCatalog: InstalledPlatformPackCatalogPort,
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  private val specIntentProjectionResolver: SpecIntentProjectionResolver,
  private val rubricPlanning: ParallelCodeReviewRunnerRubricPlanning,
  private val lanePlanRecording: ParallelCodeReviewRunnerLanePlanRecording,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  internal fun prepareInitialRun(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewInitialRun {
    val agent1 = resolveAgent(originalRequest.agent1Id, "--agent1")
    val revisions = resolveReviewRevisions(originalRequest)
    val sharedEvidence = SharedReviewEvidenceResolution(sharedEvidenceResolver, diffResolver).resolve(
      SharedReviewEvidenceQuery(
        repoRoot = originalRequest.repoRoot,
        workflowId = originalRequest.reviewRunId ?: PARALLEL_REVIEW_SHARED_EVIDENCE_WORKFLOW_ID,
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
    val resolvedMode = ReviewExecutionModePolicy.resolve(request.resolvedTier ?: request.codeReviewMode)
    val compiled = prepare(
      PlanningPrepareArgs(
        request = request,
        revisions = revisions,
        diffText = diffText,
        evidence = evidence,
        sharedSequence = sharedEvidence.sequence,
        routedManifests = detection.routed,
        manifests = detection.manifests,
        ownedPathsBySlug = detection.ownedPathsBySlug,
        agentIds = listOf(agent1.id),
        budget = budget,
        evidenceStorePath = sharedEvidence.storePath,
      ),
    )
    return ParallelCodeReviewInitialRun(
      request = request,
      detection = detection,
      resolvedMode = resolvedMode,
      agent1Id = agent1.id,
      preparedLaunchRequests = compiled.toRun,
      compiledLaunchRequests = compiled.all,
      budget = budget,
      specIntentResolution = compiled.specIntentResolution,
    )
  }

  fun completeEmptySuppliedDelta(
    request: ParallelCodeReviewRequest,
    recordAdjudicationBoundary: (String) -> Unit,
  ): ParallelCodeReviewResult {
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
    lanePlanRecording.recordSpecIntent(request.reviewRunId, specIntent)
    request.reviewRunId?.let { runId ->
      if (specIntent is SpecIntentResolution.Resolved) {
        recordAdjudicationBoundary(runId)
      }
    }
    return ParallelCodeReviewResult(
      mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = "NO_FINDINGS"),
      lane1 = ParallelReviewLaneStatus(agentId = request.agent1Id, success = true),
    )
  }

  private fun resolvedMode(request: ParallelCodeReviewRequest) = ReviewExecutionModePolicy.resolveWithRule(
    requested = request.resolvedTier ?: request.codeReviewMode,
  ).resolvedMode

  private fun prepare(args: PlanningPrepareArgs): ParallelCodeReviewCompiledLaunches {
    if (
      sharedEvidenceLocatorReader !== FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE &&
      args.evidenceStorePath.isNullOrBlank()
    ) {
      throw ReviewHunkEvidenceLocatorMissingError(args.evidenceStorePath.orEmpty())
    }
    val plannedRubrics = rubricPlanning.resolvePlannedRubrics(
      args.evidence,
      args.routedManifests,
      args.manifests,
      args.ownedPathsBySlug,
    )
    val (baseRevision, headRevision) = args.revisions
    val commitSequence = SharedReviewEvidenceProjection.project(args.sharedSequence, args.evidence)
    val specIntentResolution = resolveSpecIntent(args.request, args.evidence, args.budget)
    val compiled = ParallelReviewPreparationCompiler.compile(
      input = ParallelReviewPreparationInput(
        diff = args.diffText,
        evidence = args.evidence,
        commitSequence = commitSequence,
        stack = args.routedManifests.joinToString("+") { it.slug }.ifBlank { null },
        agents = args.agentIds,
        repositoryEnclosingRootPort = repositoryEnclosingRootPort,
        repoRoot = args.request.repoRoot,
        routedPacks = args.routedManifests.map { it.slug },
        lanes = plannedRubrics,
        reviewRunId = args.request.reviewRunId,
        baseRevision = baseRevision,
        headRevision = headRevision,
        prelaunchExpansions = args.request.prelaunchExpansions,
        baselineUntrackedPolicy = args.request.baselineUntrackedPolicy,
        specIntentResolution = specIntentResolution,
        evidenceStorePath = args.evidenceStorePath,
      ),
      budget = args.budget,
      envelopeValidator = reviewContextEnvelopeValidator,
      specialistContract = reviewSpecialistContractProvider.authoritativeContract(),
      hunkLocatorReader = sharedEvidenceLocatorReader,
    )
    val selected = lanePlanRecording.selectLaunchesForResume(args.request.reviewRunId, compiled)
    lanePlanRecording.recordPlannedLanes(args.request.reviewRunId, plannedRubrics, selected)
    lanePlanRecording.recordSpecIntent(args.request.reviewRunId, specIntentResolution)
    return ParallelCodeReviewCompiledLaunches(
      all = compiled,
      toRun = selected,
      specIntentResolution = specIntentResolution,
    )
  }

  private fun resolveSpecIntent(
    request: ParallelCodeReviewRequest,
    evidence: ReviewDiffEvidence,
    budget: ReviewContextBudgetPolicy,
  ): SpecIntentResolution = specIntentProjectionResolver.resolve(
    SpecIntentProjectionResolveRequest(
      repoRoot = request.repoRoot,
      explicitSpecPath = request.specPath,
      branchName = currentHeadBranchName(request.repoRoot),
      changedPaths = evidence.files.map { it.path },
      budget = budget,
    ),
  )

  fun currentHeadBranchName(repoRoot: Path): String = diffResolver.runProcess(
    listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
    repoRoot,
  )?.trim().orEmpty()
}
