package skillbill.contracts.workflow

import skillbill.error.InvalidIdeStatusSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IdeStatusSchemaValidatorTest {
  @Test
  fun `valid idle snapshot passes`() {
    IdeStatusSchemaValidator.validate(validIdleSnapshot(), "test-idle")
  }

  @Test
  fun `valid active feature-goal snapshot passes`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-148",
        "workflow_id" to "goal-1",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T10:00:00Z",
        "current_subtask" to linkedMapOf("id" to "1", "started_at" to "2026-08-06T10:05:00Z"),
        "updated_at" to "2026-08-06T10:10:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-148 is active on implement.",
      ),
      "test-goal",
    )
  }

  @Test
  fun `unknown lifecycle state fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["lifecycle_state"] = "exploded"
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-lifecycle")
    }
  }

  @Test
  fun `unknown problem code fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["problem"] = linkedMapOf(
      "code" to "not_a_code",
      "message" to "bad",
    )
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-problem")
    }
  }

  @Test
  fun `wrong contract version fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["contract_version"] = "9.9"
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-version")
    }
  }

  @Test
  fun `unknown additional property fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["sqlite_row"] = "nope"
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-additional-property")
    }
  }

  @Test
  fun `malformed nested current_step fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["current_step"] = linkedMapOf("id" to "idle")
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-malformed-step")
    }
  }

  private fun validIdleSnapshot(): LinkedHashMap<String, Any?> = linkedMapOf(
    "contract_version" to IDE_STATUS_CONTRACT_VERSION,
    "repository_identity" to "repo-root-realpath-v1:/repo",
    "lifecycle_state" to "idle",
    "current_step" to linkedMapOf("id" to "none", "label" to "No matching work"),
    "updated_at" to "2026-08-06T10:00:00Z",
    "freshness" to "fresh",
    "summary" to "No matching Skill Bill work for this repository.",
    "problem" to linkedMapOf(
      "code" to "no_matching_work",
      "message" to "No matching Skill Bill work for this repository.",
    ),
  )
}
