package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimeImplementationAttemptSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationAttemptSchemaValidatorTest {
  @Test
  fun `accepts a well-formed attempt history`() {
    FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(record(attempt()), SOURCE)
  }

  @Test
  fun `accepts an absent history as zero attempts`() {
    FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(record(), SOURCE)
  }

  @Test
  fun `rejects an unknown top-level property`() {
    assertFailsWith<InvalidFeatureTaskRuntimeImplementationAttemptSchemaError> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt()) + mapOf("attempt_count" to 1),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a wrong contract version`() {
    assertFailsWith<InvalidFeatureTaskRuntimeImplementationAttemptSchemaError> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt()) + mapOf("contract_version" to "0.2"),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a malformed task id`() {
    assertFailsWith<InvalidFeatureTaskRuntimeImplementationAttemptSchemaError> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt(completedTaskIds = listOf("Task_1"))),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects an unknown attempt status`() {
    assertFailsWith<InvalidFeatureTaskRuntimeImplementationAttemptSchemaError> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt(status = "partially_completed")),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a deviation missing its ref`() {
    assertFailsWith<InvalidFeatureTaskRuntimeImplementationAttemptSchemaError> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt() + mapOf("deviations" to listOf(mapOf("note" to "renamed the file")))),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a missing required attempt field`() {
    assertFailsWith<InvalidFeatureTaskRuntimeImplementationAttemptSchemaError> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt() - "sequence_number"),
        SOURCE,
      )
    }
  }

  @Test
  fun `failure names the source label so the offending record is identifiable`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeImplementationAttemptSchemaError> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(record(attempt(status = "nope")), SOURCE)
    }

    assertTrue(error.message.orEmpty().contains(SOURCE), "Error must name '$SOURCE'; got: ${error.message}")
  }

  private fun record(vararg attempts: Map<String, Any?>): Map<String, Any?> = mapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION,
    "attempts" to attempts.toList(),
  )

  private fun attempt(
    status: String = "incomplete",
    completedTaskIds: List<String> = listOf("task-1"),
  ): Map<String, Any?> = mapOf(
    "sequence_number" to 1,
    "phase_id" to "implement",
    "attempt_number" to 1,
    "agent_id" to "claude",
    "status" to status,
    "recorded_at" to "2026-08-04T10:00:00Z",
    "completed_task_ids" to completedTaskIds,
    "changed_paths" to listOf("runtime-kotlin/runtime-application/src/main/kotlin/Sample.kt"),
  )

  private companion object {
    const val SOURCE = "implement.attempt_history"
  }
}
