package skillbill.application

import skillbill.application.goalrunner.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.model.GoalPlanningSweepOutcome
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.model.GoalPlanningContext
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.EmptyWorkListRepository
import skillbill.ports.persistence.GoalPlanningPreparationRepository
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalPlanningSweepTest {
  @Test
  fun `multi-subtask sweep prepares every non-skipped pair in dependency order with one shared context`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("preplan", "plan", "preplan", "plan"), harness.launcher.phases)
    assertEquals(listOf(1, 1, 2, 2), harness.launcher.subtaskIds)
    assertEquals(2, harness.preparedCount())

    val record = harness.recordFor(1)
    assertNotNull(record, "each prepared pair must be durably persisted with its provenance")
    assertEquals("SKILL-56", record.normalizedIssueKey)
    assertTrue(record.repositoryIdentity.startsWith("repo-root-realpath-v1:"))
    assertEquals(".feature-specs/SKILL-56-goal/spec_subtask_1.md", record.governedSubSpecPath)
    assertNotNull(JsonSupport.parseObjectOrNull(record.preplanPayload), "preplan payload must be strict JSON")
    assertNotNull(JsonSupport.parseObjectOrNull(record.planPayload), "plan payload must be strict JSON")
  }

  @Test
  fun `shared repository and decomposition discovery happens exactly once across all sub_specs`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 3)), harness.request())

    assertEquals(1, harness.manifestFileStore.countContaining("/spec.md"))
    assertEquals(1, harness.manifestFileStore.countContaining("decomposition-manifest.yaml"))
    assertEquals(3, harness.manifestFileStore.countContaining("spec_subtask_"))
    assertEquals(6, harness.launcher.requests.size)
  }

  @Test
  fun `non-skipped subtask with an allocated workflow remains planning eligible`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val allocated = manifest(subtaskCount = 1).let { manifest ->
      manifest.copy(subtasks = manifest.subtasks.map { it.copy(workflowId = "wfl-child") })
    }

    val outcome = harness.sweep.prepare(harness.stateFor(allocated), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("preplan", "plan"), harness.launcher.phases)
  }

  @Test
  fun `planning emits a progress line per phase in caller order`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val progress = mutableListOf<String>()
    val request = harness.request().copy(
      outputSink = AgentRunOutputSink { stream, text ->
        if (stream == AgentRunOutputStream.STDERR) progress += text
      },
    )

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), request)

    assertEquals(
      listOf(
        "skill-bill: goal planning - subtask 1 preplan\n",
        "skill-bill: goal planning - subtask 1 plan\n",
        "skill-bill: goal planning - subtask 2 preplan\n",
        "skill-bill: goal planning - subtask 2 plan\n",
      ),
      progress,
    )
  }

  @Test
  fun `resume after a crash between pairs continues at the next subtask without replanning`() {
    val fixtures = sharedSweepFixtures()
    val runOneLauncher = SweepPlanningLauncher { phase, subtaskId, _ ->
      if (subtaskId == 2 && phase == "preplan") launchFacts(stdout = "") else validPhaseOutcome(phase)
    }
    val runOne = DefaultGoalPlanningSweep(
      fixtures.database,
      fixtures.checkpoint,
      fixtures.outputValidator,
      runOneLauncher,
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      fakeContextDiscovery,
    )

    val stoppedRunOne = runOne.prepare(fixtures.stateFor(manifest(subtaskCount = 2)), fixtures.request())
    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(stoppedRunOne)
    assertEquals(2, stopped.currentSubtaskId)
    assertEquals(1, fixtures.preparedCount())
    assertEquals(3, runOneLauncher.requests.size)

    val runTwoLauncher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val runTwo = DefaultGoalPlanningSweep(
      fixtures.database,
      fixtures.checkpoint,
      fixtures.outputValidator,
      runTwoLauncher,
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      fakeContextDiscovery,
    )

    val outcome = runTwo.prepare(fixtures.stateFor(manifest(subtaskCount = 2)), fixtures.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(2, runTwoLauncher.requests.size)
    assertEquals(listOf("preplan", "plan"), runTwoLauncher.phases)
    assertEquals(listOf(2, 2), runTwoLauncher.subtaskIds)
    assertEquals(2, fixtures.preparedCount())
  }

  @Test
  fun `blocked planning stops before mutation with the current subtask and resumable state`() {
    val harness = sweepHarness(markPreparedThrows = false) { _, _, _ -> spawnBlockedOutcome() }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `schema valid blocked payload is never checkpointed as prepared`() {
    val harness = sweepHarness { phase, _, _ ->
      launchFacts(stdout = phasePayload(phase).replace("\"completed\"", "\"blocked\""))
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `failed launch cannot pass output gate with stale stdout`() {
    val harness = sweepHarness { phase, _, _ ->
      launchFacts(stdout = phasePayload(phase)).copy(exitStatus = 1)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `malformed phase output stops before mutation with no checkpointed pair`() {
    val harness = sweepHarness { _, _, _ -> launchFacts(stdout = "not a json object") }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `persistence failure rolls back leaving no half pair and stops before mutation`() {
    val harness = sweepHarness(markPreparedThrows = true) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `all plans gate blocks every child activation while a pair is missing`() {
    val fixtures = sharedSweepFixtures()
    val sharedLauncher = SweepPlanningLauncher { phase, subtaskId, _ ->
      if (subtaskId == 2) launchFacts(stdout = "") else validPhaseOutcome(phase)
    }
    val sweep = DefaultGoalPlanningSweep(
      fixtures.database,
      fixtures.checkpoint,
      fixtures.outputValidator,
      sharedLauncher,
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      fakeContextDiscovery,
    )
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = sharedLauncher,
      outcomeStore = RecordingOutcomeStore(),
      pullRequestPort = RecordingPullRequestPort(),
      goalPlanningSweep = sweep,
    )

    val report = runner.run(fixtures.request())

    assertIs<GoalRunnerRunReport.Stopped>(report)
    assertTrue(sharedLauncher.requests.isNotEmpty(), "the sweep must have attempted planning before stopping")
    assertTrue(
      sharedLauncher.requests.all { it.skillRunRequest.promptOverride != null },
      "every launch while a pair is missing must be a planning prompt, not a child activation",
    )
    assertTrue(
      sharedLauncher.requests.all { it.skillRunRequest.goalContinuation == null },
      "no child workflow may be activated until every non-skipped pair is prepared",
    )
  }

  @Test
  fun `planning payloads are persisted as strict canonical json even when the agent fences output with prose`() {
    val harness = sweepHarness(outputValidator = FenceAwarePhaseOutputValidator()) { phase, _, _ ->
      launchFacts(stdout = fencedPhasePayload(phase))
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val record = harness.recordFor(1)
    assertNotNull(record, "the prepared pair must be persisted")
    val preplanMap = JsonSupport.parseObjectOrNull(record.preplanPayload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
    val planMap = JsonSupport.parseObjectOrNull(record.planPayload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
    assertNotNull(preplanMap, "preplan payload must be strict JSON, not fenced prose")
    assertNotNull(planMap, "plan payload must be strict JSON, not fenced prose")
    assertFalse(record.preplanPayload.contains("```") || record.preplanPayload.contains("Here is"))
    assertFalse(record.planPayload.contains("```") || record.planPayload.contains("Here is"))
    assertEquals("preplan", preplanMap["phase_id"])
    assertEquals("plan", planMap["phase_id"])
  }

  @Test
  fun `missing or unreadable shared governed spec stops before mutation with a clear pre sweep state`() {
    val outputValidator = FakePhaseOutputValidator()
    val database = InMemoryPreparationDatabase()
    val checkpoint = GoalPlanningPreparationCheckpoint(
      database = database,
      envelopeValidator = NoopGoalPlanningPreparationEnvelopeValidator,
      phaseOutputValidator = outputValidator,
    )
    val launcher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val sweep = DefaultGoalPlanningSweep(
      database,
      checkpoint,
      outputValidator,
      launcher,
      FakeInvariantsSource(),
      ThrowingManifestFileStore(),
      fakeContextDiscovery,
    )
    val state = GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = "/fake/goal-planning-sweep-preparations.db",
      manifest = manifest(subtaskCount = 2),
    )
    val request = GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Files.createTempDirectory("goal-planning-sweep"),
      invokedAgentId = "claude",
      dbPathOverride = "/fake/goal-planning-sweep-preparations.db",
    )

    val outcome = sweep.prepare(state, request)

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("shared context could not be gathered"))
    assertEquals(0, launcher.requests.size, "no planning agent launches before shared discovery succeeds")
  }

  @Test
  fun `incompatible provenance on a prepared row stops before mutation instead of silently reusing the stale plan`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    val staleRecord = prepared.copy(
      provenance = prepared.provenance.copy(parentSpecHash = "stale-parent-spec-hash"),
    )
    harness.fixtures.database.repository.markPrepared(staleRecord)
    val launchCountBeforeResume = harness.launcher.requests.size

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("incompatible"))
    assertEquals(
      launchCountBeforeResume,
      harness.launcher.requests.size,
      "no planning launch occurs once incompatible provenance is rejected",
    )
  }
}

private fun validPhaseOutcome(phase: String): AgentRunLaunchOutcome = launchFacts(stdout = phasePayload(phase))

private fun spawnBlockedOutcome(): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = null,
  stdout = "",
  stderr = "planning agent could not start",
  timedOut = false,
  interrupted = false,
  spawnFailed = true,
)

private fun phasePayload(phaseId: String): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"$phaseId",""" +
    """"status":"completed","summary":"s","produced_outputs":{"result":"$phaseId"}}"""

private fun fencedPhasePayload(phaseId: String): String =
  "Here is the $phaseId output.\n```json\n" + phasePayload(phaseId) + "\n```\nLet me know if you need more."

private class SweepPlanningLauncher(
  private val behavior: (phase: String, subtaskId: Int, request: GoalRunnerSubtaskLaunchRequest)
  -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
  val phases = mutableListOf<String>()
  val subtaskIds = mutableListOf<Int>()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    val phase = phaseOf(request)
    val subtaskId = request.skillRunRequest.subtaskId ?: 0
    requests += request
    phases += phase
    subtaskIds += subtaskId
    return behavior(phase, subtaskId, request)
  }

  fun phaseOf(request: GoalRunnerSubtaskLaunchRequest): String {
    val prompt = request.skillRunRequest.promptOverride.orEmpty()
    return Regex("""Phase: (\w+) \(""").find(prompt)?.groupValues?.get(1) ?: "unknown"
  }
}

private class CountingManifestFileStore : DecompositionManifestFileStore {
  private val readPaths = mutableListOf<String>()

  override fun readText(path: Path): String {
    readPaths += path.toString()
    return "content-${path.fileName}"
  }

  override fun isRegularFile(path: Path): Boolean = true

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = emptyList()

  override fun writeTextAtomically(target: Path, content: String): Unit =
    error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String =
    error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  fun countContaining(fragment: String): Int = readPaths.count { path -> fragment in path }
}

private class ThrowingManifestFileStore : DecompositionManifestFileStore {
  override fun readText(path: Path): String = error("simulated unreadable governed spec at ${path.fileName}")

  override fun isRegularFile(path: Path): Boolean = true

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = emptyList()

  override fun writeTextAtomically(target: Path, content: String): Unit =
    error("ThrowingManifestFileStore is read-only in goal planning sweep tests.")

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String =
    error("ThrowingManifestFileStore is read-only in goal planning sweep tests.")
}

private class FakeInvariantsSource : FeatureTaskRuntimeRunInvariantsSource {
  override fun read(specPath: Path): FeatureTaskRuntimeRunInvariants = FeatureTaskRuntimeRunInvariants(
    specReference = specPath.toString(),
    featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
    acceptanceCriteria = listOf("The sweep produces a schema-valid planning pair for this sub-spec."),
    mandatesAndOverrides = emptyList(),
  )
}

private class FakePhaseOutputValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    val output = JsonSupport.parseObjectOrNull(phaseOutputText)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: throw malformed(sourceLabel, "Phase output root must be a single JSON object.")
    val contractVersion = output["contract_version"]?.toString()
    val phaseId = output["phase_id"]?.toString()
    val status = output["status"]?.toString()
    val produced = output["produced_outputs"]
    if (contractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
      throw malformed(sourceLabel, "contract_version must be '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'.")
    }
    if (phaseId != sourceLabel) {
      throw malformed(sourceLabel, "phase_id must be '$sourceLabel'.")
    }
    if (status !in setOf("completed", "blocked", "failed")) {
      throw malformed(sourceLabel, "status must be completed, blocked, or failed.")
    }
    if (produced !is Map<*, *> || produced.isEmpty()) {
      throw malformed(sourceLabel, "produced_outputs must be a non-empty object.")
    }
  }

  private fun malformed(sourceLabel: String, reason: String): InvalidFeatureTaskRuntimePhaseOutputSchemaError =
    InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel = sourceLabel, reason = reason)
}

private class FenceAwarePhaseOutputValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    validateAndReadPhaseOutput(phaseOutputText, sourceLabel)
  }

  override fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    val candidate = firstJsonObject(phaseOutputText)
      ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "Phase output root must contain a single JSON object.",
      )
    val output = JsonSupport.parseObjectOrNull(candidate)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "Phase output root must be a single JSON object.",
      )
    when {
      output["contract_version"]?.toString() != FEATURE_TASK_RUNTIME_CONTRACT_VERSION ->
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "contract_version must be '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'.",
        )
      output["phase_id"]?.toString() != sourceLabel ->
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "phase_id must be '$sourceLabel'.",
        )
      output["status"]?.toString() !in setOf("completed", "blocked", "failed") ->
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "status must be completed, blocked, or failed.",
        )
      output["produced_outputs"] !is Map<*, *> || (output["produced_outputs"] as Map<*, *>).isEmpty() ->
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "produced_outputs must be a non-empty object.",
        )
    }
    return output
  }

  private fun firstJsonObject(text: String): String? {
    val fenced = Regex("```[A-Za-z]*\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
      .find(text)?.groupValues?.get(1)
    val candidate = (fenced ?: text).trim()
    val open = candidate.indexOf('{')
    val close = candidate.lastIndexOf('}')
    return if (open in 0 until close) candidate.substring(open, close + 1) else null
  }
}

private class InMemoryPreparationRepository(
  private val markPreparedThrows: Boolean = false,
) : GoalPlanningPreparationRepository {
  private val records = linkedMapOf<Int, GoalPlanningPreparationRecord>()

  override fun markPrepared(record: GoalPlanningPreparationRecord) {
    if (markPreparedThrows) error("simulated goal planning persistence failure")
    records[record.subtaskId] = record
  }

  override fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
    records[subtaskId]

  override fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    records.values.toList().sortedBy { it.subtaskId }

  override fun preparedCount(parentGoalWorkflowId: String): Int = records.size

  override fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int? =
    orderedSubtaskIds.firstOrNull { id -> id !in records }

  override fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? =
    records[subtaskId]?.let { record ->
      GoalPlanningPreparationStatus(parentGoalWorkflowId, subtaskId, record.preparationStatus, record.provenance)
    }

  fun count(): Int = records.size
}

private class InMemoryPreparationDatabase(markPreparedThrows: Boolean = false) : DatabaseSessionFactory {
  val repository = InMemoryPreparationRepository(markPreparedThrows)
  private val dbPath = Path.of("/fake/goal-planning-sweep-preparations.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath
  override fun databaseExists(dbOverride: String?): Boolean = true
  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())
  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@InMemoryPreparationDatabase.dbPath
    override val reviews: ReviewRepository get() = error("unused by goal planning sweep tests")
    override val learnings: LearningRepository get() = error("unused by goal planning sweep tests")
    override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by goal planning sweep tests")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("unused by goal planning sweep tests")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal planning sweep tests")
    override val workflowStates: WorkflowStateRepository get() = error("unused by goal planning sweep tests")
    override val workList = EmptyWorkListRepository
    override val goalPlanningPreparations: GoalPlanningPreparationRepository = repository
  }
}

private data class SweepFixtures(
  val database: InMemoryPreparationDatabase,
  val checkpoint: GoalPlanningPreparationCheckpoint,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val manifestFileStore: CountingManifestFileStore,
  val invariantsSource: FakeInvariantsSource,
  val repoRoot: Path,
  val dbOverride: String,
) {
  fun stateFor(manifest: DecompositionManifest): GoalRunnerManifestState = GoalRunnerManifestState(
    parentWorkflowId = "wfl-parent",
    dbPath = dbOverride,
    manifest = manifest,
  )

  fun request(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = repoRoot,
    invokedAgentId = "claude",
    dbPathOverride = dbOverride,
  )

  fun preparedCount(): Int = database.repository.count()
}

private fun sharedSweepFixtures(
  markPreparedThrows: Boolean = false,
  outputValidator: FeatureTaskRuntimePhaseOutputValidator = FakePhaseOutputValidator(),
): SweepFixtures {
  val database = InMemoryPreparationDatabase(markPreparedThrows = markPreparedThrows)
  val checkpoint = GoalPlanningPreparationCheckpoint(
    database = database,
    envelopeValidator = NoopGoalPlanningPreparationEnvelopeValidator,
    phaseOutputValidator = outputValidator,
  )
  return SweepFixtures(
    database = database,
    checkpoint = checkpoint,
    outputValidator = outputValidator,
    manifestFileStore = CountingManifestFileStore(),
    invariantsSource = FakeInvariantsSource(),
    repoRoot = Files.createTempDirectory("goal-planning-sweep"),
    dbOverride = "/fake/goal-planning-sweep-preparations.db",
  )
}

private class SweepHarness(
  val fixtures: SweepFixtures,
  val launcher: SweepPlanningLauncher,
  val sweep: DefaultGoalPlanningSweep,
) {
  fun stateFor(manifest: DecompositionManifest): GoalRunnerManifestState = fixtures.stateFor(manifest)
  fun request(): GoalRunnerRunRequest = fixtures.request()
  fun preparedCount(): Int = fixtures.preparedCount()
  fun recordFor(subtaskId: Int): GoalPlanningPreparationRecord? =
    fixtures.database.repository.findByGoalAndSubtask("wfl-parent", subtaskId)
  val manifestFileStore: CountingManifestFileStore get() = fixtures.manifestFileStore
}

private fun sweepHarness(
  markPreparedThrows: Boolean = false,
  outputValidator: FeatureTaskRuntimePhaseOutputValidator = FakePhaseOutputValidator(),
  behavior: (phase: String, subtaskId: Int, request: GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
): SweepHarness {
  val fixtures = sharedSweepFixtures(markPreparedThrows = markPreparedThrows, outputValidator = outputValidator)
  val launcher = SweepPlanningLauncher(behavior)
  val sweep = DefaultGoalPlanningSweep(
    fixtures.database,
    fixtures.checkpoint,
    fixtures.outputValidator,
    launcher,
    fixtures.invariantsSource,
    fixtures.manifestFileStore,
    fakeContextDiscovery,
  )
  return SweepHarness(fixtures, launcher, sweep)
}

private val fakeContextDiscovery = GoalPlanningContextDiscovery {
  GoalPlanningContext(
    platformPacks = mapOf("platform-packs/kotlin/platform.yaml" to "contract_version: '1.2'"),
    boundaryMemory = mapOf("platform-packs/kotlin/agent/history.md" to "history"),
    validationGuidance = "Run focused Gradle checks.",
  )
}
