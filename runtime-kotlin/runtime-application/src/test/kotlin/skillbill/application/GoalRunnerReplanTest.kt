package skillbill.application

import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.model.GoalRunnerReplanRequest
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.workflow.model.CurrentSubtaskIntent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerReplanTest {
  private val idleClock: Clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC)

  @Test
  fun `scoped replan deletes only the target plan and retargets intent`() {
    val original = manifest(subtaskCount = 3).copy(
      status = "in_progress",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 3, action = "start"),
      subtasks = manifest(subtaskCount = 3).subtasks.map { subtask ->
        when (subtask.id) {
          1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
          2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
          else -> subtask.copy(status = "pending")
        }
      },
    )
    val store = InMemoryGoalManifestStore(original).apply {
      plannedSubtaskIds = mutableSetOf(1, 2, 3)
      sharedPreplanPrepared = true
      persistOutOfBandAcceptance(
        "wfl-parent",
        GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z"),
        null,
      )
      seedIdleLease()
    }
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)

    val result = requireNotNull(
      service.replan(GoalRunnerReplanRequest(issueKey = "SKILL-56", subtaskId = 3)),
    )

    assertTrue(result.discardedPlan)
    assertEquals(listOf(1, 2, 3), result.before.plannedSubtaskIds)
    assertEquals(listOf(1, 2), result.after.plannedSubtaskIds)
    assertTrue(result.after.sharedPreplanPrepared)
    assertEquals(CurrentSubtaskIntent(subtaskId = 3, action = "start"), store.manifest.currentSubtaskIntent)
    assertEquals(original.subtasks, store.manifest.subtasks)
    assertEquals(
      mapOf(1 to GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z")),
      store.acceptances,
    )
    assertEquals(1, store.scopedReplanCount)
    assertEquals(setOf(1, 2), store.plannedSubtaskIds.toSet())
    assertEquals(3, listOf(1, 2, 3).first { it !in store.plannedSubtaskIds })
  }

  @Test
  fun `scoped replan resumes when the target already owns a child workflow`() {
    val store = InMemoryGoalManifestStore(
      manifest(subtaskCount = 2).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
        subtasks = listOf(
          manifest(subtaskCount = 2).subtasks[0].copy(status = "complete", commitSha = "sha-1"),
          manifest(subtaskCount = 2).subtasks[1].copy(
            status = "in_progress",
            workflowId = "wfl-2",
            lastResumableStep = "implement",
          ),
        ),
      ),
    ).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
      seedIdleLease()
    }
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)

    service.replan(GoalRunnerReplanRequest("SKILL-56", subtaskId = 2))

    assertEquals(CurrentSubtaskIntent(2, "resume"), store.manifest.currentSubtaskIntent)
    assertEquals("wfl-2", store.manifest.subtasks.last().workflowId)
    assertEquals("implement", store.manifest.subtasks.last().lastResumableStep)
  }

  @Test
  fun `scoped replan refuses live goals without mutation`() {
    val store = refusalBaseStore().apply {
      executionLeaseForTest = idleLease().copy(expiresAt = "2026-07-27T12:00:01Z")
    }
    val failure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 2))
    }
    assertTrue(failure.message!!.contains("live"), failure.message)
    assertEquals(0, store.scopedReplanCount)
    assertEquals(setOf(1, 2), store.plannedSubtaskIds)
  }

  @Test
  fun `scoped replan refuses unknown liveness without mutation`() {
    val store = refusalBaseStore().apply { executionLeaseForTest = null }
    val failure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 2))
    }
    assertTrue(failure.message!!.contains("unknown execution liveness"), failure.message)
    assertEquals(0, store.scopedReplanCount)
  }

  @Test
  fun `scoped replan refuses terminal targets naming reset without mutation`() {
    val store = refusalBaseStore(
      base = refusalBaseManifest().copy(
        subtasks = listOf(
          refusalBaseManifest().subtasks[0],
          refusalBaseManifest().subtasks[1].copy(status = "complete", commitSha = "sha-2"),
        ),
      ),
    ).apply { seedIdleLease() }
    val failure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 2))
    }
    assertTrue(failure.message!!.contains("reset"), failure.message)
    assertEquals(0, store.scopedReplanCount)
  }

  @Test
  fun `scoped replan refuses absent subtask without mutation`() {
    val store = refusalBaseStore().apply { seedIdleLease() }
    val failure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 9))
    }
    assertTrue(failure.message!!.contains("not part of goal"), failure.message)
    assertEquals(0, store.scopedReplanCount)
  }

  @Test
  fun `scoped replan returns null for unknown issue key without mutation`() {
    assertNull(
      GoalRunnerStatusService(
        refusalBaseStore().apply { seedIdleLease() },
        RecordingOutcomeStore(),
        goalTestPhaseRecorder(),
        clock = idleClock,
      ).replan(GoalRunnerReplanRequest("SKILL-999", 1)),
    )
  }

  @Test
  fun `non-positive subtask id is rejected at the request boundary`() {
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerReplanRequest(issueKey = "SKILL-56", subtaskId = 0)
    }
  }

  @Test
  fun `acceptances survive scoped replan`() {
    val store = InMemoryGoalManifestStore(
      manifest(subtaskCount = 2).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
        subtasks = listOf(
          manifest(subtaskCount = 2).subtasks[0].copy(status = "complete", commitSha = "abc1234"),
          manifest(subtaskCount = 2).subtasks[1].copy(status = "pending"),
        ),
      ),
    ).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
      persistOutOfBandAcceptance(
        "wfl-parent",
        GoalRunnerOutOfBandAcceptance(1, "abc1234", "shipped by hand", "2026-07-27T11:00:00Z"),
        null,
      )
      seedIdleLease()
    }
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)

    service.replan(GoalRunnerReplanRequest("SKILL-56", 2))

    assertEquals(
      mapOf(1 to GoalRunnerOutOfBandAcceptance(1, "abc1234", "shipped by hand", "2026-07-27T11:00:00Z")),
      store.acceptances,
    )
    assertEquals("complete", store.manifest.subtasks.first().status)
    assertEquals("abc1234", store.manifest.subtasks.first().commitSha)
  }

  @Test
  fun `include-shared-preplan cascades every sibling plan and discards shared`() {
    val original = manifest(subtaskCount = 3).copy(
      status = "in_progress",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 3, action = "start"),
      subtasks = manifest(subtaskCount = 3).subtasks.map { subtask ->
        when (subtask.id) {
          1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
          2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
          else -> subtask.copy(status = "pending")
        }
      },
    )
    val store = InMemoryGoalManifestStore(original).apply {
      plannedSubtaskIds = mutableSetOf(1, 2, 3)
      sharedPreplanPrepared = true
      persistOutOfBandAcceptance(
        "wfl-parent",
        GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z"),
        null,
      )
      seedIdleLease()
    }
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)

    val result = requireNotNull(
      service.replan(
        GoalRunnerReplanRequest(issueKey = "SKILL-56", subtaskId = 3, includeSharedPreplan = true),
      ),
    )

    assertTrue(result.discardedPlan)
    assertTrue(result.discardedSharedPreplan)
    assertEquals(listOf(1, 2), result.cascadedPlanSubtaskIds)
    assertEquals(listOf(1, 2, 3), result.before.plannedSubtaskIds)
    assertEquals(emptyList(), result.after.plannedSubtaskIds)
    assertTrue(result.before.sharedPreplanPrepared)
    assertTrue(!result.after.sharedPreplanPrepared)
    assertEquals(emptySet(), store.plannedSubtaskIds.toSet())
    assertTrue(!store.sharedPreplanPrepared)
    assertEquals(true, store.lastIncludeSharedPreplan)
    assertEquals(original.subtasks, store.manifest.subtasks)
    assertEquals(
      mapOf(1 to GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z")),
      store.acceptances,
    )
  }

  @Test
  fun `omitting include-shared-preplan leaves shared preplan and sibling plans`() {
    val store = InMemoryGoalManifestStore(
      manifest(subtaskCount = 3).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(3, "start"),
        subtasks = manifest(subtaskCount = 3).subtasks.map { subtask ->
          when (subtask.id) {
            1 -> subtask.copy(status = "complete", commitSha = "sha-1")
            2 -> subtask.copy(status = "complete", commitSha = "sha-2")
            else -> subtask.copy(status = "pending")
          }
        },
      ),
    ).apply {
      plannedSubtaskIds = mutableSetOf(1, 2, 3)
      sharedPreplanPrepared = true
      seedIdleLease()
    }
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)

    val result = requireNotNull(service.replan(GoalRunnerReplanRequest("SKILL-56", 3)))

    assertEquals(false, store.lastIncludeSharedPreplan)
    assertTrue(result.after.sharedPreplanPrepared)
    assertEquals(listOf(1, 2), result.after.plannedSubtaskIds)
    assertEquals(emptyList(), result.cascadedPlanSubtaskIds)
    assertTrue(!result.discardedSharedPreplan)
    assertEquals(setOf(1, 2), store.plannedSubtaskIds.toSet())
  }

  @Test
  fun `include-shared-preplan refuses when shared digest moved with zero mutation`() {
    val store = refusalBaseStore(
      base = manifest(subtaskCount = 3).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(3, "start"),
        subtasks = manifest(subtaskCount = 3).subtasks.map { subtask ->
          when (subtask.id) {
            1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
            2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
            else -> subtask.copy(status = "pending")
          }
        },
      ),
    ).apply {
      plannedSubtaskIds = mutableSetOf(1, 2, 3)
      sharedPreplanPrepared = true
      forceSharedDigestMismatchOnReplan = true
      seedIdleLease()
    }
    val failure = assertFailsWith<skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError> {
      GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 3, includeSharedPreplan = true))
    }
    assertTrue(failure.message!!.contains("shared preplan changed"), failure.message)
    assertEquals(1, store.scopedReplanCount)
    assertEquals(setOf(1, 2, 3), store.plannedSubtaskIds.toSet())
    assertTrue(store.sharedPreplanPrepared)
  }

  @Test
  fun `status after include-shared-preplan reports shared_preplan false with regeneration pending`() {
    val store = InMemoryGoalManifestStore(
      manifest(subtaskCount = 3).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(3, "start"),
        subtasks = manifest(subtaskCount = 3).subtasks.map { subtask ->
          when (subtask.id) {
            1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
            2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
            else -> subtask.copy(status = "pending")
          }
        },
      ),
    ).apply {
      plannedSubtaskIds = mutableSetOf(1, 2, 3)
      sharedPreplanPrepared = true
      seedIdleLease()
    }
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
    service.replan(GoalRunnerReplanRequest("SKILL-56", 3, includeSharedPreplan = true))

    val status = requireNotNull(
      service.status(
        skillbill.application.model.GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      ),
    )
    val planning = requireNotNull(status.planning)
    assertTrue(!planning.sharedPreplanPrepared)
    assertEquals(skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED, planning.state)
    assertTrue(
      planning.reason!!.contains("not started") || planning.reason!!.contains("preplan"),
      planning.reason,
    )
    assertEquals("complete", store.manifest.subtasks[0].status)
    assertEquals("sha-1", store.manifest.subtasks[0].commitSha)
    assertEquals("wfl-1", store.manifest.subtasks[0].workflowId)
    assertEquals("complete", store.manifest.subtasks[1].status)
    assertEquals("pending", store.manifest.subtasks[2].status)
  }

  private fun refusalBaseManifest() = manifest(subtaskCount = 2).copy(
    status = "in_progress",
    currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
    subtasks = listOf(
      manifest(subtaskCount = 2).subtasks[0].copy(status = "complete", commitSha = "sha-1"),
      manifest(subtaskCount = 2).subtasks[1].copy(status = "pending"),
    ),
  )

  private fun refusalBaseStore(
    base: skillbill.workflow.model.DecompositionManifest = refusalBaseManifest(),
  ): InMemoryGoalManifestStore = InMemoryGoalManifestStore(base).apply {
    plannedSubtaskIds = mutableSetOf(1, 2)
  }

  private fun InMemoryGoalManifestStore.seedIdleLease() {
    executionLeaseForTest = idleLease()
  }

  private fun idleLease(): GoalRunnerExecutionLease = GoalRunnerExecutionLease(
    generation = 1,
    ownerToken = "parent-owner",
    hostIdentity = "host",
    bootIdentity = "boot",
    pid = 42,
    processBirthToken = "birth-42",
    heartbeatAt = "2026-07-27T11:59:50Z",
    expiresAt = "2026-07-27T11:59:59Z",
  )
}
