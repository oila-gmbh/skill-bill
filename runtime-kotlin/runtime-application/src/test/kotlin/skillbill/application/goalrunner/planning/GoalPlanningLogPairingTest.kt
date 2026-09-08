package skillbill.application.goalrunner.planning

import skillbill.application.RecordingOutcomeStore
import skillbill.application.goalrunner.planning.model.GoalPlanningLogRequest
import skillbill.application.manifest
import skillbill.application.testHarnessClock
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PARENT_WORKFLOW_ID = "wftr-pairing"
private const val ISSUE_KEY = "SKILL-1"
private const val OPERATION = "plan:3:attempt:1"

/**
 * A relaunched goal re-mints the same operation name for the attempt it retries, so the name alone
 * does not identify one interval. These pin that each start keeps its own completion.
 */
class GoalPlanningLogPairingTest {
  @Test
  fun `a relaunched attempt does not borrow the previous segment's completion`() {
    val log = logFrom(
      started("2026-08-24T11:57:20Z"),
      completed("2026-08-24T11:57:50Z", outcome = "failed"),
      started("2026-08-24T12:28:01Z"),
      completed("2026-08-24T12:32:01Z", outcome = "succeeded"),
    )

    assertEquals(2, log.attempts.size, "each segment is its own attempt")
    val (first, second) = log.attempts
    assertEquals(30_000L, first.durationMs)
    assertEquals("failed", first.outcome)
    assertEquals(240_000L, second.durationMs)
    assertEquals("succeeded", second.outcome)
    assertEquals(270_000L, log.totalPlanningMs)
    assertTrue(log.attempts.none { it.timestampsInconsistent })
  }

  @Test
  fun `an attempt whose process died stays in flight instead of taking a later finish`() {
    val log = logFrom(
      started("2026-08-24T11:57:20Z"),
      started("2026-08-24T12:28:01Z"),
      completed("2026-08-24T12:32:01Z", outcome = "succeeded"),
    )

    assertEquals(2, log.attempts.size)
    val crashed = log.attempts.first { it.outcome == "in_flight" }
    assertNull(crashed.finishedAt, "a killed attempt has no finish of its own")
    assertNull(crashed.durationMs)
    assertFalse(crashed.timestampsInconsistent)
    assertEquals(240_000L, log.totalPlanningMs, "only the measured interval counts")
  }

  @Test
  fun `an inverted pair reports no duration and says the record is inconsistent`() {
    val log = logFrom(
      started("2026-08-24T12:28:01Z"),
      completed("2026-08-24T11:57:50Z", outcome = "failed"),
    )

    val attempt = log.attempts.single()
    assertTrue(attempt.timestampsInconsistent)
    assertNull(attempt.durationMs, "a finish before its start is not a duration")
    assertEquals(0L, log.totalPlanningMs, "an unusable record must not subtract from the total")
  }

  private fun logFrom(vararg events: Map<String, Any?>) = GoalPlanningLogService(
    manifestStore = StubManifestStore,
    outcomeStore = StubOutcomeStore(events.toList()),
    database = UnreadableDatabase,
    diagnosticMetadataValidator = RejectedOutputDiagnosticMetadataValidator { },
    clock = testHarnessClock,
  ).log(GoalPlanningLogRequest(issueKey = ISSUE_KEY))

  private fun started(timestamp: String): Map<String, Any?> = mapOf(
    "workflow_phase" to "goal_planning",
    "operation_name" to OPERATION,
    "event_kind" to "operation_started",
    "timestamp" to timestamp,
  )

  private fun completed(timestamp: String, outcome: String): Map<String, Any?> = mapOf(
    "workflow_phase" to "goal_planning",
    "operation_name" to OPERATION,
    "event_kind" to "operation_completed",
    "timestamp" to timestamp,
    "outcome" to outcome,
  )
}

private object StubManifestStore : GoalRunnerManifestStore {
  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?) =
    GoalRunnerManifestState(PARENT_WORKFLOW_ID, "/fake/metrics.db", manifest(subtaskCount = 3))

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?) = state

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

private class StubOutcomeStore(private val events: List<Map<String, Any?>>) :
  GoalRunnerWorkflowOutcomeStore by RecordingOutcomeStore() {
  override fun progressEvents(workflowId: String, dbPathOverride: String?) = events
}

/** Rejection metadata is a separate read the log degrades past; refusing it keeps these on pairing. */
private object UnreadableDatabase : DatabaseSessionFactory {
  override fun resolveDbPath(dbOverride: String?): Path = Path.of("/fake/metrics.db")

  override fun databaseExists(dbOverride: String?): Boolean = false

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = unsupported()

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = unsupported()

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = unsupported()

  private fun unsupported(): Nothing = throw UnsupportedOperationException("no database in this test")
}
