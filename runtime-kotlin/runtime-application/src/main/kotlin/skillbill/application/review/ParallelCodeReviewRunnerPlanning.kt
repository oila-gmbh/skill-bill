package skillbill.application.review

import skillbill.application.evidence.SharedReviewEvidenceProjection
import skillbill.application.evidence.SharedReviewEvidenceQuery
import skillbill.application.evidence.SharedReviewEvidenceResolution
import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.context.model.toCodeReviewExecutionMode
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.nio.file.Path
import java.time.Instant

internal class ParallelCodeReviewRunnerPlanning(deps: ParallelCodeReviewRunnerPlanningDeps) {
  internal val diffResolver: DiffResolverPort = deps.diffResolver
  internal val repoLocalConfig: RepoLocalConfigPort = deps.repoLocalConfig
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator = deps.reviewContextEnvelopeValidator
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider =
    deps.reviewSpecialistContractProvider
  internal val installedPackCatalog: InstalledPlatformPackCatalogPort = deps.installedPackCatalog
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort = deps.sharedEvidenceResolver
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort =
    deps.sharedEvidenceLocatorReader
  private val specIntentProjectionResolver: SpecIntentProjectionResolver = deps.specIntentProjectionResolver
  internal val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary = deps.runtimeOwnedPersistence
  private val rubricPlanning: ParallelCodeReviewRunnerRubricPlanning = deps.rubricPlanning

  fun prepareInitialRun(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewInitialRun {
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
    recordSpecIntent(request.reviewRunId, specIntent)
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

  fun recordSpecIntent(reviewRunId: String?, resolution: SpecIntentResolution) {
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
    val selected = selectLaunchesForResume(args.request.reviewRunId, compiled)
    recordPlannedLanes(args.request.reviewRunId, plannedRubrics, selected)
    recordSpecIntent(args.request.reviewRunId, specIntentResolution)
    return ParallelCodeReviewCompiledLaunches(
      all = compiled,
      toRun = selected,
      specIntentResolution = specIntentResolution,
    )
  }

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
        parallelCodeReviewGovernedLaunchFor(launch).completionState
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

  internal fun currentHeadBranchName(repoRoot: Path): String = diffResolver.runProcess(
    listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
    repoRoot,
  )?.trim().orEmpty()
}
