package skillbill.application.review

import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBroker
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
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewContextBudgetPolicy
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

/**
 * A pure observer over the production [FileSystemReviewEvidenceBroker]. It records what the
 * delegated worker asked for and what the production broker refused; every policy decision, byte
 * count, and budget termination is the production broker's, never this class's.
 */
class ObservingReviewEvidenceBroker(
  private val recorder: ReviewRecorder,
  private val delegate: ReviewEvidenceBroker,
) : ReviewEvidenceBroker {
  override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult {
    recorder.evidenceBatches += request
    return delegate.readBatch(request).also { result ->
      recorder.refusedOperations += result.results.mapNotNull { it.forbidden }
    }
  }

  override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult =
    delegate.recordToolCall(call).also { result -> result.forbidden?.let { recorder.refusedOperations += it } }

  override fun recordModelTurn(): ReviewBudgetOutcome? = delegate.recordModelTurn()

  override fun validateLaneResult(result: String): ReviewBudgetOutcome? = delegate.validateLaneResult(result)

  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? = delegate.observeLaneResultChunk(chunk)

  override fun hasObservedLaneResult(): Boolean = delegate.hasObservedLaneResult()

  override fun evaluateProviderUsage(usage: ProviderTokenUsage, enforceable: Boolean): ReviewBudgetOutcome? =
    delegate.evaluateProviderUsage(usage, enforceable)

  override fun accounting(): ReviewLaneAccounting = delegate.accounting()

  override fun terminalOutcome(): ReviewBudgetOutcome? = delegate.terminalOutcome()
}

/**
 * Writes the files a lane is allowed to read into the review repository so the production broker
 * measures real file bytes. Repeated bindings over one repository root rewrite identical content.
 */
private fun materializeLaneEvidence(binding: ReviewEvidenceBrokerBinding, body: (String) -> String) {
  val paths = binding.assignment.assignedPaths + binding.assignment.expansions.map { it.requestedPath }
  paths.distinct().forEach { path ->
    val target = binding.repoRoot.resolve(path)
    target.parent?.let(Files::createDirectories)
    Files.writeString(target, body(path))
  }
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

data class ReviewHarnessConfig(
  val manifests: List<PlatformManifest>,
  val diff: String,
  val response: (NativeReviewWorkerRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  val preflight: (ReviewNativeAgentPreflightRequest) -> Unit = {},
  val evidenceBody: (String) -> String = { "// brokered body for $it" },
  val rubricBody: (String) -> String = { "governed rubric body for $it" },
)

fun reviewHarness(config: ReviewHarnessConfig, recorder: ReviewRecorder): ParallelCodeReviewRunner =
  ParallelCodeReviewRunner(
    delegatedReviewExecutionBroker = DelegatedReviewExecutionBroker(
      DelegatedReviewLaunchBroker(
        evidenceBrokerFactory = ReviewEvidenceBrokerFactory { binding ->
          recorder.brokerBindings += binding
          materializeLaneEvidence(binding, config.evidenceBody)
          ObservingReviewEvidenceBroker(recorder, FileSystemReviewEvidenceBroker(binding))
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
    reviewRubricResolver = recordingRubricResolver(recorder, config.rubricBody),
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
    // The production broker only admits calls addressed to the assignment it was bound to, so the
    // lane identity comes from the broker rather than from the worker's logical name.
    val lane = request.broker.accounting().lane
    response.childOperations.forEach { operation ->
      request.operations.tool(
        ReviewToolCall(
          lane = lane,
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

private fun recordingRubricResolver(recorder: ReviewRecorder, rubricBody: (String) -> String) =
  object : ReviewRubricResolver {
    override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric {
      recorder.rubricResolutions += manifest?.slug ?: "generic"
      return ResolvedReviewRubric("parallel-code-review", rubricBody("parallel-code-review"))
    }

    override fun resolve(
      manifest: PlatformManifest?,
      evidence: List<skillbill.ports.review.model.ReviewOwnedFileEvidence>,
      specialistSkillName: String,
    ): ResolvedReviewRubric {
      recorder.rubricResolutions += specialistSkillName
      return ResolvedReviewRubric(
        rubricId = specialistSkillName,
        body = rubricBody(specialistSkillName),
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
  reviewRunId: String? = null,
) = ParallelCodeReviewRequest(
  agent1Id = agent1Id,
  agent2Id = agent2Id,
  scope = ParallelReviewScope.BRANCH,
  repoRoot = repoRoot,
  timeout = timeout,
  reviewRunId = reviewRunId,
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
