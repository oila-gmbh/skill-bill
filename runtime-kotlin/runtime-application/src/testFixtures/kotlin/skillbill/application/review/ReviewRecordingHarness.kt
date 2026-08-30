package skillbill.application.review

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewRunnerDeps
import skillbill.application.review.model.ParallelReviewScope
import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.fs.ClasspathReviewSpecialistContractProvider
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBrokerFactory
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewRepository
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.review.stubGovernedReviewEvidenceEndpointBinder
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.workflow.goal.model.CodeReviewExecutionMode
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

  val stageDegradations: MutableList<ReviewStageDegradationMeasurement> =
    Collections.synchronizedList(mutableListOf())

  /** The prompts the inline parent lanes were actually launched with. */
  val parentPrompts: List<String>
    get() = parentLaunches.mapNotNull { it.skillRunRequest.promptOverride }
}

data class RecordedWorkerResponse(
  val stdout: String = "NO_FINDINGS",
  val exitStatus: Int? = 0,
  val timedOut: Boolean = false,
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
  val response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  val evidenceBrokerFactory: ReviewEvidenceBrokerFactory =
    FileSystemReviewEvidenceBrokerFactory(),
  val parentLaunch: ((GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome)? = null,
  /** Set false to model a worker that answered without reading its assigned evidence. */
  val simulateEvidenceReads: Boolean = true,
  val evidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder =
    stubGovernedReviewEvidenceEndpointBinder(Files.createTempDirectory("review-endpoint")),
  /**
   * Commit range the fixture enumerates. Empty keeps the default single synthetic unit; the last
   * entry's sha must be the request's head revision, exactly as a real range resolves.
   */
  val commits: List<RecordedCommit> = emptyList(),
)

fun reviewHarness(config: ReviewHarnessConfig, recorder: ReviewRecorder): ParallelCodeReviewRunner =
  ParallelCodeReviewRunner(
    ParallelCodeReviewRunnerDeps(
      parentReviewLauncher = GoalRunnerSubtaskLauncher { request ->
        recorder.parentLaunches += request
        if (config.simulateEvidenceReads) simulateGovernedEvidenceReads(request.skillRunRequest)
        config.parentLaunch?.invoke(request)?.let { return@GoalRunnerSubtaskLauncher it }
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
        ) as AgentRunLaunchOutcome
      },
      installedPackCatalog = InstalledPlatformPackCatalogPort { config.manifests },
      diffResolver = object : DiffResolverPort {
        override fun runProcess(args: List<String>, workDir: Path): String? {
          recorder.diffCommands += args
          return when (args.getOrNull(1)) {
            "rev-parse" -> args.last().removeSuffix("^{commit}")
            "rev-list" -> config.commits.joinToString("\n") { it.sha }
            "show" -> config.commits.single { it.sha == args.last() }.let { commit ->
              "${parentOf(config.commits, commit)}\n${commit.subject}"
            }
            else -> config.commits.firstOrNull {
              it.sha == args.getOrNull(3) && parentOf(config.commits, it) == args.getOrNull(2)
            }?.diff ?: config.diff
          }
        }
      },
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
      reviewEvidenceBrokerFactory = config.evidenceBrokerFactory,
      governedEvidenceEndpointBinder = config.evidenceEndpointBinder,
    ),
  )

/** The base revision every harness request declares; the root commit of a fixture range parents onto it. */
const val HARNESS_BASE_REVISION: String = "base-revision"

/** The head revision every harness request declares; a fixture range must end on it. */
const val HARNESS_HEAD_REVISION: String = "head-revision"

private fun parentOf(commits: List<RecordedCommit>, commit: RecordedCommit): String =
  commits.getOrNull(commits.indexOf(commit) - 1)?.sha ?: HARNESS_BASE_REVISION

/** Runs both lanes to completion in a fixed order so recorded evidence stays deterministic. */
private fun recordingRubricResolver(recorder: ReviewRecorder, rubricBody: (String) -> String) =
  object : ReviewRubricResolver {
    override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric {
      recorder.rubricResolutions += manifest?.slug ?: "generic"
      return ResolvedReviewRubric("parallel-code-review", rubricBody("parallel-code-review"))
    }

    override fun resolve(
      manifest: PlatformManifest?,
      evidence: List<ReviewOwnedFileEvidence>,
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
        @Suppress("UNCHECKED_CAST")
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
    override fun reviewStageDegradation(record: ReviewStageDegradationMeasurement) {
      recorder.stageDegradations += record
    }

    override fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String) = Unit

    override fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String) = Unit

    override fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String) = Unit

    override fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String) = Unit

    override fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String) = Unit

    override fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String) = Unit

    override fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String) = Unit

    override fun goalStarted(record: GoalStartedRecord, level: String) = Unit

    override fun goalSubtaskFinished(record: GoalSubtaskFinishedRecord, level: String) = Unit

    override fun goalFinished(record: GoalFinishedRecord, level: String) = Unit

    override fun goalIssueFinished(record: GoalIssueFinishedRecord, level: String) = Unit
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
  timeout: Duration? = null,
  reviewRunId: String? = null,
  prelaunchExpansions: List<ReviewPrelaunchExpansion> = emptyList(),
  codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.INLINE,
  scope: ParallelReviewScope = ParallelReviewScope.BRANCH,
) = ParallelCodeReviewRequest(
  agent1Id = agent1Id,
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
  fallback: Boolean = false,
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
  fallbackCapabilities = if (fallback) setOf("code-review") else emptySet(),
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

/** Replays the one thing the stub launcher cannot fake: the lane's own governed evidence reads. */
/**
 * Replays the one thing a launcher stub cannot fake: the lane's own governed evidence reads. Paths
 * come from the launch prompt's own `Owned paths:` lines, so this stays correct for any fixture
 * without the test having to restate its assignment.
 */
fun simulateGovernedEvidenceReads(request: SkillRunRequest) {
  val protocol = request.nativeReviewOperations ?: return
  val lane = request.reviewEvidenceBroker?.accounting()?.lane ?: return
  val prompt = request.promptOverride ?: return
  val paths = prompt.lineSequence()
    .filter { it.startsWith("Owned paths: ") }
    .flatMap { line -> OWNED_PATH.findAll(line.removePrefix("Owned paths: ")).map { it.groupValues[1] } }
    .distinct()
    .toList()
  if (paths.isEmpty()) return
  runCatching {
    protocol.read(
      ReviewEvidenceBatchRequest(
        lane = lane,
        requests = paths.map { ReviewEvidenceRequest(lane = lane, path = it) },
      ),
    )
  }
}

private val OWNED_PATH = Regex("\"([^\"]+)\"")

/**
 * The harness broker with one lane-evidence denial injected where the runner reads it. A fixture
 * packet carries no materializable hunk bodies, so a byte-driven refusal cannot be provoked here.
 */
fun brokerDenyingUnit(deniedPath: String): ReviewEvidenceBrokerFactory = ReviewEvidenceBrokerFactory { binding ->
  val delegate = FileSystemReviewEvidenceBrokerFactory().brokerFor(binding)
  val hunkId = binding.projectedHunks.first { it.path == deniedPath }.hunkId
  val commitSha = binding.assignment.assignedBundle.entries.first { hunkId in it.hunkIds }.commitSha
  val deniedUnit = "$commitSha@$deniedPath"
  object : ReviewEvidenceBroker by delegate {
    override fun accounting(): ReviewLaneAccounting = delegate.accounting().copy(
      budgetDimension = LANE_EVIDENCE_BYTES_DIMENSION,
      unreviewedUnits = listOf(deniedUnit),
    )
  }
}
