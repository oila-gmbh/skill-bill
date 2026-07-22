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
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.review.model.ReviewOwnedFileEvidence
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
import skillbill.review.context.model.ReviewAutoEligibility
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.TokenOwnership
import skillbill.review.context.model.structuredString
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.plan.ReviewContentMatcher
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Inject
@Suppress("LongParameterList", "TooManyFunctions")
class ParallelCodeReviewRunner(
  private val delegatedReviewExecutionBroker: DelegatedReviewExecutionBroker,
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val diffResolver: DiffResolverPort,
  private val parallelLaneRunner: ParallelReviewLaneRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewRubricResolver: ReviewRubricResolver,
  private val nativeAgentPreflight: ReviewNativeAgentPreflightPort,
  private val database: DatabaseSessionFactory,
) {
  fun run(request: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    val agent1 = resolveAgent(request.agent1Id, "--agent1")
    val agent2 = resolveAgent(request.agent2Id, "--agent2")
    if (agent1.id == agent2.id) {
      throw UsageValidationException(
        "agent1 and agent2 must be different agents; both resolved to '${agent1.id}'.",
      )
    }

    val diffText = resolveDiff(request)
    val evidence = ReviewDiffEvidence.parse(diffText)
    val detection = detectStack(evidence, request.repoRoot)
    val budget = repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(request.repoRoot))
      .config.reviewContextBudget
    val resolvedMode = resolvedMode(request, diffText, detection.routed, budget.maxLaneLaunchBytes)
    val launchRequests = prepare(
      request,
      diffText,
      evidence,
      detection.routed,
      detection.manifests,
      listOf(agent1.id, agent2.id),
      budget,
    )
    val providerNativeWorkers = launchRequests
      .filter { it.workerKind == skillbill.application.review.model.ReviewWorkerKind.PROVIDER_NATIVE }
      .map { requireNotNull(it.logicalWorkerName) }
      .distinct()
    if (resolvedMode == ResolvedReviewExecutionMode.DELEGATED && providerNativeWorkers.isNotEmpty()) {
      nativeAgentPreflight.verify(
        ReviewNativeAgentPreflightRequest(
          repoRoot = request.repoRoot,
          agentIds = listOf(agent1.id, agent2.id),
          logicalNames = providerNativeWorkers,
        ),
      )
    }
    delegatedReviewExecutionBroker.preflight(launchRequests)
    val prepared = launchRequests.groupBy { it.agentId }
    val outcomes = runLanes(
      request,
      detection.routed,
      resolvedMode,
      prepared,
      agent1.id,
      agent2.id,
    )
    return parallelResult(agent1.id, agent2.id, outcomes).also { result ->
      result.accountingSummary?.let { summary ->
        database.transaction { unitOfWork ->
          unitOfWork.reviews.saveAccounting(
            ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary.toBoundedPayload()),
          )
        }
      }
    }
  }

  private fun resolvedMode(
    request: ParallelCodeReviewRequest,
    diffText: String,
    manifests: List<PlatformManifest>,
    maxLaneLaunchBytes: Long,
  ) = ReviewExecutionModePolicy.resolve(
    request.codeReviewMode,
    ReviewAutoEligibility(
      oversized = diffText.toByteArray().size > maxLaneLaunchBytes,
      highRisk = HIGH_RISK_SIGNAL.containsMatchIn(diffText),
      layeredStack = manifests.any { it.codeReviewComposition != null },
    ),
  )

  private fun runLanes(
    request: ParallelCodeReviewRequest,
    routedManifests: List<PlatformManifest>,
    resolvedMode: ResolvedReviewExecutionMode,
    prepared: Map<String, List<DelegatedReviewLaunchRequest>>,
    agent1Id: String,
    agent2Id: String,
  ): skillbill.ports.review.model.ParallelReviewLaneRunResult {
    val manifest = routedManifests.firstOrNull()
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

  @Suppress("LongMethod")
  private fun prepare(
    request: ParallelCodeReviewRequest,
    diffText: String,
    evidence: ReviewDiffEvidence,
    routedManifests: List<PlatformManifest>,
    manifests: List<PlatformManifest>,
    agentIds: List<String>,
    budget: skillbill.review.context.model.ReviewContextBudgetPolicy,
  ): List<DelegatedReviewLaunchRequest> {
    val plannedRubrics = resolvePlannedRubrics(evidence, routedManifests, manifests)
    return ParallelReviewPreparationCompiler.compile(
      input = ParallelReviewPreparationInput(
        diff = diffText,
        evidence = evidence,
        stack = routedManifests.joinToString("+") { it.slug }.ifBlank { null },
        agents = agentIds,
        repoRoot = request.repoRoot,
        routedPacks = routedManifests.map { it.slug },
        lanes = plannedRubrics,
      ),
      budget = budget,
      envelopeValidator = reviewContextEnvelopeValidator,
    )
  }

  @Suppress("LongMethod")
  private fun resolvePlannedRubrics(
    evidence: ReviewDiffEvidence,
    routedManifests: List<PlatformManifest>,
    manifests: List<PlatformManifest>,
  ): List<PlannedReviewRubric> = if (routedManifests.isEmpty()) {
    val rubric = reviewRubricResolver.resolve(null)
    listOf(
      PlannedReviewRubric(
        ReviewLaunchLane(
          rubric.rubricId,
          "generic",
          rubric.area ?: "generic",
          0,
          listOf("generic"),
          true,
          emptyList(),
          0,
          "generic fallback",
          ownedPaths = evidence.hunks.map { it.path }.distinct().sorted(),
          changedHunkIds = evidence.hunks.map { it.hunkId },
        ),
        ReviewRubricProjection(rubric.rubricId, rubric.body, rubric.area),
        workerKind = skillbill.application.review.model.ReviewWorkerKind.GENERIC,
      ),
    )
  } else {
    val selectedAreas = manifests.flatMap { it.declaredCodeReviewAreas }.toSet()
    val flattened = routedManifests.flatMap { root ->
      ReviewLaunchPlanPolicy.flatten(root.slug, manifests, selectedAreas).lanes.also { lanes ->
        require(lanes.isNotEmpty()) {
          "Routed pack '${root.slug}' resolved no declared flattened specialist worker."
        }
      }
    }
    flattened
      .map { lane ->
        val ownedPaths = if (lane.required) evidence.hunks.map { it.path } else laneOwnedPaths(lane, evidence)
        lane.copy(
          ownedPaths = ownedPaths.distinct().sorted(),
          changedHunkIds = evidence.hunks.filter { it.path in ownedPaths }.map { it.hunkId },
        )
      }
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
      scaffoldCatalogService.discoverPlatformManifests(packsRoot)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      val displayPath = runCatching { repoRoot.relativize(packsRoot) }.getOrDefault(packsRoot)
      throw StackDetectionException(
        "Platform pack discovery failed for $displayPath: ${e.message ?: e.javaClass.simpleName}. " +
          "Repair the platform pack before running parallel review.",
        e,
      )
    }
    if (manifests.isEmpty()) return StackDetection(emptyList(), emptyList())

    val changedFiles = evidence.files.filterNot { RoutingSignalPathMatcher.isIgnored(it.path) }

    val signalOwners = manifests.flatMap { manifest ->
      manifest.routingSignals.path.distinct().map { it to manifest.slug }
    }.groupBy({ it.first }, { it.second })
    val routedSlugs = linkedSetOf<String>()
    changedFiles.forEach { changed ->
      val pathScores = manifests.associateWith { manifest ->
        val pathScore = manifest.routingSignals.path.distinct().sumOf { signal ->
          if (!RoutingSignalPathMatcher.matches(changed.path, signal)) {
            0
          } else if (signalOwners.getValue(signal).size == 1) {
            UNIQUE_PATH_SIGNAL_SCORE
          } else {
            1
          }
        }
        val contentScore = manifest.routingSignals.content.distinct().count { signal ->
          changed.changedContent.contains(signal, ignoreCase = true)
        } * CONTENT_SIGNAL_SCORE
        pathScore + contentScore
      }.filterValues { it > 0 }
      val best = pathScores.values.maxOrNull() ?: return@forEach
      val winners = pathScores.filterValues { it == best }.keys.toMutableList()
      if (winners.size > 1) {
        // With only shared signals, prefer a baseline pack over a composed root. A root wins
        // naturally as soon as one of its manifest-owned KMP/Android/etc. signals matches.
        val composedRoots = winners.filter { it.codeReviewComposition != null }.toSet()
        val baselineSlugs = composedRoots.flatMap { root ->
          root.codeReviewComposition!!.baselineLayers.map { it.platform }
        }.toSet()
        if (baselineSlugs.any { slug -> winners.any { it.slug == slug } }) {
          winners.removeAll(composedRoots)
        }
      }
      winners.forEach { routedSlugs += it.slug }
    }
    val routed = manifests.filter { it.slug in routedSlugs }
    return StackDetection(routed, manifests)
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
    require(launchRequests.isNotEmpty()) { "Delegated review selected no specialist launches for '$agentId'." }
    val timeout = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes
    val started = TimeSource.Monotonic.markNow()
    val outcomes = mutableListOf<ParallelReviewLaneOutcome>()
    for (launchRequest in launchRequests) {
      val remaining = timeout - started.elapsedNow()
      if (remaining <= 0.seconds) {
        outcomes += ParallelReviewLaneOutcome(
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
        continue
      }
      outcomes += launchSpecialist(launchRequest, request, modelOverride, remaining)
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

  @Suppress("LongMethod")
  private fun launchSpecialist(
    launchRequest: DelegatedReviewLaunchRequest,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
    timeout: kotlin.time.Duration = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
  ): ParallelReviewLaneOutcome {
    val execution = delegatedReviewExecutionBroker.execute(
      DelegatedReviewExecutionRequest(
        launchRequest = launchRequest,
        repoRoot = request.repoRoot,
        timeout = timeout,
        modelOverride = modelOverride,
      ),
    )
    return when (execution) {
      is DelegatedReviewExecutionOutcome.Terminated -> ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason = describeBudgetOutcome(execution.budgetOutcome),
        budgetOutcome = execution.budgetOutcome,
        accounting = execution.accounting,
      )
      is DelegatedReviewExecutionOutcome.Completed -> {
        val worker = execution.worker
        worker.budgetOutcome?.takeIf { worker.facts == null }?.let { budgetOutcome ->
          return ParallelReviewLaneOutcome(
            success = false,
            rawOutput = "",
            failureReason = describeBudgetOutcome(budgetOutcome),
            budgetOutcome = budgetOutcome,
            accounting = worker.accounting,
          )
        }
        worker.forbiddenOperation?.let { forbidden ->
          return ParallelReviewLaneOutcome(
            success = false,
            rawOutput = "",
            failureReason = "forbidden review operation: ${forbidden.reason}",
            accounting = worker.accounting,
          )
        }
        val outcome = worker.facts
        if (outcome == null) {
          return ParallelReviewLaneOutcome(
            success = false,
            rawOutput = "",
            failureReason = "unsupported agent: ${worker.unsupportedReason}",
            accounting = worker.accounting,
          )
        }
        val usage = providerTokenUsage(outcome)
        val processFailure = laneFailureReason(outcome)
        val budgetOutcome = worker.budgetOutcome
        val reason = budgetOutcome?.takeIf { it.enforceable }?.let(::describeBudgetOutcome) ?: processFailure
        ParallelReviewLaneOutcome(
          success = reason == null,
          rawOutput = outcome.stdout,
          failureReason = reason,
          tokenUsage = usage,
          budgetOutcome = budgetOutcome,
          accounting = worker.accounting,
          findings = if (reason == null) {
            ParallelReviewFindingParser.parse(outcome.stdout).map { finding ->
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
          } else {
            emptyList()
          },
        )
      }
    }
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
      appendLine("Run one complete bill-code-review mode:inline parent review.")
      appendLine("Resolved execution mode: inline")
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
      appendLine("Apply every signal-relevant routed rubric in this agent context and do not launch specialists.")
      appendLine(
        "Return only '[F-XXX] Severity | Confidence | specialist=<exact resolved rubric identity> | " +
          "path=<JSON string> | line=<positive integer> | description' lines.",
      )
      appendLine()
      append(launchRequests.first().packet.changedHunks.joinToString("\n") { it.content })
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
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason = "unsupported agent: ${outcome.reason}",
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
          findings = findings,
        )
      }
    }
  }

  private fun laneOwnedPaths(lane: ReviewLaunchLane, evidence: ReviewDiffEvidence): List<String> {
    return evidence.files.filter { file ->
      lane.pathSignals.any { RoutingSignalPathMatcher.matches(file.path, it) } ||
        lane.contentSignals.any { ReviewContentMatcher.contains(file.changedContent, it) }
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
    else -> null
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MINUTES = 30L
    const val TIMEOUT_BUFFER_SECONDS = 30L
    const val SECONDS_PER_MINUTE = 60L
    const val STDERR_EXCERPT_MAX_LENGTH = 120
    const val MAX_SUPPLIED_DIFF_BYTES = 1_000_000L
    const val UNIQUE_PATH_SIGNAL_SCORE = 10
    const val CONTENT_SIGNAL_SCORE = 20
    val HIGH_RISK_SIGNAL = Regex(
      "(?i)(auth|authorization|secret|token|migration|transaction|process|subprocess|network|ssrf|unsafe)",
    )
  }

  private data class StackDetection(
    val routed: List<PlatformManifest>,
    val manifests: List<PlatformManifest>,
  )
}

private fun parallelResult(
  agent1Id: String,
  agent2Id: String,
  outcomes: skillbill.ports.review.model.ParallelReviewLaneRunResult,
): ParallelCodeReviewResult {
  val lane1Result = ParallelReviewLaneResult(
    agentId = agent1Id,
    findings = if (outcomes.lane1.success) {
      outcomes.lane1.findings.ifEmpty { ParallelReviewFindingParser.parse(outcomes.lane1.rawOutput) }
    } else {
      emptyList()
    },
  )
  val lane2Result = ParallelReviewLaneResult(
    agentId = agent2Id,
    findings = if (outcomes.lane2.success) {
      outcomes.lane2.findings.ifEmpty { ParallelReviewFindingParser.parse(outcomes.lane2.rawOutput) }
    } else {
      emptyList()
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
