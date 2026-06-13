package skillbill.db

import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.infrastructure.sqlite.review.InvalidGoalTelemetryRowError
import skillbill.infrastructure.sqlite.review.ReviewStatsRuntime
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalTelemetryStoreTest {
  @Test
  fun `empty store reports zero runs and no most-recent run`() {
    withConnection { connection ->
      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(0, stats.totalRuns)
      assertEquals(0, stats.finishedRuns)
      assertEquals(0, stats.inProgressRuns)
      assertEquals(0, stats.totalSubtaskEvents)
      assertEquals(mapOf("complete" to 0, "blocked" to 0, "skipped" to 0), stats.subtaskOutcomeCounts)
      assertEquals(mapOf("completed" to 0, "blocked" to 0), stats.completionStatusCounts)
      assertEquals(0.0, stats.averageRunDurationMs)
      assertNull(stats.mostRecentRun)
    }
  }

  @Test
  fun `write read and aggregate a full blocked run with complete blocked and skipped subtasks`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection)
      seedBlockedRunWithMixedSubtasks(store)

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.finishedRuns)
      assertEquals(0, stats.inProgressRuns)
      assertEquals(0, stats.completedRuns)
      assertEquals(1, stats.blockedRuns)
      assertEquals(1.0, stats.blockedRate)
      assertEquals(mapOf("completed" to 0, "blocked" to 1), stats.completionStatusCounts)
      assertEquals(mapOf("complete" to 1, "blocked" to 1, "skipped" to 1), stats.subtaskOutcomeCounts)
      assertEquals(3, stats.totalSubtaskEvents)
      assertEquals(1_800_000.0, stats.averageRunDurationMs)
      assertEquals(60_000.0, stats.averageSubtaskDurationMs)
      assertEquals(1.0, stats.averageAttemptCount)

      val mostRecent = requireNotNull(stats.mostRecentRun)
      assertEquals("wf-1", mostRecent.workflowId)
      assertEquals("SKILL-66", mostRecent.issueKey)
      assertEquals("blocked", mostRecent.status)
      assertEquals(1_800_000L, mostRecent.durationMs)
      assertEquals(3, mostRecent.subtaskTotal)

      assertOutboxEmittedOncePerEvent(connection)

      assertEquals(1, stats.topBlockedSubtasks.size)
      val blocked = stats.topBlockedSubtasks.single()
      assertEquals(2, blocked.subtaskId)
      assertEquals("validation failed", blocked.blockedReason)
      assertEquals(2, blocked.attemptCount)
    }
  }

  @Test
  fun `top blocked subtasks is empty when no subtasks are blocked`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection)
      finishedRun(store, "wf-a", startedAt = "2026-06-04T08:00:00Z", status = "completed", durationMs = 100_000)
      finishedRun(store, "wf-b", startedAt = "2026-06-04T09:00:00Z", status = "completed", durationMs = 200_000)

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertTrue(stats.topBlockedSubtasks.isEmpty())
    }
  }

  @Test
  fun `top blocked subtasks lists all blocked entries across runs`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection)

      store.goalStarted(startedRecord("wf-x", subtaskTotal = 1, resumed = false), level = "full")
      store.goalSubtaskFinished(
        subtask(id = 1, status = "blocked", durationMs = 60_000, attempts = 1, blockedReason = "test failure"),
        "full",
      )
      store.goalFinished(
        GoalFinishedRecord(
          issueKey = "SKILL-66",
          workflowId = "wf-x",
          status = "blocked",
          startedAt = "2026-06-04T10:00:00Z",
          finishedAt = "2026-06-04T10:01:00Z",
          durationMs = 60_000,
          subtasksComplete = 0,
          subtasksBlocked = 1,
          subtasksSkipped = 0,
        ),
        level = "full",
      )

      store.goalStarted(startedRecord("wf-y", subtaskTotal = 1, resumed = false), level = "full")
      store.goalSubtaskFinished(
        GoalSubtaskFinishedRecord(
          issueKey = "SKILL-77",
          workflowId = "wf-y",
          subtaskId = 3,
          subtaskName = "subtask-3",
          status = "blocked",
          startedAt = "2026-06-04T11:00:00Z",
          finishedAt = "2026-06-04T11:02:00Z",
          durationMs = 120_000,
          attemptCount = 2,
          blockedReason = "compile error",
        ),
        "full",
      )

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(2, stats.topBlockedSubtasks.size)
    }
  }

  @Test
  fun `most-recent run lookup picks the latest started completed run`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection)

      finishedRun(store, "wf-early", startedAt = "2026-06-04T08:00:00Z", status = "completed", durationMs = 100_000)
      finishedRun(store, "wf-late", startedAt = "2026-06-04T20:00:00Z", status = "completed", durationMs = 300_000)

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(2, stats.totalRuns)
      assertEquals(2, stats.completedRuns)
      assertEquals(1.0, stats.completedRate)
      assertEquals(200_000.0, stats.averageRunDurationMs)
      assertEquals("wf-late", requireNotNull(stats.mostRecentRun).workflowId)
    }
  }

  @Test
  fun `resumed duplicate subtask event is not double counted or re-emitted`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection)
      val record = subtask(id = 1, status = "complete", durationMs = 60_000, attempts = 1)

      store.goalSubtaskFinished(record, "full")
      store.goalSubtaskFinished(record, "full")

      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalSubtaskEvents)
      assertEquals(mapOf("complete" to 1, "blocked" to 0, "skipped" to 0), stats.subtaskOutcomeCounts)

      val outbox = pendingOutbox(connection)
      assertEquals(1, outbox.count { it.eventName == "skillbill_goal_subtask_finished" })
    }
  }

  @Test
  fun `in-progress run counts as not finished and has no terminal status`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection)

      store.goalStarted(startedRecord("wf-open", subtaskTotal = 2, resumed = true), level = "full")

      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalRuns)
      assertEquals(0, stats.finishedRuns)
      assertEquals(1, stats.inProgressRuns)
      assertEquals("", requireNotNull(stats.mostRecentRun).status)
      assertTrue(requireNotNull(stats.mostRecentRun).resumed)
    }
  }

  @Test
  fun `malformed persisted subtask row loud-fails on read`() {
    withConnection { connection ->
      connection.prepareStatement(
        """
        INSERT INTO goal_subtask_events (
          issue_key, workflow_id, subtask_id, subtask_name, status,
          started_at, finished_at, duration_ms, attempt_count
        ) VALUES ('SKILL-66', 'wf-1', 1, 'persistence', 'bogus', '2026-06-04T10:00:00Z', '2026-06-04T10:01:00Z', 60000, 1)
        """.trimIndent(),
      ).use { it.executeUpdate() }

      assertFailsWith<InvalidGoalTelemetryRowError> {
        ReviewStatsRuntime.goalStats(connection)
      }
    }
  }

  private fun seedBlockedRunWithMixedSubtasks(store: LifecycleTelemetryStore) {
    store.goalStarted(startedRecord("wf-1", subtaskTotal = 3, resumed = false), level = "full")
    store.goalSubtaskFinished(subtask(id = 1, status = "complete", durationMs = 60_000, attempts = 1), "full")
    store.goalSubtaskFinished(
      subtask(id = 2, status = "blocked", durationMs = 120_000, attempts = 2, blockedReason = "validation failed"),
      "full",
    )
    store.goalSubtaskFinished(subtask(id = 3, status = "skipped", durationMs = 0, attempts = 0), "full")
    store.goalFinished(
      GoalFinishedRecord(
        issueKey = "SKILL-66",
        workflowId = "wf-1",
        status = "blocked",
        startedAt = "2026-06-04T10:00:00Z",
        finishedAt = "2026-06-04T10:30:00Z",
        durationMs = 1_800_000,
        subtasksComplete = 1,
        subtasksBlocked = 1,
        subtasksSkipped = 1,
      ),
      level = "full",
    )
  }

  private fun assertOutboxEmittedOncePerEvent(connection: Connection) {
    val outbox = pendingOutbox(connection)
    assertEquals(1, outbox.count { it.eventName == "skillbill_goal_started" })
    assertEquals(3, outbox.count { it.eventName == "skillbill_goal_subtask_finished" })
    assertEquals(1, outbox.count { it.eventName == "skillbill_goal_finished" })
    assertTrue(
      outbox.any { it.eventName == "skillbill_goal_subtask_finished" && it.payloadJson.contains("validation failed") },
      "Blocked subtask outbox payload should carry blocked_reason at full level.",
    )
  }

  private fun finishedRun(
    store: LifecycleTelemetryStore,
    workflowId: String,
    startedAt: String,
    status: String,
    durationMs: Long,
  ) {
    store.goalStarted(startedRecord(workflowId, subtaskTotal = 1, resumed = false, startedAt = startedAt), "full")
    store.goalFinished(
      GoalFinishedRecord(
        issueKey = "SKILL-66",
        workflowId = workflowId,
        status = status,
        startedAt = startedAt,
        finishedAt = "2026-06-05T00:00:00Z",
        durationMs = durationMs,
        subtasksComplete = 1,
        subtasksBlocked = 0,
        subtasksSkipped = 0,
      ),
      level = "full",
    )
  }

  private fun startedRecord(
    workflowId: String,
    subtaskTotal: Int,
    resumed: Boolean,
    startedAt: String = "2026-06-04T10:00:00Z",
  ): GoalStartedRecord = GoalStartedRecord(
    issueKey = "SKILL-66",
    featureName = "goal telemetry",
    workflowId = workflowId,
    subtaskTotal = subtaskTotal,
    resumed = resumed,
    startedAt = startedAt,
  )

  private fun subtask(
    id: Int,
    status: String,
    durationMs: Long,
    attempts: Int,
    blockedReason: String? = null,
  ): GoalSubtaskFinishedRecord = GoalSubtaskFinishedRecord(
    issueKey = "SKILL-66",
    workflowId = "wf-1",
    subtaskId = id,
    subtaskName = "subtask-$id",
    status = status,
    startedAt = "2026-06-04T10:00:00Z",
    finishedAt = "2026-06-04T10:05:00Z",
    durationMs = durationMs,
    attemptCount = attempts,
    blockedReason = blockedReason,
  )

  private fun pendingOutbox(connection: Connection): List<TelemetryOutboxRecord> =
    TelemetryOutboxStore(connection).listPending(limit = null)

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("runtime-kotlin-goal-telemetry").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }
}
