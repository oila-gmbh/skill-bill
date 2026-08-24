package skillbill.goalrunner

import skillbill.goalrunner.model.GoalAttemptLedger
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerAccountingModelsTest {
  @Test
  fun `ledger appends preserve sequence order and cap to retention limit`() {
    var ledger = GoalAttemptLedger(retentionLimit = 3)
    (0 until 5).forEach { index ->
      ledger = ledger.append(
        GoalAttemptLedgerEntry(
          action = GoalAttemptLedgerAction.CHILD_ACTIVATION,
          sequenceNumber = index,
          timestamp = "2026-06-02T10:0$index:00Z",
        ),
      )
    }
    assertEquals(3, ledger.entries.size)
    assertEquals(listOf(2, 3, 4), ledger.entries.map { it.sequenceNumber })
  }

  @Test
  fun `ledger entry artifact map carries action wire value and optional fields`() {
    val entry = GoalAttemptLedgerEntry(
      action = GoalAttemptLedgerAction.TIMEOUT,
      sequenceNumber = 7,
      timestamp = "2026-06-02T10:00:00Z",
      issueKey = "SKILL-64",
      subtaskId = 2,
      stopReason = "timeout",
    )
    val map = entry.toArtifactMap()
    assertEquals("timeout", map["action"])
    assertEquals("SKILL-64", map["issue_key"])
    assertEquals(2, map["subtask_id"])
    assertEquals("timeout", map["stop_reason"])
  }
}
