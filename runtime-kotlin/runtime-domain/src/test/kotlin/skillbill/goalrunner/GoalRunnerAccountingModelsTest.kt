package skillbill.goalrunner

import skillbill.goalrunner.model.GoalAttemptLedger
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalSessionAccountingFields
import skillbill.goalrunner.model.GoalSessionAccountingParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalRunnerAccountingModelsTest {
  @Test
  fun `parser records available accounting when token data is present`() {
    val accounting = GoalSessionAccountingParser.parse(
      subtaskId = 1,
      phase = "implement",
      sequenceNumber = 0,
      timestamp = "2026-06-02T10:00:00Z",
      fields = GoalSessionAccountingFields(
        childSessionId = "sess-1",
        model = "test-model",
        inputTokens = 100,
        outputTokens = 50,
      ),
    )
    assertTrue(accounting.available)
    assertEquals(100, accounting.inputTokens)
    assertEquals("sess-1", accounting.childSessionId)
  }

  @Test
  fun `parser records available accounting when only a provider-neutral session path is present`() {
    // SKILL-64 Subtask 3 (AC6, AC11): the launcher exposes a provider-neutral
    // child session path (working dir) even without token data; that alone makes
    // accounting available with the session path/id populated.
    val accounting = GoalSessionAccountingParser.parse(
      subtaskId = 1,
      phase = "implement",
      sequenceNumber = 0,
      timestamp = "2026-06-02T10:00:00Z",
      fields = GoalSessionAccountingFields(
        childSessionPath = "/work/child",
        childSessionId = "claude:SKILL-64:subtask-1",
      ),
    )
    assertTrue(accounting.available)
    assertEquals("/work/child", accounting.childSessionPath)
    assertEquals("claude:SKILL-64:subtask-1", accounting.childSessionId)
  }

  @Test
  fun `parser records accounting-unavailable when no token or session data is present`() {
    val accounting = GoalSessionAccountingParser.parse(
      subtaskId = 1,
      phase = "implement",
      sequenceNumber = 0,
      timestamp = "2026-06-02T10:00:00Z",
      fields = GoalSessionAccountingFields(unavailableReason = "provider log absent"),
    )
    assertFalse(accounting.available)
    assertEquals("provider log absent", accounting.unavailableReason)
  }

  @Test
  fun `parser supplies a default unavailable reason when none provided`() {
    val accounting = GoalSessionAccountingParser.parse(
      subtaskId = 1,
      phase = "implement",
      sequenceNumber = 0,
      timestamp = "2026-06-02T10:00:00Z",
      fields = GoalSessionAccountingFields(),
    )
    assertFalse(accounting.available)
    assertNotNull(accounting.unavailableReason)
  }

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
