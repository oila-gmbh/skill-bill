package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeCheckpointIdentitySchemaValidatorTest {
  @Test
  fun `accepts a full checkpoint-identity record`() {
    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf("contract_version" to "0.1", "checkpoints" to listOf(entry())),
      SOURCE,
    )
  }

  @Test
  fun `accepts a record whose only checkpoint omits the optional loop and parent fields`() {
    val forwardEdge = entry().toMutableMap().apply {
      remove("loop_id")
      remove("parent_sha")
    }

    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf("contract_version" to "0.1", "checkpoints" to listOf(forwardEdge)),
      SOURCE,
    )
  }

  @Test
  fun `accepts an empty history`() {
    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf("contract_version" to "0.1", "checkpoints" to emptyList<Any?>()),
      SOURCE,
    )
  }

  @Test
  fun `rejects an unknown field rather than reinterpreting the record`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf(
          "contract_version" to "0.1",
          "checkpoints" to listOf(entry() + ("raw_diff" to "@@ -1 +1 @@")),
        ),
        SOURCE,
      )
    }

    assertContains(error.message.orEmpty(), "raw_diff")
  }

  @Test
  fun `rejects a wrong contract version`() {
    assertFailsWith<InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf("contract_version" to "0.2", "checkpoints" to listOf(entry())),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a missing required identity field`() {
    assertFailsWith<InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf(
          "contract_version" to "0.1",
          "checkpoints" to listOf(entry() - "owned_path_digest"),
        ),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a malformed issue key so an unbounded authority boundary cannot be recorded`() {
    assertFailsWith<InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf(
          "contract_version" to "0.1",
          "checkpoints" to listOf(entry() + ("issue_key" to "not an issue key")),
        ),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a commit sha that is not a commit sha`() {
    assertFailsWith<InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf(
          "contract_version" to "0.1",
          "checkpoints" to listOf(entry() + ("commit_sha" to "checkpoint-sha")),
        ),
        SOURCE,
      )
    }
  }

  private fun entry(): Map<String, Any?> = mapOf(
    "sequence_number" to 0,
    "issue_key" to "SKILL-150",
    "branch" to "feat/SKILL-150-scoped-checkpoint",
    "phase_id" to "audit",
    "loop_id" to "audit_gap",
    "generation" to 1,
    "parent_sha" to "b".repeat(40),
    "owned_path_digest" to "c".repeat(64),
    "owned_path_count" to 3,
    "commit_sha" to "a".repeat(40),
    "recorded_at" to "2026-08-04T00:00:00Z",
  )

  private companion object {
    const val SOURCE = "checkpoint-identity-test"
  }
}
