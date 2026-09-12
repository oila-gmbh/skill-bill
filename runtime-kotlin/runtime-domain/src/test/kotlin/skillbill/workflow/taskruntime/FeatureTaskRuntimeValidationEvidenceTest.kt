package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.ValidationEvidencePayloadKeys
import skillbill.error.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationCommandResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeValidationEvidenceTest {
  @Test
  fun `valid evidence preserves command and exit code through wire round trip`() {
    val evidence = FeatureTaskRuntimeValidationEvidence(
      listOf(FeatureTaskRuntimeValidationCommandResult("./gradlew check", 0)),
    )

    val restored = FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
      evidence.toArtifactMap(),
      "test",
    )

    assertEquals("./gradlew check", restored.results.single().command)
    assertEquals(0, restored.results.single().exitCode)
  }

  @Test
  fun `missing and malformed evidence fail with typed errors`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(emptyMap(), "missing")
    }
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        mapOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
          ValidationEvidencePayloadKeys.RESULTS to listOf(
            mapOf(ValidationEvidencePayloadKeys.COMMAND to "./gradlew check"),
          ),
        ),
        "malformed",
      )
    }
  }

  @Test
  fun `non-zero final result cannot satisfy completion`() {
    val evidence = FeatureTaskRuntimeValidationEvidence(
      listOf(FeatureTaskRuntimeValidationCommandResult("./gradlew check", 1)),
    )

    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulResult("test")
    }
  }

  @Test
  fun `required command cannot be masked by a later unrelated success`() {
    val evidence = FeatureTaskRuntimeValidationEvidence(
      listOf(
        FeatureTaskRuntimeValidationCommandResult("./gradlew check", 1),
        FeatureTaskRuntimeValidationCommandResult("unrelated", 0),
      ),
    )

    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulCommand("./gradlew check", "test")
    }
  }

  @Test
  fun `unsupported evidence version is actionable`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        mapOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION to "9.9",
          ValidationEvidencePayloadKeys.RESULTS to emptyList<Any?>(),
        ),
        "legacy",
      )
    }
  }
}
