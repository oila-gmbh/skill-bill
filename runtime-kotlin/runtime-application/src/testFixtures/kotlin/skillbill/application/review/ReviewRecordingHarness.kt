package skillbill.application.review

import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.ReviewPrelaunchExpansion
import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.fs.ClasspathReviewSpecialistContractProvider
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.JdkParallelReviewLaneRunner
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunTokenOwnership
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.persistence.model.ReviewIntegrationPassRecord
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.workflow.model.CodeReviewExecutionMode
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.time.Duration

/**
 * A recording harness around the production [ParallelCodeReviewRunner]. It records what the
 * production composition, inline lane launch, and accounting seams actually did; it never restates
 * a routing, budget, or accounting policy of its own.
 */
class ReviewRecorder {
  val parentLaunches: MutableList<GoalRunnerSubtaskLaunchRequest> =
    Collections.synchronizedList(mutableListOf())
  val rubricResolutions: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val diffCommands: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
  val savedAccounting: MutableList<ReviewAccountingRecord> =
    Collections.synchronizedList(mutableListOf())

  /**
   * Durable review state the harness carries across runs, so a second run against the same recorder
   * is a real resume: lane rows and the integration boundary are stored and read back separately,
   * exactly as the two distinct durable boundaries they are.
   */
  val durableLanes: MutableList<ReviewRunLane> = Collections.synchronizedList(mutableListOf())

  @Volatile var durableIntegrationPass: ReviewIntegrationPassRecord? = null

  val durableFindingVerdicts: MutableList<ReviewFindingVerdict> =
    Collections.synchronizedList(mutableListOf())
  val durableStageBoundaries: MutableList<ReviewStageBoundary> =
    Collections.synchronizedList(mutableListOf())

  @Volatile var durablePassClaims: ReviewPassClaimSnapshot? = null

  val durableFindingLanes: MutableMap<String, String> =
    Collections.synchronizedMap(mutableMapOf())

  @Volatile var durableSpecProjection: ReviewSpecProjectionReference? = null

  val stageDegradations: MutableList<skillbill.review.model.ReviewStageDegradationMeasurement> =
    Collections.synchronizedList(mutableListOf())

  /** The prompts the inline parent lanes were actually launched with. */
  val parentPrompts: List<String>
    get() = parentLaunches.mapNotNull { it.skillRunRequest.promptOverride }
}

/** What a recorded specialist run reports back, keyed by logical worker name. */
data class RecordedWorkerResponse(
  val stdout: String = "NO_FINDINGS",
  val exitStatus: Int? = 0,
  val timedOut: Boolean = false,
  val usage: ProviderTokenUsage? = null,
  val usageEnforceable: Boolean = false,
  val processStarted: Boolean = true,
  val mcpStartupObserved: Boolean = false,
  val spawnFailed: Boolean = false,
  val interrupted: Boolean = false,
  val liveness: AgentRunLivenessSnapshot? = null,
)

/** One commit of a harness commit-range fixture, in sequence order. */
data class RecordedCommit(val sha: String, val subject: String, val diff: String)

data class ReviewHarnessConfig(
  val manifests: List<PlatformManifest>,
  val diff: String,
  val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  val rubricBody: (String) -> String = { "governed rubric body for $it" },
  val response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse(stdout = "") },
  /**
   * Commit range the fixture enumerates. Empty keeps the default single synthetic unit; the last
   * entry's sha must be the request's head revision, exactly as a real range resolves.
   */
  val commits: List<RecordedCommit> = emptyList(),
)

fun reviewHarness(config: ReviewHarnessConfig, recorder: ReviewRecorder): ParallelCodeReviewRunner =
  ParallelCodeReviewRunner(
    parentReviewLauncher = GoalRunnerSubtaskLauncher { request ->
      recorder.parentLaunches += request
      val response = config.response(request)
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = if (response.timedOut || response.spawnFailed || response.interrupted) {
          null
        } else {
          response.exitStatus
        },
        stdout = response.stdout,
        stderr = "",
        timedOut = response.timedOut,
        interrupted = response.interrupted,
        spawnFailed = response.spawnFailed,
        liveness = response.liveness,
        processStarted = response.processStarted && !response.spawnFailed,
        mcpStartupObserved = response.mcpStartupObserved,
        inputTokens = response.usage?.inputTokens,
        cachedInputTokens = response.usage?.cachedInputTokens,
        outputTokens = response.usage?.outputTokens,
        reasoningTokens = response.usage?.reasoningTokens,
        totalTokens = response.usage?.totalTokens,
        tokenOwnership = AgentRunTokenOwnership.DIRECT,
        providerUsageEnforceable = response.usageEnforceable,
      ) as AgentRunLaunchOutcome
    },
    installedPackCatalog = InstalledPlatformPackCatalogPort { config.manifests },
    diffResolver = object : DiffResolverPort {
      override fun runProcess(args: List<String>, workDir: Path): String? {
        recorder.diffCommands += args
        return when (args.getOrNull(1)) {
          // Declared revisions canonicalize to themselves. With no commit fixture the range
          // enumerates nothing, so the harness reviews one synthetic unit over config.diff rather
          // than a fabricated chain; with one it replays exactly that chain.
          "rev-parse" -> args.last().removeSuffix("^{commit}")
          "rev-list" -> config.commits.joinToString("\n") { it.sha }
          "show" -> config.commits.single { it.sha == args.last() }.let { commit ->
            "${parentOf(config.commits, commit)}\n${commit.subject}"
          }
          // A per-commit read names (parent, sha); the aggregate read names (base, head). Matching
          // both ends keeps the two apart when head is also the last commit of the range.
          else -> config.commits.firstOrNull {
            it.sha == args.getOrNull(3) && parentOf(config.commits, it) == args.getOrNull(2)
          }?.diff ?: config.diff
        }
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
    reviewSpecialistContractProvider = ClasspathReviewSpecialistContractProvider(),
    database = recordingDatabase(recorder),
    specIntentProjectionResolver = SpecIntentProjectionResolver(
      FileSystemDecompositionManifestFileStore(),
      DecompositionManifestValidatorAdapter(),
      SpecIntentProjectionExtractor(
        object : ReviewContextEnvelopeValidator {
          override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
        },
        FileSystemDecompositionManifestFileStore(),
      ),
    ),
    reviewEvidenceBrokerFactory = skillbill.infrastructure.fs.FileSystemReviewEvidenceBrokerFactory(),
  )

/** The base revision every harness request declares; the root commit of a fixture range parents onto it. */
const val HARNESS_BASE_REVISION: String = "base-revision"

/** The head revision every harness request declares; a fixture range must end on it. */
const val HARNESS_HEAD_REVISION: String = "head-revision"

private fun parentOf(commits: List<RecordedCommit>, commit: RecordedCommit): String =
  commits.getOrNull(commits.indexOf(commit) - 1)?.sha ?: HARNESS_BASE_REVISION

/** Runs both lanes to completion in a fixed order so recorded evidence stays deterministic. */
private class SequentialLaneRunner : ParallelReviewLaneRunner {
  override fun <T> runWave(tasks: List<() -> T>): List<Result<T>> = JdkParallelReviewLaneRunner().runWave(tasks)

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
      "recordFindingLaneAttribution" -> {
        @Suppress("UNCHECKED_CAST")
        recorder.durableFindingLanes.putAll(args[1] as Map<String, String>)
      }
      "replaceReviewRunLanes" -> {
        @Suppress("UNCHECKED_CAST")
        val lanes = args[1] as List<ReviewRunLane>
        recorder.durableLanes.clear()
        recorder.durableLanes.addAll(lanes)
      }
      "fetchReviewRunLanes" -> recorder.durableLanes.toList()
      "recordIntegrationPass" -> {
        recorder.durableIntegrationPass = args[1] as ReviewIntegrationPassRecord
      }
      "fetchIntegrationPass" -> recorder.durableIntegrationPass
      "recordFindingVerdicts" -> {
        @Suppress("UNCHECKED_CAST")
        val verdicts = args[1] as List<ReviewFindingVerdict>
        verdicts.forEach { incoming ->
          recorder.durableFindingVerdicts.removeAll {
            it.findingRef == incoming.findingRef && it.stage == incoming.stage
          }
          recorder.durableFindingVerdicts += incoming
        }
      }
      "fetchFindingVerdicts" -> recorder.durableFindingVerdicts.toList()
      "recordReviewPassClaims" -> {
        @Suppress("UNCHECKED_CAST")
        val incoming = args[1] as List<ParallelReviewMergedFinding>
        val existing = recorder.durablePassClaims?.findings
        if (incoming.isEmpty() && !existing.isNullOrEmpty()) {
          Unit
        } else {
          recorder.durablePassClaims = ReviewPassClaimSnapshot(incoming)
        }
      }
      "fetchReviewPassClaims" -> recorder.durablePassClaims
      "recordStageBoundary" -> {
        val boundary = args[1] as ReviewStageBoundary
        recorder.durableStageBoundaries.removeAll { it.stage == boundary.stage }
        recorder.durableStageBoundaries += boundary
      }
      "fetchStageBoundaries" -> recorder.durableStageBoundaries.toList()
      "recordSpecProjectionReference" -> {
        recorder.durableSpecProjection = args[1] as ReviewSpecProjectionReference
      }
      "fetchSpecProjectionReference" -> recorder.durableSpecProjection
      else -> error("Unexpected review repository call: ${method.name}")
    }
  } as ReviewRepository
  val unitOfWork = Proxy.newProxyInstance(
    UnitOfWork::class.java.classLoader,
    arrayOf(UnitOfWork::class.java),
  ) { _, method, _ ->
    when (method.name) {
      "getReviews" -> reviews
      "getLifecycleTelemetry" -> recordingLifecycleTelemetry(recorder)
      "getDbPath" -> Path.of("/tmp/recording-review.db")
      else -> error("Unexpected unit-of-work call: ${method.name}")
    }
  } as UnitOfWork
  return object : DatabaseSessionFactory {
    override fun resolveDbPath(dbOverride: String?) = unitOfWork.dbPath
    override fun databaseExists(dbOverride: String?) = true
    override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)
    override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

    override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)
  }
}

private fun recordingLifecycleTelemetry(recorder: ReviewRecorder): LifecycleTelemetryRepository =
  object : LifecycleTelemetryRepository {
    override fun reviewStageDegradation(record: skillbill.review.model.ReviewStageDegradationMeasurement) {
      recorder.stageDegradations += record
    }

    override fun featureTaskRuntimeStarted(
      record: skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord,
      level: String,
    ) = Unit

    override fun featureTaskRuntimeFinished(
      record: skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord,
      level: String,
    ) = Unit

    override fun qualityCheckStarted(record: skillbill.telemetry.model.QualityCheckStartedRecord, level: String) = Unit

    override fun qualityCheckFinished(record: skillbill.telemetry.model.QualityCheckFinishedRecord, level: String) =
      Unit

    override fun featureVerifyStarted(record: skillbill.telemetry.model.FeatureVerifyStartedRecord, level: String) =
      Unit

    override fun featureVerifyFinished(record: skillbill.telemetry.model.FeatureVerifyFinishedRecord, level: String) =
      Unit

    override fun prDescriptionGenerated(record: skillbill.telemetry.model.PrDescriptionGeneratedRecord, level: String) =
      Unit

    override fun goalStarted(record: skillbill.telemetry.model.GoalStartedRecord, level: String) = Unit

    override fun goalSubtaskFinished(record: skillbill.telemetry.model.GoalSubtaskFinishedRecord, level: String) = Unit

    override fun goalFinished(record: skillbill.telemetry.model.GoalFinishedRecord, level: String) = Unit

    override fun goalIssueFinished(record: skillbill.telemetry.model.GoalIssueFinishedRecord, level: String) = Unit
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
  agent2Id: String? = "claude",
  timeout: Duration? = null,
  reviewRunId: String? = null,
  prelaunchExpansions: List<ReviewPrelaunchExpansion> = emptyList(),
  codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.INLINE,
  scope: ParallelReviewScope = ParallelReviewScope.BRANCH,
) = ParallelCodeReviewRequest(
  agent1Id = agent1Id,
  agent2Id = agent2Id,
  scope = scope,
  repoRoot = repoRoot,
  timeout = timeout,
  codeReviewMode = codeReviewMode,
  reviewRunId = reviewRunId,
  baseRevision = HARNESS_BASE_REVISION,
  headRevision = HARNESS_HEAD_REVISION,
  prelaunchExpansions = prelaunchExpansions,
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
  contractVersion = "1.3",
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

/**
 * Pack whose specialist path signals drive sparse commit/lane routing in harness fixtures: a required
 * baseline plus optional areas keyed by the given path prefixes.
 */
fun sparseReviewPack(
  slug: String,
  requiredArea: String,
  pathAreas: Map<String, List<String>>,
  routingSignals: List<String> = listOf("*.kt"),
): PlatformManifest {
  val areas = listOf(requiredArea) + pathAreas.keys.toList()
  return reviewPack(slug, areas, routingSignals = routingSignals).copy(
    laneConditions = buildMap {
      put(requiredArea, ReviewLaneCondition(required = true))
      pathAreas.forEach { (area, paths) -> put(area, ReviewLaneCondition(path = paths)) }
    },
  )
}

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
