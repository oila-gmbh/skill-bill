package skillbill.application.review

import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.config.model.RepoLocalConfig
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTokenOwnership
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.review.NativeReviewWorkerLauncher
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.NativeReviewWorkerRequest
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.ReviewOperationPolicy
import skillbill.review.context.model.ReviewRequestedOperation
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * A recording harness around the production [ParallelCodeReviewRunner]. It records what the
 * production composition, broker, preflight, and accounting seams actually did; it never restates a
 * routing, budget, or accounting policy of its own.
 */
class ReviewRecorder {
  val preflightRequests: MutableList<ReviewNativeAgentPreflightRequest> = mutableListOf()
  val nativeLaunches: MutableList<NativeReviewWorkerRequest> = mutableListOf()
  val parentLaunches: MutableList<GoalRunnerSubtaskLaunchRequest> = mutableListOf()
  val evidenceBatches: MutableList<ReviewEvidenceBatchRequest> = mutableListOf()
  val brokerBindings: MutableList<ReviewEvidenceBrokerBinding> = mutableListOf()
  val rubricResolutions: MutableList<String> = mutableListOf()
  val diffCommands: MutableList<List<String>> = mutableListOf()
  val savedAccounting: MutableList<ReviewAccountingRecord> = mutableListOf()
  val refusedOperations: MutableList<ForbiddenReviewOperation> = mutableListOf()

  val launchedSpecialists: List<String> get() = nativeLaunches.mapNotNull { it.logicalWorkerName }
}

/** Serves brokered evidence and refuses everything the governed operation policy rejects. */
class RecordingReviewEvidenceBroker(
  private val recorder: ReviewRecorder,
  private val binding: ReviewEvidenceBrokerBinding,
  private val evidenceBody: (String) -> String = { "// brokered body for $it" },
) : ReviewEvidenceBroker {
  private val identity = ReviewLaneIdentity.of(binding.assignment)
  private val policy = ReviewOperationPolicy(binding.assignment, binding.laneRubricId, binding.namedDependencies)
  private var evidenceBytes = 0L
  private var resultBytes = 0L
  private var toolCalls = 0
  private var modelTurns = 0
  private var terminal: ReviewBudgetOutcome? = null

  override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult {
    recorder.evidenceBatches += request
    terminal?.let { return ReviewEvidenceBatchResult(emptyList(), evidenceBytes, emptyList(), it) }
    val results = request.requests.map { evidenceRequest ->
      val refusal = policy.classify(
        ReviewRequestedOperation(
          ReviewOperationKind.FILE_READ,
          evidenceRequest.path,
          evidenceRequest.reachabilityReason,
        ),
      )
      if (refusal != null) {
        recorder.refusedOperations += refusal
        return@map ReviewEvidenceResult(null, 0, evidenceBytes, 0, forbidden = refusal)
      }
      val body = evidenceBody(evidenceRequest.path)
      evidenceBytes += body.toByteArray().size
      ReviewEvidenceResult(body, body.toByteArray().size.toLong(), evidenceBytes, 0)
    }
    terminal = ReviewBudgetEvaluator.exceededOrNull(
      identity,
      "lane_evidence_bytes",
      binding.budget.maxLaneEvidenceBytes,
      evidenceBytes,
    )
    return ReviewEvidenceBatchResult(results, evidenceBytes, emptyList(), terminal)
  }

  override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult {
    val refusal = policy.classify(ReviewRequestedOperation(call.kind, call.target, searchScopes = call.searchScopes))
    if (refusal != null) {
      recorder.refusedOperations += refusal
      return ReviewToolCallResult(forbidden = refusal)
    }
    toolCalls += 1
    terminal = terminal ?: ReviewBudgetEvaluator.exceededOrNull(
      identity,
      "tool_calls",
      binding.budget.maxSpecialistToolCalls.toLong(),
      toolCalls.toLong(),
    )
    return ReviewToolCallResult(budgetExceeded = terminal)
  }

  override fun recordModelTurn(): ReviewBudgetOutcome? {
    modelTurns += 1
    terminal = terminal ?: ReviewBudgetEvaluator.exceededOrNull(
      identity,
      "model_turns",
      binding.budget.maxSpecialistModelTurns.toLong(),
      modelTurns.toLong(),
    )
    return terminal
  }

  override fun validateLaneResult(result: String): ReviewBudgetOutcome? {
    resultBytes = maxOf(resultBytes, result.toByteArray().size.toLong())
    terminal = terminal ?: ReviewBudgetEvaluator.laneResultOutcome(identity, binding.budget, resultBytes)
    return terminal
  }

  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? {
    resultBytes += chunk.toByteArray().size
    terminal = terminal ?: ReviewBudgetEvaluator.laneResultOutcome(identity, binding.budget, resultBytes)
    return terminal
  }

  override fun evaluateProviderUsage(usage: ProviderTokenUsage, enforceable: Boolean): ReviewBudgetOutcome? {
    val outcome = ReviewBudgetEvaluator.providerUsageOutcome(
      identity,
      binding.budget.providerTokenThresholds,
      usage,
      enforceable,
    )
    terminal = terminal ?: outcome
    return outcome
  }

  override fun accounting(): ReviewLaneAccounting = ReviewLaneAccounting(
    lane = binding.assignment.lane,
    reviewId = binding.assignment.reviewId,
    packetDigest = binding.assignment.packetDigest,
    assignmentDigest = binding.assignment.digest,
    evidenceBytes = evidenceBytes,
    expansions = emptyList(),
    toolCalls = toolCalls,
    modelTurns = modelTurns,
    resultBytes = resultBytes,
    terminalOutcome = terminal,
  )

  override fun terminalOutcome(): ReviewBudgetOutcome? = terminal
}

/** What a recorded specialist run reports back, keyed by logical worker name. */
data class RecordedWorkerResponse(
  val stdout: String = "",
  val exitStatus: Int? = 0,
  val timedOut: Boolean = false,
  val usage: ProviderTokenUsage? = null,
  val usageEnforceable: Boolean = false,
  val childOperations: List<ReviewRequestedOperation> = emptyList(),
  val modelTurns: Int = 1,
)

class ReviewHarnessConfig(
  val manifests: List<PlatformManifest>,
  val diff: String,
  val response: (NativeReviewWorkerRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  val preflight: (ReviewNativeAgentPreflightRequest) -> Unit = {},
  val evidenceBody: (String) -> String = { "// brokered body for $it" },
)

fun reviewHarness(config: ReviewHarnessConfig, recorder: ReviewRecorder): ParallelCodeReviewRunner =
  ParallelCodeReviewRunner(
    delegatedReviewExecutionBroker = DelegatedReviewExecutionBroker(
      DelegatedReviewLaunchBroker(
        evidenceBrokerFactory = ReviewEvidenceBrokerFactory { binding ->
          recorder.brokerBindings += binding
          RecordingReviewEvidenceBroker(recorder, binding, config.evidenceBody)
        },
        isolationResolver = { ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE },
      ),
      DelegatedReviewWorkerLauncher(recordingWorkerLauncher(config, recorder)),
    ),
    parentReviewLauncher = GoalRunnerSubtaskLauncher { request ->
      recorder.parentLaunches += request
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = 0,
        stdout = "",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      ) as AgentRunLaunchOutcome
    },
    scaffoldCatalogService = ScaffoldCatalogService(recordingCatalogGateway(config.manifests)),
    diffResolver = object : DiffResolverPort {
      override fun runProcess(args: List<String>, workDir: Path): String? {
        recorder.diffCommands += args
        return config.diff
      }
    },
    parallelLaneRunner = SequentialLaneRunner(),
    repoLocalConfig = object : RepoLocalConfigPort {
      override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
        ReadRepoLocalConfigResult(RepoLocalConfig.defaults().copy(reviewContextBudget = config.budget))
    },
    reviewContextEnvelopeValidator = object : ReviewContextEnvelopeValidator {
      override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
    },
    reviewRubricResolver = recordingRubricResolver(recorder),
    nativeAgentPreflight = ReviewNativeAgentPreflightPort { request ->
      recorder.preflightRequests += request
      config.preflight(request)
    },
    database = recordingDatabase(recorder),
  )

/** Replays a scripted specialist run through the governed operation protocol before reporting facts. */
private fun recordingWorkerLauncher(config: ReviewHarnessConfig, recorder: ReviewRecorder) =
  NativeReviewWorkerLauncher { request ->
    recorder.nativeLaunches += request
    val response = config.response(request)
    response.childOperations.forEach { operation ->
      request.operations.tool(
        ReviewToolCall(
          lane = request.logicalWorkerName ?: request.issueKey,
          kind = operation.kind,
          target = operation.target,
          searchScopes = operation.searchScopes,
        ),
      )
    }
    repeat(response.modelTurns) { request.operations.modelTurn() }
    AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId),
      exitStatus = if (response.timedOut) null else response.exitStatus,
      stdout = response.stdout,
      stderr = "",
      timedOut = response.timedOut,
      spawnFailed = false,
      inputTokens = response.usage?.inputTokens,
      cachedInputTokens = response.usage?.cachedInputTokens,
      outputTokens = response.usage?.outputTokens,
      reasoningTokens = response.usage?.reasoningTokens,
      totalTokens = response.usage?.totalTokens,
      tokenOwnership = AgentRunTokenOwnership.DIRECT,
      providerUsageEnforceable = response.usageEnforceable,
    )
  }

/** Runs both lanes to completion in a fixed order so recorded evidence stays deterministic. */
private class SequentialLaneRunner : ParallelReviewLaneRunner {
  override fun runTwoLanes(request: ParallelReviewLaneRunRequest): ParallelReviewLaneRunResult =
    ParallelReviewLaneRunResult(runLane(request.lane1), runLane(request.lane2))

  private fun runLane(lane: () -> ParallelReviewLaneOutcome): ParallelReviewLaneOutcome = try {
    lane()
  } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "lane launch threw ${e::class.simpleName}: ${e.message ?: "no detail"}",
    )
  }
}

private fun recordingRubricResolver(recorder: ReviewRecorder) = object : ReviewRubricResolver {
  override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric {
    recorder.rubricResolutions += manifest?.slug ?: "generic"
    return ResolvedReviewRubric("parallel-code-review", "governed generic rubric")
  }

  override fun resolve(
    manifest: PlatformManifest?,
    evidence: List<skillbill.ports.review.model.ReviewOwnedFileEvidence>,
    specialistSkillName: String,
  ): ResolvedReviewRubric {
    recorder.rubricResolutions += specialistSkillName
    return ResolvedReviewRubric(
      rubricId = specialistSkillName,
      body = "governed rubric body for $specialistSkillName",
      area = specialistSkillName.substringAfter("-code-review-", "generic"),
    )
  }
}

private fun recordingDatabase(recorder: ReviewRecorder): DatabaseSessionFactory {
  val reviews = Proxy.newProxyInstance(
    ReviewRepository::class.java.classLoader,
    arrayOf(ReviewRepository::class.java),
  ) { _, method, args ->
    when (method.name) {
      "saveAccounting" -> recorder.savedAccounting.add(args[0] as ReviewAccountingRecord).let { }
      "loadAccounting" -> null
      else -> error("Unexpected review repository call: ${method.name}")
    }
  } as ReviewRepository
  val unitOfWork = Proxy.newProxyInstance(
    UnitOfWork::class.java.classLoader,
    arrayOf(UnitOfWork::class.java),
  ) { _, method, _ ->
    when (method.name) {
      "getReviews" -> reviews
      "getDbPath" -> Path.of("/tmp/recording-review.db")
      else -> error("Unexpected unit-of-work call: ${method.name}")
    }
  } as UnitOfWork
  return object : DatabaseSessionFactory {
    override fun resolveDbPath(dbOverride: String?) = unitOfWork.dbPath
    override fun databaseExists(dbOverride: String?) = true
    override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)
    override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)
  }
}

private fun recordingCatalogGateway(manifests: List<PlatformManifest>): ScaffoldCatalogGateway =
  object : ScaffoldCatalogGateway {
    override fun approvedCodeReviewAreas() = emptySet<String>()
    override fun preShellFamilies() = emptySet<String>()
    override fun shelledFamilies() = emptySet<String>()
    override fun platformPackPresets() = emptyMap<String, String>()
    override fun scaffoldPayloadVersion() = "1.0"
    override fun discoverPilotedPlatformPacks(packsRoot: Path) = emptyList<PilotedPlatformPackProjection>()
    override fun discoverPlatformManifests(packsRoot: Path) = manifests
    override fun discoverBaselineReviewCatalog(packsRoot: Path) =
      BaselineReviewCatalog(packs = emptyList(), compositionEdges = emptyList(), layerSuggestions = emptyList())
  }

fun harnessRequest(
  repoRoot: Path = Files.createTempDirectory("review-e2e"),
  agent1Id: String = "codex",
  agent2Id: String = "claude",
  timeout: Duration? = null,
) = ParallelCodeReviewRequest(
  agent1Id = agent1Id,
  agent2Id = agent2Id,
  scope = ParallelReviewScope.BRANCH,
  repoRoot = repoRoot,
  timeout = timeout,
)

fun reviewPack(
  slug: String,
  areas: List<String>,
  layers: List<CodeReviewBaselineLayer> = emptyList(),
  routingSignals: List<String> = emptyList(),
  contentSignals: List<String> = emptyList(),
) = PlatformManifest(
  slug = slug,
  packRoot = Path.of("platform-packs", slug),
  contractVersion = "1.2",
  routingSignals = RoutingSignals(
    strong = routingSignals,
    tieBreakers = emptyList(),
    path = routingSignals,
    content = contentSignals,
  ),
  declaredCodeReviewAreas = areas,
  declaredFiles = DeclaredFiles(
    baseline = Path.of("platform-packs", slug, "code-review", "bill-$slug-code-review", "content.md"),
    areas = areas.associateWith {
      Path.of("platform-packs", slug, "code-review", "bill-$slug-code-review-$it", "content.md")
    },
  ),
  areaMetadata = emptyMap(),
  laneConditions = areas.associateWith { ReviewLaneCondition(path = listOf("*")) },
  codeReviewComposition = layers.takeIf { it.isNotEmpty() }?.let(::CodeReviewComposition),
)

fun reviewLayer(slug: String, required: Boolean = true) = CodeReviewBaselineLayer(
  platform = slug,
  skill = "bill-$slug-code-review",
  scope = CodeReviewCompositionScope.SameReviewScope,
  required = required,
  mode = CodeReviewCompositionMode.KmpBaseline,
)

fun diffForPaths(vararg paths: String): String =
  diffForChanges(*paths.map { it to "val changed = \"$it\"" }.toTypedArray())

fun diffForChanges(vararg changes: Pair<String, String>): String = changes.joinToString("\n") { (path, added) ->
  """
  diff --git a/$path b/$path
  --- a/$path
  +++ b/$path
  @@ -1,2 +1,3 @@
  +$added
  """.trimIndent()
}
