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
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
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
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneAssembledBundle
import skillbill.review.context.model.ReviewLaneCompletionState
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.TokenOwnership
import skillbill.review.context.model.structuredString
import skillbill.review.context.model.toCodeReviewExecutionMode
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewRunLaneSegmentAccountingJson
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Inject
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class ParallelCodeReviewRunner(
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val diffResolver: DiffResolverPort,
  private val parallelLaneRunner: ParallelReviewLaneRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewRubricResolver: ReviewRubricResolver,
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  private val database: DatabaseSessionFactory,
  private val installedReviewCatalog: InstalledReviewCatalogPort = InstalledReviewCatalogPort.NONE,
) {
  private data class InitialRun(
    val request: ParallelCodeReviewRequest,
    val detection: StackDetection,
    val resolvedMode: ResolvedReviewExecutionMode,
    val agent1Id: String,
    val agent2Id: String,
    val preparedLaunchRequests: List<ReviewSpecialistLaunchRequest>,
    val budget: ReviewContextBudgetPolicy,
  )

  fun run(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    val initial = prepareInitialRun(originalRequest)
    val outcomes = runLanes(initial)
    recordLaneDispositions(initial, outcomes)
    val result = parallelResult(initial.agent1Id, initial.agent2Id, outcomes)
    recordMergedFindingLanes(initial.request.reviewRunId, result)
    result.accountingSummary?.let { summary ->
      database.transaction { unitOfWork ->
        unitOfWork.reviews.saveAccounting(
          ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary.toBoundedPayload()),
        )
      }
    }
    return result
  }

  private fun prepareInitialRun(originalRequest: ParallelCodeReviewRequest): InitialRun {
    val agent1 = resolveAgent(originalRequest.agent1Id, "--agent1")
    val agent2 = resolveAgent(originalRequest.agent2Id, "--agent2")
    if (agent1.id == agent2.id) {
      throw UsageValidationException(
        "agent1 and agent2 must be different agents; both resolved to '${agent1.id}'.",
      )
    }
    val revisions = resolveReviewRevisions(originalRequest)
    val diffText = resolveDiff(originalRequest, revisions)
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
        revisions,
        diffText,
        evidence,
        detection.routed,
        detection.manifests,
        detection.ownedPathsBySlug,
        listOf(agent1.id, agent2.id),
        budget,
      ),
      budget = budget,
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
    val timeoutSec = request.timeout?.inWholeSeconds ?: DEFAULT_TIMEOUT_MINUTES * SECONDS_PER_MINUTE
    return parallelLaneRunner.runTwoLanes(
      ParallelReviewLaneRunRequest(
        lane1 = {
          launchParentLane(
            initial.agent1Id,
            byAgent[initial.agent1Id].orEmpty(),
            initial.detection.routed,
            initial.budget,
            request,
            null,
            initial.resolvedMode,
          )
        },
        lane2 = {
          launchParentLane(
            initial.agent2Id,
            byAgent[initial.agent2Id].orEmpty(),
            initial.detection.routed,
            initial.budget,
            request,
            request.agent2Model,
            initial.resolvedMode,
          )
        },
        timeout = (timeoutSec + TIMEOUT_BUFFER_SECONDS).seconds,
      ),
    )
  }

  private fun prepare(
    request: ParallelCodeReviewRequest,
    revisions: Pair<String, String>,
    diffText: String,
    evidence: ReviewDiffEvidence,
    routedManifests: List<PlatformManifest>,
    manifests: List<PlatformManifest>,
    ownedPathsBySlug: Map<String, Set<String>>,
    agentIds: List<String>,
    budget: skillbill.review.context.model.ReviewContextBudgetPolicy,
  ): List<ReviewSpecialistLaunchRequest> {
    val plannedRubrics = resolvePlannedRubrics(evidence, routedManifests, manifests, ownedPathsBySlug)
    val (baseRevision, headRevision) = revisions
    val commitSequence = ReviewCommitSequenceResolver(diffResolver).resolve(
      scope = request.scope,
      repoRoot = request.repoRoot,
      range = ReviewCommitRange(baseRevision, headRevision),
      aggregate = evidence,
      suppliedDiff = hasSuppliedDiff(request),
    )
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
      ),
      budget = budget,
      envelopeValidator = reviewContextEnvelopeValidator,
      specialistContract = reviewSpecialistContractProvider.authoritativeContract(),
    )
    val selected = selectLaunchesForResume(request.reviewRunId, compiled)
    recordPlannedLanes(request.reviewRunId, plannedRubrics, selected)
    return selected
  }

  /**
   * A resume re-runs only lanes whose durable disposition is not complete. Fresh runs keep the full
   * compiled set; completed durable results are never re-launched.
   */
  private fun selectLaunchesForResume(
    reviewRunId: String?,
    launches: List<ReviewSpecialistLaunchRequest>,
  ): List<ReviewSpecialistLaunchRequest> {
    if (reviewRunId == null || launches.isEmpty()) return launches
    val existing = database.transaction { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (existing.isEmpty()) return launches
    val resumeNames = ReviewRunLaneResolver.lanesToResume(existing)
      .map { it.laneSkillName }
      .toSet()
    return launches.filter { launch ->
      launch.assignment.laneDecision.specialistSkillName in resumeNames
    }
  }

  /**
   * Records the launch plan for a runtime-launched review at the moment it is resolved, so the run's
   * lane attribution comes from the plan itself rather than round-tripping through review text.
   * Disposition stays non-complete until [recordLaneDispositions] observes a durable single-pass
   * result, except budget-unreviewable segments which are incomplete immediately.
   */
  private fun recordPlannedLanes(
    reviewRunId: String?,
    plannedRubrics: List<PlannedReviewRubric>,
    launches: List<ReviewSpecialistLaunchRequest>,
  ) {
    if (reviewRunId == null || launches.isEmpty()) return
    val existing = database.transaction { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
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
    database.transaction { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, merged) }
  }

  /** Writes final per-lane disposition after the parallel pass so resume stays lane-granular. */
  private fun recordLaneDispositions(initial: InitialRun, outcomes: ParallelReviewLaneRunResult) {
    val reviewRunId = initial.request.reviewRunId ?: return
    val existing = database.transaction { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (existing.isEmpty()) return
    val completionBySkill = initial.preparedLaunchRequests.associate { launch ->
      requireNotNull(launch.assignment.laneDecision.specialistSkillName) to
        governedLaunchFor(launch).completionState
    }
    // Both parallel agents review the same specialist skills; a skill is durably complete only when
    // its bundle was reviewable and both parent agents finished successfully.
    val bothAgentsSucceeded = outcomes.lane1.success && outcomes.lane2.success
    val updated = existing.map { lane ->
      val completion = completionBySkill[lane.laneSkillName] ?: return@map lane
      val durableComplete = completion.disposition == ReviewLaneReviewDisposition.COMPLETE &&
        bothAgentsSucceeded
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
    database.transaction { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, updated) }
  }

  /**
   * Records which lane produced each merged finding straight from the merge result, where the
   * producing specialist is already known. Ingestion reads this rather than re-deriving the lane
   * from the formatted review text, which no agent is obliged to reproduce faithfully.
   */
  private fun recordMergedFindingLanes(reviewRunId: String?, result: ParallelCodeReviewResult) {
    if (reviewRunId == null) return
    val attribution = result.mergeResult.findings.mapNotNull { finding ->
      finding.specialistSkillNames.firstOrNull()?.let { finding.fNumber to it }
    }.toMap()
    if (attribution.isEmpty()) return
    database.transaction { unitOfWork -> unitOfWork.reviews.recordFindingLaneAttribution(reviewRunId, attribution) }
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

  private fun resolveDiff(request: ParallelCodeReviewRequest, revisions: Pair<String, String>): String {
    val (base, head) = revisions
    val diffText = request.suppliedDiff ?: request.suppliedDiffPath?.let { path ->
      diffResolver.readDiff(path, MAX_SUPPLIED_DIFF_BYTES)
        ?: throw DiffResolutionException(
          "--diff-file must name a readable, non-empty regular file no larger than $MAX_SUPPLIED_DIFF_BYTES bytes.",
        )
    } ?: when (request.scope) {
      ParallelReviewScope.STAGED -> runDiff(listOf("git", "diff", "--cached"), request.repoRoot)
      ParallelReviewScope.UNSTAGED -> runDiff(listOf("git", "diff"), request.repoRoot)
      // The aggregate delta spans the same canonical range the commit sequence does, so the
      // base-to-head equivalence fact compares two views of one range rather than two ranges.
      ParallelReviewScope.BRANCH -> runDiff(listOf("git", "diff", base, head), request.repoRoot)
      // Falls back to the PR's own diff when its commits are not in the local object store, which
      // is the case the SYNTHETIC_AGGREGATE_PR_DIFF unit exists for.
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

  private fun launchParentLane(
    agentId: String,
    launchRequests: List<ReviewSpecialistLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    budget: ReviewContextBudgetPolicy,
    request: ParallelCodeReviewRequest,
    modelOverride: String?,
    resolvedMode: ResolvedReviewExecutionMode,
  ): ParallelReviewLaneOutcome {
    // Resume may leave one or both parent agents with no incomplete specialists; that is a no-op
    // pass, not a routing failure. A fresh run still fails loudly when compile produced no launches.
    if (launchRequests.isEmpty()) {
      return ParallelReviewLaneOutcome(
        success = true,
        rawOutput = "",
        reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
        bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
      )
    }
    val selected = launchRequests.sortedBy { it.assignment.laneDecision.orderIndex }
    val bundleStates = selected.map(::governedLaunchFor).map { it.completionState }
    val aggregateBundleState = aggregateBundleCompletion(bundleStates)
    val prompt = parentPrompt(selected, routedManifests, resolvedMode)
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
        accounting = inlineParentAccounting(
          agentId,
          inlineAssignment,
          prompt,
          "unsupported_provider",
          null,
          aggregateBundleState,
        ),
        reviewDisposition = aggregateBundleState.disposition,
        bundleCompositionDigest = aggregateBundleState.bundleCompositionDigest,
        segmentAccounting = aggregateBundleState.segments,
        unreviewedSegmentIds = aggregateBundleState.unreviewedSegmentIds,
        budgetDimension = aggregateBundleState.budgetDimension,
      )
      is AgentRunLaunchFacts -> {
        val budgetOutcome = ReviewBudgetEvaluator.laneResultOutcome(
          ReviewLaneIdentity.of(inlineAssignment),
          budget,
          outcome.stdout.toByteArray().size.toLong(),
        )
        val reason = budgetOutcome?.let { ReviewContextBudgetExceededException(it).message }
          ?: laneFailureReason(outcome)
        val terminalStatus = inlineTerminalStatus(outcome, aggregateBundleState.disposition)
        ParallelReviewLaneOutcome(
          success = reason == null,
          rawOutput = outcome.stdout,
          failureReason = reason,
          budgetOutcome = budgetOutcome,
          tokenUsage = providerTokenUsage(outcome),
          accounting = inlineParentAccounting(
            agentId,
            inlineAssignment,
            prompt,
            terminalStatus,
            outcome,
            aggregateBundleState,
          ),
          findings = if (reason == null) attributeInlineFindings(outcome.stdout, selected) else emptyList(),
          reviewDisposition = aggregateBundleState.disposition,
          bundleCompositionDigest = aggregateBundleState.bundleCompositionDigest,
          segmentAccounting = aggregateBundleState.segments,
          unreviewedSegmentIds = aggregateBundleState.unreviewedSegmentIds,
          budgetDimension = aggregateBundleState.budgetDimension,
        )
      }
    }
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
        "Depth: full. Launch one specialist worker per resolved rubric below and merge their " +
          "registers. Inability to launch a required worker blocks loudly; never degrade to the " +
          "single-prompt inline lane.",
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
      }.ifBlank { "parallel-code-review" }
      appendLine("Authoritative routed rubric identities: $rubricLabel")
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine()
        appendLine("## Resolved rubric: ${decision.specialistSkillName}")
        appendLine("Owned paths: ${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }}")
        launch.rubrics.forEach { rubric -> appendLine(rubric.body) }
      }
      appendLine("Use the assigned bundle evidence below as authoritative; do not rediscover or replace its scope.")
      if (inline) {
        appendLine(
          "Merge every routed rubric above into one combined checklist, then traverse the diff exactly " +
            "once against it at reduced depth in this agent context, holding all rubrics in mind " +
            "simultaneously, and do not launch specialists. Never re-walk the diff once per rubric: " +
            "iterating rubrics over the same code is the same review repeated N times at N times the " +
            "cost, and it misses defects that only surface where two rubrics intersect. " +
            "Follow only the signals that appear; do not build a case for a marginal finding. " +
            "Depth and budget are lowered here — the severity vocabulary, the finding admission gate, the " +
            "evidence and observable-consequence requirements, the F-XXX register format, and telemetry are " +
            "inherited unchanged.",
        )
      } else {
        appendLine(
          "Assign each routed rubric above to its own specialist worker over that rubric's owned paths " +
            "and merge the returned registers. The severity vocabulary, the finding admission gate, the " +
            "evidence and observable-consequence requirements, the F-XXX register format, and telemetry " +
            "are the same as every other mode.",
        )
      }
      appendLine(
        "Return only '[F-XXX] Severity | Confidence | specialist=<exact resolved rubric identity> | " +
          "commits=<sha>[,<sha>] | path=<JSON string> | line=<positive integer> | description' lines. " +
          "The commits= segment is optional for a finding confined to a single assigned commit and " +
          "required whenever a finding relates code from more than one assigned commit; list the " +
          "involved commit shas in the bundle's commit order.",
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
      appendLine(
        "### Commit ${structuredString(entry.commitSha)} (order=${entry.orderIndex}, " +
          "path=${structuredString(entry.hunk.path)})",
      )
      appendLine("Subject: ${structuredString(entry.subject.replace("\r\n", "\n"))}")
      appendLine(entry.hunk.content)
    }
  }

  private fun governedLaunchFor(request: ReviewSpecialistLaunchRequest): GovernedReviewLaunch =
    GovernedReviewLaunch(
      assignment = request.assignment,
      packet = request.packet,
      specialistContract = request.specialistContract,
      rubric = request.rubrics.first().body,
      brokerId = request.brokerId,
      budget = request.budget,
    )

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
      budgetDimension = incomplete.first().budgetDimension,
    )
  }

  private fun attributeInlineFindings(
    stdout: String,
    selected: List<ReviewSpecialistLaunchRequest>,
  ): List<ParallelReviewRawFinding> = ParallelReviewFindingParser.parse(stdout).map { finding ->
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
      facts.stderr.trim().lineSequence().firstOrNull { it.isNotBlank() }?.let { line ->
        append(" — ${line.take(STDERR_EXCERPT_MAX_LENGTH)}")
      }
    }
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
    const val TIMEOUT_BUFFER_SECONDS = 30L
    const val SECONDS_PER_MINUTE = 60L
    const val STDERR_EXCERPT_MAX_LENGTH = 120
    const val MAX_SUPPLIED_DIFF_BYTES = 1_000_000L
    const val FIRST_SOURCE_LINE = 1
    const val HEAD_REVISION = "HEAD"
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
  // A lane's own `findings` already carries the right value, so the `success` check guards only the
  // raw-output fallback: re-parsing a failed run's output would surface truncated or error text as
  // findings.
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
    bundleCompositionDigest = bundleCompositionDigest,
    segmentAccounting = segmentAccounting,
    unreviewedSegmentIds = unreviewedSegmentIds,
  )
  val roots = listOf(outcomes.lane1, outcomes.lane2).mapIndexed { index, outcome ->
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

private fun laneTerminalOutcome(outcome: ParallelReviewLaneOutcome): String = when {
  outcome.reviewDisposition == ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
  outcome.success -> "completed"
  else -> "partial_failure"
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
  bundleState: ReviewLaneCompletionState,
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
  reviewDisposition = bundleState.disposition,
  bundleCompositionDigest = bundleState.bundleCompositionDigest,
  segmentAccounting = bundleState.segments,
  unreviewedSegmentIds = bundleState.unreviewedSegmentIds,
  budgetDimension = bundleState.budgetDimension,
)

private fun inlineTerminalStatus(
  facts: AgentRunLaunchFacts,
  disposition: ReviewLaneReviewDisposition,
): String = when {
  disposition == ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
  facts.timedOut -> "timeout"
  facts.interrupted -> "interrupted"
  facts.spawnFailed -> "spawn_failure"
  facts.exitStatus != 0 -> "process_failure"
  else -> "completed"
}

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
