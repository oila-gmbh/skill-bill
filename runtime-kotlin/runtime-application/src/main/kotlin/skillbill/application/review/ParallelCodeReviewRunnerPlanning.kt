package skillbill.application.review

import skillbill.application.evidence.SharedReviewEvidenceCommits
import skillbill.application.evidence.SharedReviewEvidenceProjection
import skillbill.application.evidence.SharedReviewEvidenceQuery
import skillbill.application.evidence.SharedReviewEvidenceResolution
import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.review.model.DiffResolutionException
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewScope
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.StackDetectionException
import skillbill.application.review.model.UsageValidationException
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.install.model.InstallAgent
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.context.model.toCodeReviewExecutionMode
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import java.time.Instant

internal class ParallelCodeReviewRunnerPlanning(
  private val diffResolver: DiffResolverPort,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  private val installedPackCatalog: InstalledPlatformPackCatalogPort,
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  private val specIntentProjectionResolver: SpecIntentProjectionResolver,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  private val rubricPlanning: ParallelCodeReviewRunnerRubricPlanning,
) {
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

  @Suppress("LongMethod")
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
    budget: ReviewContextBudgetPolicy,
    evidenceStorePath: String?,
  ): ParallelCodeReviewCompiledLaunches {
    if (
      sharedEvidenceLocatorReader !== FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE &&
      evidenceStorePath.isNullOrBlank()
    ) {
      throw ReviewHunkEvidenceLocatorMissingError(evidenceStorePath.orEmpty())
    }
    val plannedRubrics = rubricPlanning.resolvePlannedRubrics(evidence, routedManifests, manifests, ownedPathsBySlug)
    val (baseRevision, headRevision) = revisions
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
    return ParallelCodeReviewCompiledLaunches(all = compiled, toRun = selected, specIntentResolution = specIntentResolution)
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

  private fun currentHeadBranchName(repoRoot: Path): String = diffResolver.runProcess(
    listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
    repoRoot,
  )?.trim().orEmpty()

  private fun resolveReviewRevisions(request: ParallelCodeReviewRequest): Pair<String, String> {
    val (base, head) = if (spansCommitRange(request)) canonicalRange(request) else declaredRange(request)
    if (base.isBlank() || head.isBlank()) {
      throw DiffResolutionException("Review base and head revisions must resolve to non-blank immutable identities.")
    }
    return base to head
  }

  private fun spansCommitRange(request: ParallelCodeReviewRequest): Boolean = !hasSuppliedDiff(request) &&
    (request.scope == ParallelReviewScope.BRANCH || request.scope == ParallelReviewScope.PR)

  private fun hasSuppliedDiff(request: ParallelCodeReviewRequest): Boolean =
    request.suppliedDiff != null || request.suppliedDiffPath != null

  private fun canonicalRange(request: ParallelCodeReviewRequest): Pair<String, String> {
    val head = canonicalRevision(request.headRevision ?: PARALLEL_REVIEW_HEAD_REVISION, request.repoRoot)
    val base = request.baseRevision?.let { canonicalRevision(it, request.repoRoot) } ?: when (request.scope) {
      ParallelReviewScope.PR -> detectPrBase(request.repoRoot)
      ParallelReviewScope.STAGED,
      ParallelReviewScope.UNSTAGED,
      ParallelReviewScope.BRANCH,
      -> detectBranchBase(request.repoRoot)
    }
    return base to head
  }

  private fun declaredRange(request: ParallelCodeReviewRequest): Pair<String, String> {
    val head = request.headRevision
      ?: if (hasSuppliedDiff(request)) PARALLEL_REVIEW_HEAD_REVISION
      else canonicalRevision(PARALLEL_REVIEW_HEAD_REVISION, request.repoRoot)
    return (request.baseRevision ?: head) to head
  }

  private fun canonicalRevision(revision: String, repoRoot: Path): String =
    diffResolver.runProcess(listOf("git", "rev-parse", "--verify", "$revision^{commit}"), repoRoot)
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?: throw DiffResolutionException("Review revision '$revision' does not resolve to a commit here.")

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
      diffResolver.readDiff(path, PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES)
        ?: throw DiffResolutionException(
          "--diff-file must name a readable, non-empty regular file no larger than " +
            "$PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES bytes.",
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

  private fun detectStack(evidence: ReviewDiffEvidence): ParallelCodeReviewStackDetection {
    val manifests = try {
      installedPackCatalog.manifests()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      throw StackDetectionException(
        "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
          "Repair the installed platform packs before running parallel review.",
        e,
      )
    }
    if (manifests.isEmpty()) return ParallelCodeReviewStackDetection(emptyList(), emptyList(), emptyMap())

    val routing = ReviewStackRouting.route(
      manifests,
      evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) },
    )
    val routed = manifests.filter { it.slug in routing.routedSlugs }
    return ParallelCodeReviewStackDetection(routed, manifests, routing.ownedPathsBySlug)
  }
}