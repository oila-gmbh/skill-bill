package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * An audit gap can sit in governed markdown as well as in code. `location` once required a Java-style
 * identifier, which no kebab-case governed skill name could match, so a prose gap was unrecordable and
 * the audit exhausted its malformed-output correction budget against an unsatisfiable field instead of
 * reporting the gap it had found. These cases pin the widened segment and the bound that survived it.
 */
class FeatureTaskRuntimeAuditGapLocationSchemaTest {
  @Test
  fun `a governed markdown gap records a kebab-case skill name`() {
    listOf(
      "bill-feature-goal",
      "bill-feature-goal.AuditFirstReviewAndFindingsLedger",
      "bill-feature-task-prose.CodeReviewSelection",
      "bill-feature-task-subtask-runner",
    ).forEach { location ->
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(gapEnvelope(location), "audit")
    }
  }

  @Test
  fun `a code gap keeps recording a compact class or member reference`() {
    listOf(
      "ReviewRunner",
      "ReviewRunner.merge",
      "GoalSubtaskReviewSummaryReducer.fromOutput",
      "_internalHolder.value",
    ).forEach { location ->
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(gapEnvelope(location), "audit")
    }
  }

  @Test
  fun `location stays one compact segment pair and never carries prose or a path`() {
    listOf(
      "bill-feature-goal.Child review scope",
      "skills/bill-feature-goal/content.md",
      "com.example.deeply.qualified.Name",
      "-leading-hyphen",
      "",
    ).forEach { location ->
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError>("location \"$location\"") {
        FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(gapEnvelope(location), "audit")
      }
    }
  }

  private fun gapEnvelope(location: String): String =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"audit",""" +
      """"verdict":"gaps_found","produced_outputs":{"gaps":[""" +
      """{"criterion":"AC-008","severity":"blocker","location":"$location",""" +
      """"issue":"Governed prose still states the retired two-pass framing.",""" +
      """"fix":"Restate the sentence for unbounded remediation."}]}}"""
}
