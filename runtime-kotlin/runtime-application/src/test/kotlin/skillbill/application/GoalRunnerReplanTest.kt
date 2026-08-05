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
  fun `scoped replan refuses live unknown terminal absent and unknown-key without mutation`() {
    val base = manifest(subtaskCount = 2).copy(
      status = "in_progress",
      currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
      subtasks = listOf(
        manifest(subtaskCount = 2).subtasks[0].copy(status = "complete", commitSha = "sha-1"),
        manifest(subtaskCount = 2).subtasks[1].copy(status = "pending"),
      ),
    )

    val liveStore = InMemoryGoalManifestStore(base).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
      executionLeaseForTest = idleLease().copy(expiresAt = "2026-07-27T12:00:01Z")
    }
    val liveFailure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(liveStore, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 2))
    }
    assertTrue(liveFailure.message!!.contains("live"), liveFailure.message)
    assertEquals(0, liveStore.scopedReplanCount)
    assertEquals(setOf(1, 2), liveStore.plannedSubtaskIds)

    val unknownStore = InMemoryGoalManifestStore(base).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
      executionLeaseForTest = null
    }
    val unknownFailure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(unknownStore, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 2))
    }
    assertTrue(unknownFailure.message!!.contains("unknown execution liveness"), unknownFailure.message)
    assertEquals(0, unknownStore.scopedReplanCount)

    val terminalStore = InMemoryGoalManifestStore(
      base.copy(
        subtasks = listOf(
          base.subtasks[0],
          base.subtasks[1].copy(status = "complete", commitSha = "sha-2"),
        ),
      ),
    ).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
      seedIdleLease()
    }
    val terminalFailure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(terminalStore, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 2))
    }
    assertTrue(terminalFailure.message!!.contains("reset"), terminalFailure.message)
    assertEquals(0, terminalStore.scopedReplanCount)

    val absentStore = InMemoryGoalManifestStore(base).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
      seedIdleLease()
    }
    val absentFailure = assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(absentStore, RecordingOutcomeStore(), goalTestPhaseRecorder(), clock = idleClock)
        .replan(GoalRunnerReplanRequest("SKILL-56", 9))
    }
    assertTrue(absentFailure.message!!.contains("not part of goal"), absentFailure.message)
    assertEquals(0, absentStore.scopedReplanCount)

    assertNull(
      GoalRunnerStatusService(
        InMemoryGoalManifestStore(base).apply { seedIdleLease() },
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
