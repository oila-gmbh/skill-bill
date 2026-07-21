package skillbill.application

import skillbill.application.featuretask.sha256HexUtf8
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
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
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
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.SpecSource
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
  fun `prepared sweep reports absent hydration context for a sibling added after preparation`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val prepared = assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
    )

    assertNotNull(prepared.hydrationFor(1))
    assertEquals(null, prepared.hydrationFor(2))
  }

  @Test
  fun `multi-subtask sweep prepares one shared preplan and every included subtask plan in manifest order`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val prepared = assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(harness.stateFor(manifest(subtaskCount = 2)).parentWorkflowId, prepared.identity?.parentGoalWorkflowId)
    assertEquals(listOf(1, 2), prepared.descriptors.map { it.subtaskId })
    assertNotNull(prepared.provenance)
    assertEquals(listOf("preplan", "plan", "plan"), harness.launcher.phases)
    assertEquals(listOf(0, 1, 2), harness.launcher.subtaskIds)
    assertEquals(2, harness.preparedCount())
    val preplanPrompt = harness.launcher.requests.first().skillRunRequest.promptOverride.orEmpty()
    val planPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertFalse(preplanPrompt.contains("Current governed sub-spec:"))
    assertFalse(preplanPrompt.contains("Current subtask dependency context:"))
    assertTrue(
      preplanPrompt.contains(
        "spec_reference: ${harness.fixtures.repoRoot.toRealPath().resolve(".feature-specs/SKILL-56-goal/spec.md")}",
      ),
      "the singleton preplan must be governed by the parent goal spec",
    )
    assertTrue(planPrompt.contains("Current governed sub-spec:"))
    assertTrue(planPrompt.contains("Current subtask dependency context:"))

    val record = harness.recordFor(1)
    assertNotNull(record, "each subtask plan must be durably persisted with its shared provenance")
    assertEquals("SKILL-56", record.normalizedIssueKey)
    assertTrue(record.repositoryIdentity.startsWith("repo-root-realpath-v1:"))
    assertEquals(".feature-specs/SKILL-56-goal/spec_subtask_1.md", record.governedSubSpecPath)
    assertNotNull(JsonSupport.parseObjectOrNull(record.preplanPayload), "preplan payload must be strict JSON")
    assertNotNull(JsonSupport.parseObjectOrNull(record.planPayload), "plan payload must be strict JSON")
  }

  @Test
  fun `shared repository and decomposition discovery happens exactly once across all sub_specs`() {
    val discovery = CountingContextDiscovery()
    val harness = sweepHarness(contextDiscovery = discovery) { phase, _, _ -> validPhaseOutcome(phase) }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 3)), harness.request())

    assertEquals(1, harness.manifestFileStore.countContaining("/spec.md"))
    assertEquals(1, harness.manifestFileStore.countContaining("decomposition-manifest.yaml"))
    assertEquals(3, harness.manifestFileStore.countContaining("spec_subtask_"))
    assertEquals(1, discovery.calls)
    assertEquals(4, harness.launcher.requests.size)
  }

  @Test
  fun `resume revalidates saved payload status before accepting a prepared row`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val saved = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      saved.copy(planPayload = saved.planPayload.replace("\"completed\"", "\"blocked\"")),
    )
    val launchCount = harness.launcher.requests.size

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.isNotBlank())
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume accepts a completed Linear plan when its governed scratch spec is deleted`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR)
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    harness.manifestFileStore.remove("spec_subtask_1.md")
    val launchCount = harness.launcher.requests.size
    val resumed = initial.copy(
      subtasks = initial.subtasks.map { subtask ->
        if (subtask.id == 1) subtask.copy(status = "complete") else subtask
      },
    )

    val outcome = harness.sweep.prepare(harness.stateFor(resumed), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `mutable execution fields do not invalidate immutable decomposition provenance`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 1)
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceDecompositionManifest("runtime projection changed")
    val advanced = initial.copy(
      status = "in_progress",
      currentSubtaskIntent = initial.currentSubtaskIntent.copy(action = "resume"),
      subtasks = initial.subtasks.map {
        it.copy(status = "skipped", workflowId = "wfl-child", commitSha = "abc123", lastResumableStep = "pr")
      },
    )

    val outcome = harness.sweep.prepare(harness.stateFor(advanced), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume uses saved planning when initial specs change`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited after planning")
    harness.manifestFileStore.replaceSpec("spec_subtask_1.md", "# Initial subtask contract edited after planning")

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount, harness.launcher.requests.size)
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
        "skill-bill: goal planning - parent goal shared preplan\n",
        "skill-bill: goal planning - subtask 1 plan\n",
        "skill-bill: goal planning - subtask 2 plan\n",
      ),
      progress,
    )
  }

  @Test
  fun `resume after a crash between plans continues at the next subtask without rediscovery`() {
    val fixtures = sharedSweepFixtures()
    val discovery = CountingContextDiscovery()
    val runOneLauncher = SweepPlanningLauncher { phase, subtaskId, _ ->
      if (subtaskId == 2 && phase == "plan") launchFacts(stdout = "") else validPhaseOutcome(phase)
    }
    val runOne = DefaultGoalPlanningSweep(
      fixtures.checkpoint,
      fixtures.outputValidator,
      runOneLauncher,
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      discovery,
    )

    val initial = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR)
    val stoppedRunOne = runOne.prepare(fixtures.stateFor(initial), fixtures.request())
    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(stoppedRunOne)
    assertEquals(2, stopped.currentSubtaskId)
    assertEquals(1, fixtures.preparedCount())
    assertEquals(3, runOneLauncher.requests.size)
    val runTwoLauncher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val runTwo = DefaultGoalPlanningSweep(
      fixtures.checkpoint,
      fixtures.outputValidator,
      runTwoLauncher,
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      discovery,
    )

    val resumed = initial.copy(
      subtasks = initial.subtasks.map { subtask ->
        if (subtask.id == 1) subtask.copy(status = "complete") else subtask
      },
    )
    fixtures.manifestFileStore.remove("spec_subtask_1.md")
    val outcome = runTwo.prepare(fixtures.stateFor(resumed), fixtures.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("plan"), runTwoLauncher.phases)
    assertEquals(listOf(2), runTwoLauncher.subtaskIds)
    assertEquals(2, fixtures.preparedCount())
    assertEquals(1, discovery.calls, "resume must recover the durable packet without repeating discovery")
  }

  @Test
  fun `blocked planning stops before mutation with the current subtask and resumable state`() {
    val harness = sweepHarness(markPreparedThrows = false) { _, _, _ -> spawnBlockedOutcome() }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
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
  fun `plan failure resumes at plan while retaining the durable shared preplan`() {
    val discovery = CountingContextDiscovery()
    var failPlan = true
    val harness = sweepHarness(contextDiscovery = discovery) { phase, _, _ ->
      val payload = if (phase == "plan" && failPlan) {
        phasePayload(phase).replace("\"completed\"", "\"failed\"")
      } else {
        phasePayload(phase)
      }
      launchFacts(stdout = payload)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("'plan' stopped"))
    assertEquals(0, harness.preparedCount())
    assertNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))

    failPlan = false
    val resumed = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(resumed)
    assertEquals(listOf("preplan", "plan", "plan"), harness.launcher.phases)
    assertEquals(1, discovery.calls, "resume after shared-preplan persistence must not repeat discovery")
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
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `persistence failure rolls back leaving no half pair and stops before mutation`() {
    val harness = sweepHarness(markPreparedThrows = true) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `plan checkpoint failure resumes at plan after retaining the shared preplan`() {
    val harness = sweepHarness(planCheckpointThrows = true) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `all plans gate blocks every child activation while a plan is missing`() {
    val fixtures = sharedSweepFixtures()
    val sharedLauncher = SweepPlanningLauncher { phase, subtaskId, _ ->
      if (subtaskId == 2) launchFacts(stdout = "") else validPhaseOutcome(phase)
    }
    val sweep = DefaultGoalPlanningSweep(
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
      "every launch while a plan is missing must be a planning prompt, not a child activation",
    )
    assertTrue(
      sharedLauncher.requests.all { it.skillRunRequest.goalContinuation == null },
      "no child workflow may be activated until every included plan is prepared",
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
    assertNotNull(record, "the shared preplan and subtask plan must be persisted")
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
  fun `incompatible provenance on only a stored plan identifies that plan and stops before another launch`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())
    harness.fixtures.database.repository.corruptPlanProvenance(1)
    val launchCountBeforeResume = harness.launcher.requests.size

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("cannot be recovered"))
    assertEquals(
      launchCountBeforeResume,
      harness.launcher.requests.size,
      "no planning launch occurs once incompatible provenance is rejected",
    )
  }

  @Test
  fun `recovered shared packet requires every governed context field even with valid integrity`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      prepared.withSharedPacket { packet -> packet - "validation_guidance" },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.contains("shared context could not be gathered"))
  }

  @Test
  fun `recovered shared packet rejects missing or unknown planning dispositions`() {
    listOf<(Map<String, Any?>) -> Map<String, Any?>>(
      { subtask -> subtask - "planning_disposition" },
      { subtask -> subtask + ("planning_disposition" to "unknown") },
    ).forEach { corruptDisposition ->
      val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
      val state = harness.stateFor(manifest(subtaskCount = 1))
      harness.sweep.prepare(state, harness.request())
      val prepared = requireNotNull(harness.recordFor(1))
      val launchCount = harness.launcher.requests.size
      harness.fixtures.database.repository.markPrepared(
        prepared.withSharedPacket { packet ->
          val ordered = packet["ordered_subtasks"] as List<*>
          val first = requireNotNull(JsonSupport.anyToStringAnyMap(ordered.first()))
          packet + ("ordered_subtasks" to listOf(corruptDisposition(first)))
        },
      )

      val outcome = harness.sweep.prepare(state, harness.request())

      val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
      assertEquals(0, stopped.currentSubtaskId)
      assertTrue(stopped.blockedReason.contains("shared context could not be gathered"))
      assertEquals(launchCount, harness.launcher.requests.size)
    }
  }

  @Test
  fun `normalized recovery reads the singleton shared packet independently from prepared plans`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 2))
    harness.sweep.prepare(state, harness.request())
    val second = requireNotNull(harness.recordFor(2))
    harness.fixtures.database.repository.markPrepared(
      second.withSharedPacket { packet -> packet + ("validation_guidance" to "different valid guidance") },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
  }

  @Test
  fun `prepared row without a shared packet reports its known subtask`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(prepared.withoutSharedPacket())

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.contains("does not contain a valid shared context packet"))
  }

  @Test
  fun `missing local or pending Linear sub-spec invalidates prepared provenance`() {
    listOf(
      manifest(subtaskCount = 1),
      manifest(subtaskCount = 1).copy(specSource = SpecSource.LINEAR),
    ).forEach { manifest ->
      val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
      harness.sweep.prepare(harness.stateFor(manifest), harness.request())
      harness.manifestFileStore.remove("spec_subtask_1.md")

      val outcome = harness.sweep.prepare(harness.stateFor(manifest), harness.request())

      val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
      assertEquals(0, stopped.currentSubtaskId)
      assertTrue(stopped.blockedReason.contains("provenance"))
    }
  }
}

private fun GoalPlanningPreparationRecord.withSharedPacket(
  transform: (Map<String, Any?>) -> Map<String, Any?>,
): GoalPlanningPreparationRecord {
  val root = preplanRoot()
  val produced = requireNotNull(JsonSupport.anyToStringAnyMap(root["produced_outputs"]))
  val packet = requireNotNull(JsonSupport.anyToStringAnyMap(produced["_goal_planning_shared_context"]))
  val transformed = transform(packet - "integrity_sha256")
  val packetWithIntegrity = transformed + (
    "integrity_sha256" to sha256HexUtf8(JsonSupport.mapToJsonString(transformed))
    )
  return copy(
    preplanPayload = JsonSupport.mapToJsonString(
      root + ("produced_outputs" to (produced + ("_goal_planning_shared_context" to packetWithIntegrity))),
    ),
  )
}

private fun GoalPlanningPreparationRecord.withoutSharedPacket(): GoalPlanningPreparationRecord {
  val root = preplanRoot()
  val produced = requireNotNull(JsonSupport.anyToStringAnyMap(root["produced_outputs"]))
  val withoutPacket = root + ("produced_outputs" to (produced - "_goal_planning_shared_context"))
  return copy(preplanPayload = JsonSupport.mapToJsonString(withoutPacket))
}

private fun GoalPlanningPreparationRecord.preplanRoot(): Map<String, Any?> =
  requireNotNull(JsonSupport.parseObjectOrNull(preplanPayload))
    .let(JsonSupport::jsonElementToValue)
    .let { requireNotNull(JsonSupport.anyToStringAnyMap(it)) }

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
  private val removedFileNames = mutableSetOf<String>()
  private var decompositionManifest = "content-decomposition-manifest.yaml"
  private val specContents = mutableMapOf<String, String>()

  override fun readText(path: Path): String {
    check(path.fileName.toString() !in removedFileNames) { "missing scratch spec at ${path.fileName}" }
    readPaths += path.toString()
    return if (path.fileName.toString() == "decomposition-manifest.yaml") {
      decompositionManifest
    } else {
      specContents[path.fileName.toString()] ?: "content-${path.fileName}"
    }
  }

  override fun isRegularFile(path: Path): Boolean = path.fileName.toString() !in removedFileNames

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = emptyList()

  override fun writeTextAtomically(target: Path, content: String): Unit =
    error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String =
    error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  fun countContaining(fragment: String): Int = readPaths.count { path -> fragment in path }

  fun remove(fileName: String) {
    removedFileNames += fileName
  }

  fun replaceDecompositionManifest(content: String) {
    decompositionManifest = content
  }

  fun replaceSpec(fileName: String, content: String) {
    specContents[fileName] = content
  }
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
    acceptanceCriteria = listOf("The sweep produces a schema-valid plan for this sub-spec."),
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
  private val planCheckpointThrows: Boolean = false,
) : GoalPlanningPreparationRepository {
  private val records = linkedMapOf<Int, GoalPlanningPreparationRecord>()
  private var sharedPreplan: skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint? = null
  private val plans = linkedMapOf<Int, skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint>()

  override fun checkpointSharedPreplan(checkpoint: skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint) {
    sharedPreplan = checkpoint
    if (markPreparedThrows) {
      sharedPreplan = null
      error("simulated goal planning persistence failure after mutation")
    }
  }

  fun corruptPlanProvenance(subtaskId: Int) {
    val plan = requireNotNull(plans[subtaskId])
    plans[subtaskId] = plan.copy(provenance = plan.provenance.copy(parentSpecHash = "stale-parent-spec-hash"))
  }

  override fun findSharedPreplan(expectedIdentity: skillbill.ports.persistence.model.GoalPlanningIdentity) =
    sharedPreplan?.takeIf { it.identity == expectedIdentity }

  override fun checkpointSubtaskPlan(checkpoint: skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint) {
    plans[checkpoint.subtaskId] = checkpoint
    val shared = requireNotNull(sharedPreplan)
    records[checkpoint.subtaskId] = GoalPlanningPreparationRecord(
      parentGoalWorkflowId = checkpoint.identity.parentGoalWorkflowId,
      normalizedIssueKey = checkpoint.identity.normalizedIssueKey,
      repositoryIdentity = checkpoint.identity.repositoryIdentity,
      subtaskId = checkpoint.subtaskId,
      governedSubSpecPath = checkpoint.governedSubSpecPath,
      preparationStatus = checkpoint.preparationStatus,
      provenance = skillbill.ports.persistence.model.GoalPlanningPreparationProvenance(
        parentSpecHash = checkpoint.provenance.parentSpecHash,
        subSpecHash = checkpoint.subSpecHash,
        decompositionManifestHash = checkpoint.provenance.decompositionManifestHash,
        phaseOutputContractId = checkpoint.provenance.phaseOutputContractId,
        phaseOutputContractVersion = checkpoint.provenance.phaseOutputContractVersion,
      ),
      preplanPayload = shared.preplanPayload,
      planPayload = checkpoint.planPayload,
    )
    if (markPreparedThrows) {
      plans.remove(checkpoint.subtaskId)
      records.remove(checkpoint.subtaskId)
      error("simulated goal planning persistence failure after mutation")
    }
    if (planCheckpointThrows) {
      plans.remove(checkpoint.subtaskId)
      records.remove(checkpoint.subtaskId)
      error("simulated plan checkpoint failure after mutation")
    }
  }

  override fun findSubtaskPlan(
    expectedIdentity: skillbill.ports.persistence.model.GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ) = plans[subtaskId]?.takeIf { it.identity == expectedIdentity && it.governedSubSpecPath == governedSubSpecPath }

  override fun listSubtaskPlansOrdered(
    expectedIdentity: skillbill.ports.persistence.model.GoalPlanningIdentity,
    orderedDescriptors: List<skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor>,
  ) = plans.values.filter { it.identity == expectedIdentity }.sortedBy { it.manifestOrder }

  override fun markPrepared(record: GoalPlanningPreparationRecord) {
    records[record.subtaskId] = record
    val identity = skillbill.ports.persistence.model.GoalPlanningIdentity(
      record.parentGoalWorkflowId,
      record.normalizedIssueKey,
      record.repositoryIdentity,
    )
    val provenance = skillbill.ports.persistence.model.GoalPlanningContractProvenance(
      record.provenance.parentSpecHash,
      record.provenance.decompositionManifestHash,
      skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
    )
    sharedPreplan = skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint(
      identity = identity,
      provenance = provenance,
      payloadSha256 = skillbill.application.featuretask.sha256HexUtf8(record.preplanPayload),
      preplanPayload = record.preplanPayload,
    )
    plans[record.subtaskId] = skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint(
      identity = identity,
      subtaskId = record.subtaskId,
      manifestOrder = record.subtaskId - 1,
      governedSubSpecPath = record.governedSubSpecPath,
      subSpecHash = record.provenance.subSpecHash,
      provenance = provenance,
      payloadSha256 = skillbill.application.featuretask.sha256HexUtf8(record.planPayload),
      planPayload = record.planPayload,
    )
    if (markPreparedThrows) {
      records.remove(record.subtaskId)
      plans.remove(record.subtaskId)
      sharedPreplan = null
      error("simulated goal planning persistence failure after mutation")
    }
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

  override fun deleteByGoal(parentGoalWorkflowId: String): Int {
    val matchingIds = records.values
      .filter { record -> record.parentGoalWorkflowId == parentGoalWorkflowId }
      .map(GoalPlanningPreparationRecord::subtaskId)
    matchingIds.forEach(records::remove)
    return matchingIds.size
  }

  fun count(): Int = records.size
}

private class InMemoryPreparationDatabase(
  markPreparedThrows: Boolean = false,
  planCheckpointThrows: Boolean = false,
) : DatabaseSessionFactory {
  val repository = InMemoryPreparationRepository(markPreparedThrows, planCheckpointThrows)
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
  planCheckpointThrows: Boolean = false,
  outputValidator: FeatureTaskRuntimePhaseOutputValidator = FakePhaseOutputValidator(),
): SweepFixtures {
  val database = InMemoryPreparationDatabase(
    markPreparedThrows = markPreparedThrows,
    planCheckpointThrows = planCheckpointThrows,
  )
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
  fun identity(): GoalPlanningIdentity = GoalPlanningIdentity(
    "wfl-parent",
    "SKILL-56",
    "repo-root-realpath-v1:${fixtures.repoRoot.toRealPath()}",
  )
  fun recordFor(subtaskId: Int): GoalPlanningPreparationRecord? =
    fixtures.database.repository.findByGoalAndSubtask("wfl-parent", subtaskId)
  val manifestFileStore: CountingManifestFileStore get() = fixtures.manifestFileStore
}

private fun sweepHarness(
  markPreparedThrows: Boolean = false,
  planCheckpointThrows: Boolean = false,
  outputValidator: FeatureTaskRuntimePhaseOutputValidator = FakePhaseOutputValidator(),
  contextDiscovery: GoalPlanningContextDiscovery = fakeContextDiscovery,
  behavior: (phase: String, subtaskId: Int, request: GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
): SweepHarness {
  val fixtures = sharedSweepFixtures(
    markPreparedThrows = markPreparedThrows,
    planCheckpointThrows = planCheckpointThrows,
    outputValidator = outputValidator,
  )
  val launcher = SweepPlanningLauncher(behavior)
  val sweep = DefaultGoalPlanningSweep(
    fixtures.checkpoint,
    fixtures.outputValidator,
    launcher,
    fixtures.invariantsSource,
    fixtures.manifestFileStore,
    contextDiscovery,
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

private class CountingContextDiscovery : GoalPlanningContextDiscovery {
  var calls: Int = 0
    private set

  override fun discover(repoRoot: Path): GoalPlanningContext {
    calls += 1
    return fakeContextDiscovery.discover(repoRoot)
  }
}
