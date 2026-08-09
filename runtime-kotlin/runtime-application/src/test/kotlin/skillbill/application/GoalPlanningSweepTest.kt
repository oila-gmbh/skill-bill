package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.goalrunner.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.GoalPlanningAttemptRecorder
import skillbill.application.goalrunner.GoalPlanningRejectionRecorder
import skillbill.application.goalrunner.GoalPlanningSharedContextPacket
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.model.GoalPlanningAttemptRecord
import skillbill.application.model.GoalPlanningBurstSchedule
import skillbill.application.model.GoalPlanningRejectionRecord
import skillbill.application.model.GoalPlanningSweepOutcome
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.model.GoalPlanningContext
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
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
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass") // one suite over the sweep's recovery, gate, and stop paths; they share a harness
class GoalPlanningSweepTest {
  @Test
  fun `migrate projects 0_1 packets with platform_packs to the current version`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val legacy = legacyV01Packet(
      subtasks,
      platformPacks = mapOf(
        "platform-packs/kotlin/platform.yaml" to
          "routing_signals: [kotlin]\ndeclared_code_review_areas: [architecture]\n",
      ),
    )

    val migrated = GoalPlanningSharedContextPacket.migrate(legacy)

    assertEquals(GoalPlanningSharedContextPacket.VERSION, migrated["packet_version"])
    assertFalse(migrated.containsKey("platform_packs"))
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
    assertEquals("0.4", GoalPlanningSharedContextPacket.VERSION)
    assertEquals(
      GoalPlanningSharedContextPacket.discardedCatalog(),
      migrated["boundary_memory"],
      "the 0.1 chain lands on 0.4 with the legacy byte-prefix payload discarded and marked truncated",
    )
  }

  @Test
  fun `migrate discards legacy prefix boundary memory from recovered 0_2 packets`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val legacy = legacyV02Packet(
      subtasks,
      boundaryMemory = mapOf(
        "platform-packs/kmp/agent/history.md" to "pack history",
        "platform-packs/kotlin/agent/decisions.md" to "pack decision",
        "runtime-kotlin/runtime-application/agent/history.md" to "module history",
      ),
    )

    val migrated = GoalPlanningSharedContextPacket.migrate(legacy)

    assertEquals(GoalPlanningSharedContextPacket.VERSION, migrated["packet_version"])
    assertEquals(
      GoalPlanningSharedContextPacket.discardedCatalog(),
      migrated["boundary_memory"],
      "a discarded legacy payload must be distinguishable from a repo with no boundary memory",
    )
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

  @Test
  fun `migrate rejects tampered 0_2 integrity and unsupported versions`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val legacy = legacyV02Packet(subtasks, boundaryMemory = emptyMap())

    val tampered = assertFailsWith<IllegalArgumentException> {
      GoalPlanningSharedContextPacket.migrate(legacy + ("integrity_sha256" to "not-a-real-digest"))
    }
    assertContains(tampered.message.orEmpty(), "integrity is invalid")

    val unsupported = assertFailsWith<IllegalStateException> {
      GoalPlanningSharedContextPacket.migrate(legacy + ("packet_version" to "0.9"))
    }
    assertContains(unsupported.message.orEmpty(), "unsupported")
  }

  @Test
  fun `migrate projects 0_1 packets with empty platform_packs to the current version`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val migrated = GoalPlanningSharedContextPacket.migrate(
      legacyV01Packet(subtasks, platformPacks = emptyMap()),
    )

    assertFalse(migrated.containsKey("platform_packs"))
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

  @Test
  fun `validate rejects raw 0_1 packets without migrate`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val legacy = legacyV01Packet(subtasks, platformPacks = emptyMap())

    val rawFailure = assertFailsWith<IllegalArgumentException> {
      GoalPlanningSharedContextPacket.validate(
        packet = legacy,
        repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
        normalizedIssueKey = "SKILL-172",
        parentSpecPath = ".feature-specs/SKILL-172/spec.md",
        subtasks = subtasks,
      )
    }
    assertTrue(
      rawFailure.message.orEmpty().contains("fields are invalid") ||
        rawFailure.message.orEmpty().contains("version is invalid"),
    )
  }

  @Test
  fun `migrate rejects unknown versions and tampered 0_1 integrity`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val legacy = legacyV01Packet(subtasks, platformPacks = emptyMap())

    val unknownFailure = assertFailsWith<IllegalStateException> {
      GoalPlanningSharedContextPacket.migrate(legacy + ("packet_version" to "0.0"))
    }
    assertContains(unknownFailure.message.orEmpty(), "unsupported")

    val tamperedFailure = assertFailsWith<IllegalArgumentException> {
      GoalPlanningSharedContextPacket.migrate(legacy + ("integrity_sha256" to "not-a-real-digest"))
    }
    assertContains(tamperedFailure.message.orEmpty(), "integrity is invalid")
  }

  @Test
  fun `resume migrates an on-disk 0_1 shared packet without stopping`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      prepared.withSharedPacket { packet ->
        packet +
          ("packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1) +
          ("boundary_memory" to mapOf("runtime-kotlin/agent/history.md" to "legacy prefix excerpt")) +
          (
            "platform_packs" to mapOf(
              "platform-packs/kotlin/platform.yaml" to "routing_signals: [kotlin]\n",
            )
            )
      },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
  }

  @Test
  fun `preplan prompt carries the heading catalog and no entry body`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val preplanPrompt = harness.launcher.requests.first().skillRunRequest.promptOverride.orEmpty()
    assertContains(preplanPrompt, FIXTURE_HEADING_ID)
    assertContains(preplanPrompt, "selected_boundary_headings")
    assertFalse(preplanPrompt.contains(FIXTURE_BODY), "the catalog never carries entry bodies")
  }

  @Test
  fun `a selected heading id delivers only that body to the plan prompt`() {
    val harness = sweepHarness { phase, _, _ ->
      if (phase == "preplan") {
        launchFacts(stdout = preplanPayloadSelecting(FIXTURE_HEADING_ID))
      } else {
        validPhaseOutcome(phase)
      }
    }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val planPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertContains(planPrompt, "## Selected boundary memory")
    assertContains(planPrompt, FIXTURE_BODY)
  }

  @Test
  fun `a preplan without a selection field yields a catalog only plan prompt`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val planPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertContains(planPrompt, FIXTURE_HEADING_ID)
    assertFalse(planPrompt.contains("## Selected boundary memory"))
    assertFalse(planPrompt.contains(FIXTURE_BODY))
  }

  @Test
  fun `a recovered 0_2 packet resumes without any excluded prefix payload reaching the prompt`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      prepared.withSharedPacket { packet ->
        packet +
          ("packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2) +
          (
            "boundary_memory" to mapOf(
              "platform-packs/kmp/agent/history.md" to "pack prefix excerpt",
            )
            )
      },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertFalse(
      harness.launcher.requests.any { request ->
        request.skillRunRequest.promptOverride.orEmpty().contains("pack prefix excerpt")
      },
    )
  }

  @Test
  fun `migrate discards legacy prefix boundary memory from recovered 0_3 packets`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val legacy = legacyV03Packet(
      subtasks,
      boundaryMemory = mapOf("runtime-kotlin/agent/history.md" to "legacy prefix excerpt"),
    )

    val migrated = GoalPlanningSharedContextPacket.migrate(legacy)

    assertEquals(GoalPlanningSharedContextPacket.VERSION, migrated["packet_version"])
    assertEquals(
      GoalPlanningSharedContextPacket.discardedCatalog(),
      migrated["boundary_memory"],
      "a discarded legacy payload must be distinguishable from a repo with no boundary memory",
    )
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

  @Test
  fun `validate rejects a malformed duplicated or oversized 0_4 catalog`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val base = GoalPlanningSharedContextPacket.migrate(legacyV03Packet(subtasks, boundaryMemory = emptyMap()))

    fun rejects(catalog: Map<String, Any?>): String {
      val body = (base - "integrity_sha256") + ("boundary_memory" to catalog)
      val packet = body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
      return assertFailsWith<Exception> {
        GoalPlanningSharedContextPacket.validate(
          packet = packet,
          repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
          normalizedIssueKey = "SKILL-172",
          parentSpecPath = ".feature-specs/SKILL-172/spec.md",
          subtasks = subtasks,
        )
      }.message.orEmpty()
    }

    val entry = linkedMapOf<String, Any?>(
      "heading_id" to FIXTURE_HEADING_ID,
      "source_path" to "runtime-kotlin/agent/history.md",
      "kind" to "history",
      "heading" to FIXTURE_HEADING,
    )
    assertContains(rejects(linkedMapOf("catalog" to listOf(entry - "kind"), "truncated" to false)), "invalid")
    assertContains(
      rejects(linkedMapOf("catalog" to listOf(entry, entry), "truncated" to false)),
      "unique",
    )
    assertContains(
      rejects(
        linkedMapOf(
          "catalog" to (0..GoalPlanningContext.MAX_CATALOG_HEADINGS).map { index ->
            entry + ("heading_id" to "runtime-kotlin/agent/history.md#$index-000000000000")
          },
          "truncated" to false,
        ),
      ),
      "cap",
    )
  }

  // Dropping a newly excluded entry re-signs the packet, so the digest it replaces has to be checked
  // first — otherwise appending one excluded entry launders a tampered checkpoint through migrate.
  @Test
  fun `migrate refuses to re-sign a tampered packet while dropping excluded entries`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val base = GoalPlanningSharedContextPacket.migrate(legacyV03Packet(subtasks, boundaryMemory = emptyMap()))
    val excluded = linkedMapOf<String, Any?>(
      "heading_id" to "platform-packs/kmp/agent/history.md#0-000000000000",
      "source_path" to "platform-packs/kmp/agent/history.md",
      "kind" to "history",
      "heading" to FIXTURE_HEADING,
    )
    val body = (base - "integrity_sha256") +
      ("boundary_memory" to linkedMapOf("catalog" to listOf(excluded), "truncated" to false))
    val signed = body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
    val tampered = signed + ("validation_guidance" to "injected guidance the digest never covered")

    val failure = assertFailsWith<IllegalArgumentException> { GoalPlanningSharedContextPacket.migrate(tampered) }

    assertContains(failure.message.orEmpty(), "integrity is invalid")
  }

  // The exclusion contract is checked in and expected to grow, so a newly excluded entry in an
  // already-durable catalog is dropped on migrate rather than rejected on validate. Rejecting it made
  // the sweep re-derive the same block on every resume, leaving the goal permanently unresumable.
  @Test
  fun `migrate drops newly excluded catalog entries instead of blocking the resume`() {
    val subtasks = listOf(
      DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
    )
    val base = GoalPlanningSharedContextPacket.migrate(legacyV03Packet(subtasks, boundaryMemory = emptyMap()))
    val kept = linkedMapOf<String, Any?>(
      "heading_id" to FIXTURE_HEADING_ID,
      "source_path" to "runtime-kotlin/agent/history.md",
      "kind" to "history",
      "heading" to FIXTURE_HEADING,
    )
    val excluded = kept + mapOf(
      "heading_id" to "platform-packs/kmp/agent/history.md#0-000000000000",
      "source_path" to "platform-packs/kmp/agent/history.md",
    )
    val body = (base - "integrity_sha256") +
      ("boundary_memory" to linkedMapOf("catalog" to listOf(kept, excluded), "truncated" to false))
    val stale = body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))

    val migrated = GoalPlanningSharedContextPacket.migrate(stale)

    val catalog = (migrated["boundary_memory"] as Map<*, *>)["catalog"] as List<*>
    assertEquals(listOf(kept), catalog, "the excluded entry is dropped, the governed one is kept")
    assertEquals(true, (migrated["boundary_memory"] as Map<*, *>)["truncated"])
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

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
  fun `planning launch authorization closes before the child run is awaited`() {
    val authorization = TrackingPlanningAuthorization()
    val openWhileAwaitingChild = mutableListOf<Boolean>()
    val store = AuthorizingGoalPlanningManifestStore(authorization)
    val harness = sweepHarness(manifestStore = store) { phase, _, request ->
      val spawnAuthorization = assertNotNull(
        request.skillRunRequest.spawnAuthorization,
        "a planning launch must carry its authorization into the spawn seam, not wrap the blocking launch",
      )
      spawnAuthorization.withAuthorization { }
      openWhileAwaitingChild += authorization.open
      validPhaseOutcome(phase)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(3, authorization.invocations, "every planning launch is authorized exactly once")
    assertEquals(
      listOf(false, false, false),
      openWhileAwaitingChild,
      "the authorization transaction must be closed while the planning child is awaited",
    )
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
  fun `resume rejects saved planning when the parent spec changes`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited after planning")

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.contains("provenance"))
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume reuses saved planning when only parent spec status frontmatter is removed`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.manifestFileStore.replaceSpec(
      "spec.md",
      "---\nstatus: Pending\n---\n\n# Initial feature contract",
    )
    val prepared = assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(state, harness.request()),
    )
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract")

    val resumed = harness.sweep.prepare(state, harness.request())

    val outcome = assertIs<GoalPlanningSweepOutcome.PreparedAll>(resumed)
    assertEquals(prepared.provenance, outcome.provenance)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume rejects a non-status parent spec frontmatter change`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.manifestFileStore.replaceSpec(
      "spec.md",
      "---\nstatus: Pending\nowner: team-a\n---\n# Initial feature contract",
    )
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec(
      "spec.md",
      "---\nowner: team-b\n---\n# Initial feature contract",
    )

    val resumed = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(resumed)
    assertTrue(stopped.blockedReason.contains("provenance"))
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume rejects saved planning when a governed subtask spec changes`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec_subtask_1.md", "# Initial subtask contract edited after planning")

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("cannot be recovered"))
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume accepts saved planning when a complete subtask's governed spec changed after completion`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 1)
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec_subtask_1.md", "# Subtask contract reconciled after completion")
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
  fun `resume accepts a complete local subtask whose governed spec is deleted`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 2)
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
      // A hard launch failure, not an empty harvest: this case is about resume continuing at the
      // next subtask, and an empty harvest now spends the bounded retry budget before it stops.
      if (subtaskId == 2 && phase == "plan") spawnBlockedOutcome() else validPhaseOutcome(phase)
    }
    val runOne = DefaultGoalPlanningSweep(
      fixtures.checkpoint,
      fixtures.outputValidator,
      runOneLauncher,
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      discovery,
      NoopFeatureTaskRuntimePlanningProjectionValidator,
      manifestStore = NoopGoalPlanningManifestStore,
      boundaryBodyResolver = fakeBoundaryBodyResolver,
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
      NoopFeatureTaskRuntimePlanningProjectionValidator,
      manifestStore = NoopGoalPlanningManifestStore,
      boundaryBodyResolver = fakeBoundaryBodyResolver,
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
  fun `unexpected planning launch failure becomes a resumable stopped outcome`() {
    val attempts = mutableListOf<GoalPlanningAttemptRecord>()
    val harness = sweepHarness(
      planningAttemptRecorder = GoalPlanningAttemptRecorder { attempts += it },
    ) { phase, subtaskId, _ ->
      if (phase == "plan" && subtaskId == 2) {
        error("simulated planning launcher failure")
      }
      validPhaseOutcome(phase)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(2, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertContains(stopped.blockedReason, "simulated planning launcher failure")
    assertEquals(1, harness.preparedCount())
    assertEquals(
      listOf("preplan:SUCCEEDED", "plan:SUCCEEDED", "plan:FAILED"),
      attempts.map {
        "${it.phaseId}:${it.outcome}"
      },
    )
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
  fun `every planning launch streams for liveness and carries its own budget`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val request = harness.request().copy(
      timeout = 5.minutes,
      progressIdleTimeout = 10.minutes,
      planningBudget = 45.minutes,
    )

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), request)

    assertEquals(3, harness.launcher.requests.size)
    harness.launcher.requests.forEach { launch ->
      assertTrue(
        launch.skillRunRequest.streamOutputForLiveness,
        "planning writes no durable progress, so it must prove liveness by streaming output",
      )
      assertEquals(45.minutes, launch.skillRunRequest.timeout, "planning is bounded by its own budget")
      assertEquals(10.minutes, launch.skillRunRequest.progressIdleTimeout, "silence is still bounded")
    }
  }

  @Test
  fun `a disabled planning budget leaves planning bounded only by output silence`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val request = harness.request().copy(progressIdleTimeout = 10.minutes, planningBudget = null)

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), request)

    harness.launcher.requests.forEach { launch ->
      assertNull(launch.skillRunRequest.timeout)
      assertEquals(10.minutes, launch.skillRunRequest.progressIdleTimeout)
      assertTrue(launch.skillRunRequest.streamOutputForLiveness)
    }
  }

  @Test
  fun `an exhausted planning budget names the budget and the flag that raises it`() {
    val harness = sweepHarness { _, _, _ ->
      launchFacts(stdout = "").copy(timedOut = true, exitStatus = null)
    }

    val outcome = harness.sweep.prepare(
      harness.stateFor(manifest(subtaskCount = 1)),
      harness.request().copy(planningBudget = 45.minutes),
    )

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertTrue(stopped.blockedReason.contains("45m"), stopped.blockedReason)
    assertTrue(stopped.blockedReason.contains("--planning-budget-minutes"), stopped.blockedReason)
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
  fun `malformed phase output retries with the schema correction before checkpointing`() {
    var attempts = 0
    val harness = sweepHarness { phase, _, _ ->
      if (phase == "preplan" && attempts++ == 0) {
        launchFacts(stdout = "not a json object")
      } else {
        validPhaseOutcome(phase)
      }
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("preplan", "preplan", "plan"), harness.launcher.phases)
    val retryPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertContains(retryPrompt, "Previous attempt was REJECTED by the schema gate")
    assertContains(retryPrompt, "Goal planning phase output was rejected by its schema contract.")
    assertEquals(1, harness.preparedCount())
  }

  @Test
  fun `malformed phase output stops at the bounded schema retry cap without checkpointing`() {
    val harness = sweepHarness { _, _, _ -> launchFacts(stdout = "not a json object") }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
    assertEquals(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS, harness.launcher.phases.size)
    assertTrue(stopped.blockedReason.contains("no acceptable output"), stopped.blockedReason)
    assertTrue(stopped.blockedReason.contains("Last failure:"), stopped.blockedReason)
  }

  @Test
  fun `empty provider turn on a clean exit is retried under the fix-loop cap instead of blocking at once`() {
    var launches = 0
    val harness = sweepHarness { phase, _, _ ->
      launches += 1
      if (launches == 1) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("preplan", "preplan", "plan"), harness.launcher.phases)
    assertEquals(1, harness.preparedCount())
  }

  @Test
  fun `empty provider turn retry does not tell the agent its prior output was schema-rejected`() {
    var launches = 0
    val harness = sweepHarness { phase, _, _ ->
      launches += 1
      if (launches == 1) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
    }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val retryPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertFalse(
      retryPrompt.contains("Previous attempt was REJECTED by the schema gate"),
      "there is no rejected output to remediate when the provider returned nothing",
    )
  }

  @Test
  fun `exhausted empty provider turns block with launch facts rather than a schema verdict`() {
    val harness = sweepHarness { _, _, _ -> emptyProviderTurnOutcome() }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS, harness.launcher.phases.size)
    assertEquals(0, harness.preparedCount())
    assertContains(stopped.blockedReason, "EmptyProviderTurn")
    assertContains(stopped.blockedReason, "assistantEvents=0")
    assertContains(stopped.blockedReason, "outputTokens=0")
    assertFalse(
      stopped.blockedReason.contains("schema-invalid"),
      "an operator must not be told the schema rejected output that was never produced",
    )
  }

  @Test
  fun `empty provider turns are retained as durable rejection evidence`() {
    val recorded = mutableListOf<GoalPlanningRejectionRecord>()
    val harness = sweepHarness(planningRejectionRecorder = { recorded += it }) { _, _, _ ->
      emptyProviderTurnOutcome()
    }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertEquals(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS, recorded.size)
    assertEquals(listOf(1, 2, 3), recorded.map { it.attempt })
    val first = recorded.first()
    assertEquals("empty-planning-harvest", first.rule)
    assertEquals("preplan", first.phaseId)
    assertEquals("claude", first.agentId)
    assertContains(first.reason, "EmptyProviderTurn")
    assertEquals("{\"type\":\"result\",\"result\":\"\"}", first.rawEvidence)
  }

  @Test
  fun `a non-zero exit still blocks immediately rather than burning the retry budget`() {
    val harness = sweepHarness { _, _, _ ->
      AgentRunLaunchFacts(
        agent = InstallAgent.CLAUDE,
        exitStatus = 2,
        stdout = "",
        stderr = "boom",
        timedOut = false,
        interrupted = false,
        spawnFailed = false,
      )
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, harness.launcher.phases.size)
    assertContains(stopped.blockedReason, "exited with status 2")
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
  fun `a rejected planning projection stops the sweep durably instead of crashing the goal driver`() {
    // The launch seam owns rejection only for a preplan that was already settled under a laxer contract:
    // run one checkpoints it, run two resumes under a validator that refuses it. The producer gate cannot
    // pre-empt that — nothing is produced in run two — so an unhandled throw here would crash the goal
    // driver with no Stopped outcome and crash identically on every resume.
    val fixtures = sharedSweepFixtures()
    val settledPreplan = DefaultGoalPlanningSweep(
      fixtures.checkpoint,
      fixtures.outputValidator,
      SweepPlanningLauncher { phase, _, _ ->
        if (phase == "plan") {
          launchFacts(
            stdout = "",
          )
        } else {
          validPhaseOutcome(phase)
        }
      },
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      fakeContextDiscovery,
      NoopFeatureTaskRuntimePlanningProjectionValidator,
      manifestStore = NoopGoalPlanningManifestStore,
      boundaryBodyResolver = fakeBoundaryBodyResolver,
    )
    assertIs<GoalPlanningSweepOutcome.Stopped>(
      settledPreplan.prepare(fixtures.stateFor(manifest(subtaskCount = 1)), fixtures.request()),
    )
    assertEquals(0, fixtures.preparedCount(), "run one must settle the shared preplan and no plan")

    val launcher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val sweep = DefaultGoalPlanningSweep(
      fixtures.checkpoint,
      fixtures.outputValidator,
      launcher,
      fixtures.invariantsSource,
      fixtures.manifestFileStore,
      fakeContextDiscovery,
      RejectingSweepPlanningProjectionValidator,
      manifestStore = NoopGoalPlanningManifestStore,
      boundaryBodyResolver = fakeBoundaryBodyResolver,
    )

    val outcome = sweep.prepare(fixtures.stateFor(manifest(subtaskCount = 1)), fixtures.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals("plan", stopped.lastResumableStep)
    assertEquals(1, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.contains("rejected a declared bounded projection at the launch seam"))
    assertTrue(
      stopped.blockedReason.contains("Migrate or delete"),
      "the block must name the operator remedy for a non-conforming durable record",
    )
    assertEquals(
      0,
      launcher.requests.size,
      "the settled preplan is not re-produced and the plan edge rejects before launching",
    )
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
      NoopFeatureTaskRuntimePlanningProjectionValidator,
      manifestStore = NoopGoalPlanningManifestStore,
      boundaryBodyResolver = fakeBoundaryBodyResolver,
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
      planningProjectionValidator = NoopFeatureTaskRuntimePlanningProjectionValidator,
    )
    val launcher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val sweep = DefaultGoalPlanningSweep(
      checkpoint,
      outputValidator,
      launcher,
      FakeInvariantsSource(),
      ThrowingManifestFileStore(),
      fakeContextDiscovery,
      NoopFeatureTaskRuntimePlanningProjectionValidator,
      manifestStore = NoopGoalPlanningManifestStore,
      boundaryBodyResolver = fakeBoundaryBodyResolver,
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

  @Test
  fun `a plan child that first emits empty test_obligations relaunches once and checkpoints only the valid plan`() {
    // The SKILL-141 escape, at the goal-side producer: `plan` completed with tasks[].test_obligations
    // empty. The gate rejects it producer-side and the phase re-enters its own bounded fix loop, so the
    // remediation prompt — not the consumer — carries the validation detail.
    var planAttempts = 0
    val harness = sweepHarness(planningProjectionValidator = realPlanningProjectionValidator) { phase, _, _ ->
      if (phase != "plan") {
        validPhaseOutcome(phase)
      } else {
        planAttempts += 1
        if (planAttempts == 1) launchFacts(stdout = emptyTestObligationsPlanPayload()) else validPhaseOutcome(phase)
      }
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(2, planAttempts, "the invalid plan must relaunch exactly once before the valid one settles")
    val retryPrompt = harness.launcher.requests.last().skillRunRequest.promptOverride.orEmpty()
    assertTrue(
      retryPrompt.contains("test_obligations"),
      "the relaunch prompt must carry the projection validation detail as priorSchemaFailure",
    )
    val record = assertNotNull(harness.recordFor(1))
    assertFalse(
      record.planPayload.contains(""""test_obligations":[]"""),
      "only the projection-valid plan may be checkpointed",
    )
  }

  @Test
  fun `planning projection retries are recorded with durable attempt outcomes`() {
    val attempts = mutableListOf<String>()
    var launchCount = 0
    val harness = sweepHarness(
      planningProjectionValidator = realPlanningProjectionValidator,
      planningAttemptRecorder = GoalPlanningAttemptRecorder { record ->
        attempts += "${record.phaseId}:${record.subtaskId}:${record.attempt}:${record.outcome.wireValue}"
      },
    ) { phase, _, _ ->
      launchCount += 1
      if (phase == "plan" && launchCount == 2) {
        launchFacts(stdout = emptyTestObligationsPlanPayload())
      } else {
        validPhaseOutcome(phase)
      }
    }

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
    )
    assertEquals(
      listOf(
        "preplan:0:1:succeeded",
        "plan:1:1:failed",
        "plan:1:2:succeeded",
      ),
      attempts,
    )
  }

  @Test
  fun `a plan child that never emits a valid projection stops at the fix-loop cap with nothing checkpointed`() {
    var planAttempts = 0
    val harness = sweepHarness(planningProjectionValidator = realPlanningProjectionValidator) { phase, _, _ ->
      if (phase != "plan") {
        validPhaseOutcome(phase)
      } else {
        planAttempts += 1
        launchFacts(stdout = emptyTestObligationsPlanPayload())
      }
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS, planAttempts)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("test_obligations"), stopped.blockedReason)
    assertEquals(0, harness.preparedCount(), "no subtask plan may be checkpointed in the failing state")
    assertNull(harness.recordFor(1))
  }

  @Test
  fun `the preplan gate observes the enriched payload that is actually checkpointed`() {
    // Enrichment, not raw child stdout, produces the checkpointed bytes. A rejecting validator must
    // therefore be reached through the enriched payload, before checkpointSharedPreplan runs.
    val harness = sweepHarness(planningProjectionValidator = RejectingSweepPlanningProjectionValidator) { phase, _, _ ->
      validPhaseOutcome(phase)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
    assertEquals(
      FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      harness.launcher.phases.count { it == "preplan" },
      "the preplan fix loop reuses the one runtime cap",
    )
  }

  @Test
  fun `preplan repair evidence survives enrichment and checkpoint persistence`() {
    val evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = FeatureTaskRuntimePhaseOutputFormat.JSON,
      originalDigest = sha256HexUtf8(RAW_REPAIRED_PREPLAN),
      repairedDigest = sha256HexUtf8(REPAIRED_PREPLAN_PAYLOAD),
      operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("preplan", 0, 1, 1),
    )
    val harness = sweepHarness(outputValidator = RepairingPreplanOutputValidator(evidence)) { phase, _, _ ->
      if (phase == "preplan") launchFacts(stdout = RAW_REPAIRED_PREPLAN) else validPhaseOutcome(phase)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val shared = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertEquals(evidence, shared.repairEvidence)
    assertNotNull(harness.recordFor(1), "the repaired shared preplan must reach the plan checkpoint")
  }

  @Test
  fun `inter-plan pace waits only between consecutive plan launches`() {
    val pace = 20.seconds
    val events = mutableListOf<String>()
    val timing = RecordingRuntimeTimingPort { events += "wait" }
    var planOrdinal = 0
    val harness = sweepHarness(
      timingPort = timing,
      // One wait() call per logical pace gap so ordinals stay readable.
      burstSchedule = GoalPlanningBurstSchedule(planLaunchPace = pace, waitSlice = pace),
    ) { phase, subtaskId, _ ->
      if (phase == "plan") {
        planOrdinal += 1
        events += "plan-$planOrdinal:$subtaskId"
      }
      validPhaseOutcome(phase)
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 3)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(
      listOf("plan-1:1", "wait", "plan-2:2", "wait", "plan-3:3"),
      events,
      "pace applies only between plan launches — never before the first or after the last",
    )
    assertEquals(listOf(pace, pace), timing.waits)
  }

  @Test
  fun `empty provider turn backoff waits grow before attempts two and three`() {
    val timing = RecordingRuntimeTimingPort()
    val schedule = GoalPlanningBurstSchedule(
      emptyTurnBackoffBase = 30.seconds,
      emptyTurnBackoffFactor = 2,
      waitSlice = 60.seconds,
    )
    val harness = sweepHarness(timingPort = timing, burstSchedule = schedule) { _, _, _ ->
      emptyProviderTurnOutcome()
    }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS, harness.launcher.phases.size)
    assertEquals(listOf(30.seconds, 60.seconds), timing.waits)
    assertContains(stopped.blockedReason, "EmptyProviderTurn")
  }

  @Test
  fun `a pause requested mid-wait stops the sweep without launching further`() {
    val pauseStore = MutablePauseGoalPlanningManifestStore()
    val timing = RecordingRuntimeTimingPort { pauseStore.pauseRequested = true }
    val harness = sweepHarness(
      manifestStore = pauseStore,
      timingPort = timing,
      burstSchedule = GoalPlanningBurstSchedule(planLaunchPace = 20.seconds, waitSlice = 1.seconds),
    ) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(GoalRunnerStopReason.PAUSED, stopped.reason)
    assertEquals(listOf("preplan", "plan"), harness.launcher.phases)
    assertTrue(timing.waits.isNotEmpty())
  }

  @Test
  fun `an interrupt during wait stops with the launch-interrupt terminal shape`() {
    val timing = RecordingRuntimeTimingPort(result = RuntimeWaitResult.INTERRUPTED)
    val harness = sweepHarness(
      timingPort = timing,
      burstSchedule = GoalPlanningBurstSchedule(planLaunchPace = 20.seconds, waitSlice = 20.seconds),
    ) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.reason)
    assertContains(stopped.blockedReason, "interrupted")
    assertFalse(
      stopped.blockedReason.contains("failed before its output could be checkpointed"),
      "a wait interrupt must not be laundered as unexpectedPlanningFailure",
    )
    assertEquals(listOf("preplan", "plan"), harness.launcher.phases)
  }
}

private fun emptyTestObligationsPlanPayload(): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"plan",""" +
    """"status":"completed","summary":"s","produced_outputs":""" +
    """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[{""" +
    """"task_id":"task-1","description":"Fixture task.","criterion_refs":["AC-001"],""" +
    """"test_obligations":[]}],"validation_strategy":["Focused runtime tests."]}}"""

private const val RAW_REPAIRED_PREPLAN = "raw preplan repaired before enrichment"

private const val REPAIRED_PREPLAN_PAYLOAD =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"preplan",""" +
    """"status":"completed","summary":"s","produced_outputs":{"projection_kind":"preplanning_digest",""" +
    """"contract_version":"0.1","affected_boundaries":["runtime-application/workflow"],""" +
    """"risks":["fixture risk"],"rollout":{"flag_required":false,"notes":"fixture"},""" +
    """"validation_strategy":["Focused runtime tests"]}}"""

private class RepairingPreplanOutputValidator(
  private val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence,
) : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult {
    if (sourceLabel == "preplan" && phaseOutputText == RAW_REPAIRED_PREPLAN) {
      return FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(
        normalizedOutput = normalizedPlanningOutput(REPAIRED_PREPLAN_PAYLOAD),
        evidence = evidence,
      )
    }
    return FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(
      normalizedPlanningOutput(phaseOutputText),
    )
  }

  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    validatePhaseOutput(phaseOutputText, sourceLabel)
  }
}

private fun legacyV01Packet(
  subtasks: List<DecompositionSubtask>,
  platformPacks: Map<String, String>,
): Map<String, Any?> {
  val body = linkedMapOf<String, Any?>(
    "packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1,
    "repository_identity" to "repo-root-realpath-v1:/tmp/fixture",
    "normalized_issue_key" to "SKILL-172",
    "parent_spec_path" to ".feature-specs/SKILL-172/spec.md",
    "parent_spec" to "parent body",
    "decomposition_manifest" to "contract_version: \"0.1\"\nissue_key: SKILL-172\n",
    "platform_packs" to platformPacks,
    "boundary_memory" to mapOf(
      "platform-packs/kotlin/agent/history.md" to "prior decision",
    ),
    "validation_guidance" to "repo conventions",
    "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(subtasks),
  )
  return body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
}

private fun legacyV02Packet(
  subtasks: List<DecompositionSubtask>,
  boundaryMemory: Map<String, String>,
): Map<String, Any?> {
  val body = linkedMapOf<String, Any?>(
    "packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2,
    "repository_identity" to "repo-root-realpath-v1:/tmp/fixture",
    "normalized_issue_key" to "SKILL-172",
    "parent_spec_path" to ".feature-specs/SKILL-172/spec.md",
    "parent_spec" to "parent body",
    "decomposition_manifest" to "contract_version: \"0.1\"\nissue_key: SKILL-172\n",
    "boundary_memory" to boundaryMemory,
    "validation_guidance" to "repo conventions",
    "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(subtasks),
  )
  return body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
}

private fun legacyV03Packet(
  subtasks: List<DecompositionSubtask>,
  boundaryMemory: Map<String, String>,
): Map<String, Any?> {
  val body = linkedMapOf<String, Any?>(
    "packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3,
    "repository_identity" to "repo-root-realpath-v1:/tmp/fixture",
    "normalized_issue_key" to "SKILL-172",
    "parent_spec_path" to ".feature-specs/SKILL-172/spec.md",
    "parent_spec" to "parent body",
    "decomposition_manifest" to "contract_version: \"0.1\"\nissue_key: SKILL-172\n",
    "boundary_memory" to boundaryMemory,
    "validation_guidance" to "repo conventions",
    "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(subtasks),
  )
  return body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
}

private fun normalizedPlanningOutput(payload: String): NormalizedFeatureTaskRuntimePhaseOutput {
  val envelope = JsonSupport.parseObjectOrNull(payload)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: error("fixture phase output is not an object")
  return NormalizedFeatureTaskRuntimePhaseOutput(
    canonicalJson = JsonSupport.mapToJsonString(envelope),
    envelope = envelope,
  )
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

/** A provider turn that charged input, emitted no assistant event, and still exited zero. */
private fun emptyProviderTurnOutcome(): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = 0,
  stdout = "",
  stderr = "",
  timedOut = false,
  interrupted = false,
  spawnFailed = false,
  inputTokens = 33110,
  outputTokens = 0,
  assistantEventCount = 0,
  rawOutputPreview = "{\"type\":\"result\",\"result\":\"\"}",
)

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
    """"status":"completed","summary":"s","produced_outputs":""" +
    (PlanningProjectionFixtures.producedOutputsOrNull(phaseId) ?: """{"result":"$phaseId"}""") + "}"

private fun preplanPayloadSelecting(vararg headingIds: String): String {
  val ids = headingIds.joinToString(",") { id -> "\"" + id + "\"" }
  val digest = PlanningProjectionFixtures.PREPLAN_DIGEST.dropLast(1) +
    ""","selected_boundary_headings":[""" + ids + "]}"
  return """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"preplan",""" +
    """"status":"completed","summary":"s","produced_outputs":""" + digest + "}"
}

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

  override fun deleteIfExists(target: Path): Unit =
    error("CountingManifestFileStore is read-only in goal planning sweep tests.")

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

  override fun deleteIfExists(target: Path): Unit =
    error("ThrowingManifestFileStore is read-only in goal planning sweep tests.")

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

  override fun replaceSharedPreplan(
    checkpoint: skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
  ) {
    sharedPreplan = checkpoint
  }

  override fun replaceSubtaskPlan(checkpoint: skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint) {
    plans[checkpoint.subtaskId] = checkpoint
  }

  override fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: Int): Int {
    val removed = plans.remove(subtaskId) != null
    records.remove(subtaskId)
    return if (removed) 1 else 0
  }

  override fun deleteSharedPreplan(
    identity: skillbill.ports.persistence.model.GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int {
    val shared = sharedPreplan
    if (shared == null ||
      shared.identity.parentGoalWorkflowId != identity.parentGoalWorkflowId ||
      shared.payloadSha256 != expectedPayloadSha256
    ) {
      throw skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        0,
        "shared preplan changed after it was observed for discard",
      )
    }
    sharedPreplan = null
    plans.clear()
    records.clear()
    return 1
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
  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

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
    planningProjectionValidator = NoopFeatureTaskRuntimePlanningProjectionValidator,
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

@Suppress("LongParameterList") // one defaulted knob per sweep collaborator a case varies
private fun sweepHarness(
  markPreparedThrows: Boolean = false,
  planCheckpointThrows: Boolean = false,
  outputValidator: FeatureTaskRuntimePhaseOutputValidator = FakePhaseOutputValidator(),
  contextDiscovery: GoalPlanningContextDiscovery = fakeContextDiscovery,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
    NoopFeatureTaskRuntimePlanningProjectionValidator,
  planningAttemptRecorder: GoalPlanningAttemptRecorder = GoalPlanningAttemptRecorder.NONE,
  manifestStore: GoalRunnerManifestStore = NoopGoalPlanningManifestStore,
  planningRejectionRecorder: GoalPlanningRejectionRecorder = GoalPlanningRejectionRecorder.NONE,
  timingPort: RuntimeTimingPort = NoopRuntimeTimingPort,
  burstSchedule: GoalPlanningBurstSchedule = GoalPlanningBurstSchedule(),
  boundaryBodyResolver: GoalPlanningBoundaryBodyResolver = fakeBoundaryBodyResolver,
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
    planningProjectionValidator,
    planningAttemptRecorder,
    manifestStore = manifestStore,
    planningRejectionRecorder = planningRejectionRecorder,
    timingPort = timingPort,
    burstSchedule = burstSchedule,
    boundaryBodyResolver = boundaryBodyResolver,
  )
  return SweepHarness(fixtures, launcher, sweep)
}

private class RecordingRuntimeTimingPort(
  private val result: RuntimeWaitResult = RuntimeWaitResult.COMPLETED,
  private val onWait: (() -> Unit)? = null,
) : RuntimeTimingPort {
  val waits = mutableListOf<Duration>()

  override fun wait(duration: Duration): RuntimeWaitResult {
    waits += duration
    onWait?.invoke()
    return result
  }
}

private class MutablePauseGoalPlanningManifestStore : GoalRunnerManifestStore {
  var pauseRequested: Boolean = false

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    null

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    GoalRunnerControlState(pauseRequested = pauseRequested)

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = true
}

private object RejectingSweepPlanningProjectionValidator : FeatureTaskRuntimePlanningProjectionValidator {
  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String): Unit =
    throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = sourceLabel,
      reason = "additionalProperties: legacy shared preplan carries an undeclared field",
    )
}

internal const val FIXTURE_HEADING_ID = "runtime-kotlin/agent/history.md#0-000000000000"
internal const val FIXTURE_HEADING = "## [2026-08-01] fixture-entry"
internal const val FIXTURE_BODY = "distinctive fixture body sentence"

private val fakeContextDiscovery = GoalPlanningContextDiscovery {
  GoalPlanningContext(
    boundaryCatalog = listOf(
      GoalPlanningBoundaryHeading(
        headingId = FIXTURE_HEADING_ID,
        sourcePath = "runtime-kotlin/agent/history.md",
        kind = GoalPlanningContext.KIND_HISTORY,
        heading = FIXTURE_HEADING,
      ),
    ),
    boundaryCatalogTruncated = false,
    validationGuidance = "Run focused Gradle checks.",
  )
}

private val fakeBoundaryBodyResolver = GoalPlanningBoundaryBodyResolver { _, ids, _ ->
  GoalPlanningResolvedBoundaryBodies(
    bodies = ids.filter { id -> id == FIXTURE_HEADING_ID }.map { id ->
      GoalPlanningBoundaryBody(id, "runtime-kotlin/agent/history.md", FIXTURE_HEADING, FIXTURE_BODY)
    },
    unresolvedHeadingIds = ids.filterNot { id -> id == FIXTURE_HEADING_ID },
  )
}

private object NoopGoalPlanningManifestStore : GoalRunnerManifestStore {
  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    null

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = true
}

private class TrackingPlanningAuthorization : AgentRunSpawnAuthorization {
  var invocations: Int = 0
    private set
  var open: Boolean = false
    private set

  override fun <T> withAuthorization(spawn: () -> T): T {
    invocations += 1
    open = true
    return try {
      spawn()
    } finally {
      open = false
    }
  }
}

private class AuthorizingGoalPlanningManifestStore(
  private val authorization: AgentRunSpawnAuthorization,
) : GoalRunnerManifestStore {
  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    null

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = true

  override fun authorizePlanningLaunch(parentWorkflowId: String, dbPathOverride: String?): AgentRunSpawnAuthorization =
    authorization
}

private class CountingContextDiscovery : GoalPlanningContextDiscovery {
  var calls: Int = 0
    private set

  override fun discover(repoRoot: Path): GoalPlanningContext {
    calls += 1
    return fakeContextDiscovery.discover(repoRoot)
  }
}
