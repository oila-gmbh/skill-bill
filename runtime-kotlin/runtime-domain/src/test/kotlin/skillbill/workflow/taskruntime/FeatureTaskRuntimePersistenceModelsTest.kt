package skillbill.workflow.taskruntime

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FeatureTaskRuntimePersistenceModelsTest {
  @Test
  fun `per-phase record round trips through its artifact map`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "implement",
      status = "completed",
      attemptCount = 2,
      startedAt = "2026-06-02T10:00:00Z",
      finishedAt = "2026-06-02T10:05:00Z",
      durationMillis = 300_000,
      resolvedAgentId = "agent-implement-1",
      outputArtifact = """{"contract_version":"0.1"}""",
    )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap())
    assertEquals(record, decoded)
  }

  @Test
  fun `running per-phase record omits finish and duration and output`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "plan",
      status = "running",
      attemptCount = 1,
      startedAt = "2026-06-02T10:00:00Z",
      resolvedAgentId = "agent-plan-1",
    )
    val map = record.toArtifactMap()
    assertNull(map["finished_at"])
    assertNull(map["duration_millis"])
    assertNull(map["output_artifact"])
    assertEquals(record, FeatureTaskRuntimePhaseRecord.fromArtifactMap(map))
  }

  @Test
  fun `blocked per-phase record round trips with blocked reason and distinct first started at`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "review",
      status = "blocked",
      attemptCount = 3,
      startedAt = "2026-06-02T10:10:00Z",
      firstStartedAt = "2026-06-02T10:00:00Z",
      resolvedAgentId = "agent-review-1",
      blockedReason = "exhausted the bounded fix loop",
    )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap())
    assertEquals(record, decoded)
    assertEquals("2026-06-02T10:00:00Z", decoded.firstStartedAt)
    assertEquals("exhausted the bounded fix loop", decoded.blockedReason)
  }

  @Test
  fun `per-phase record decode falls back first started at to started at when absent`() {
    val legacy = mapOf(
      "phase_id" to "plan",
      "status" to "running",
      "attempt_count" to 1,
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-plan-1",
    )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(legacy)
    assertEquals("2026-06-02T10:00:00Z", decoded.firstStartedAt)
    assertNull(decoded.blockedReason)
  }

  @Test
  fun `per-phase record decode loud-fails on missing required field`() {
    val malformed = mapOf(
      "phase_id" to "plan",
      "status" to "completed",
      // attempt_count missing
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-plan-1",
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `per-phase record decode loud-fails on wrong-typed attempt count`() {
    val malformed = mapOf(
      "phase_id" to "plan",
      "status" to "completed",
      "attempt_count" to "not-an-int",
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-plan-1",
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `ledger entry round trips through its artifact map for every action`() {
    FeatureTaskRuntimePhaseLedgerAction.entries.forEachIndexed { index, action ->
      val entry = FeatureTaskRuntimePhaseLedgerEntry(
        action = action,
        sequenceNumber = index,
        timestamp = "2026-06-02T10:0$index:00Z",
        phaseId = "review",
        attemptCount = 1,
        resolvedAgentId = "agent-review-1",
        fixLoopIteration = if (action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION) 2 else null,
        blockedReason = if (action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED) "gate failed" else null,
      )
      assertEquals(entry, FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entry.toArtifactMap()))
    }
  }

  @Test
  fun `ledger action wire values cover the required event set`() {
    assertEquals(
      listOf("start", "resume", "retry", "fix_loop_iteration", "blocked", "complete"),
      FeatureTaskRuntimePhaseLedgerAction.entries.map { it.wireValue },
    )
  }

  @Test
  fun `unknown ledger action loud-fails with a typed schema error`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerAction.fromWire("teleport")
    }
  }

  @Test
  fun `ledger entry decode loud-fails on missing timestamp`() {
    val malformed = mapOf(
      "action" to "start",
      "sequence_number" to 0,
      // timestamp missing
      "phase_id" to "plan",
      "attempt_count" to 1,
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `append-only ledger keeps monotonic sequence order and prunes oldest beyond the limit`() {
    var ledger = emptyList<Map<String, Any?>>()
    (0 until FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT + 3).forEach { index ->
      val entry = FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
        sequenceNumber = index,
        timestamp = "2026-06-02T10:00:00Z",
        phaseId = "implement",
        attemptCount = 1,
      )
      ledger = appendBoundedHistoryBySequence(ledger, entry.toArtifactMap(), FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT)
    }
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT, ledger.size)
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(sequences.sorted(), sequences)
    assertEquals(3, sequences.first())
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT + 2, sequences.last())
  }
}
