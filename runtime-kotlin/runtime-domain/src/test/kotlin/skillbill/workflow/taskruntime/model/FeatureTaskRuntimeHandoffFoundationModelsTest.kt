package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FeatureTaskRuntimeHandoffFoundationModelsTest {
  @Test
  fun `producer iteration rejects blank identity and non-positive attempts`() {
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeProducerIteration("", 1) }
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeProducerIteration("plan", 0) }
  }

  @Test
  fun `measurement model exposes counts and classifications but no content bodies`() {
    val propertyNames = FeatureTaskRuntimeProjectionMeasurement::class.java.declaredFields.map { it.name }.toSet()

    listOf("prompt", "payload", "sourceBody", "diffBody", "receipt", "rawOutput", "logs").forEach { forbidden ->
      assertFalse(forbidden in propertyNames, "measurement unexpectedly exposes content field '$forbidden'")
    }
  }

  @Test
  fun `measurement rejects negative counts`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeProjectionMeasurement(
        workflowId = "wftr-1",
        consumerPhaseId = "audit",
        projectionContractId = "implementation_receipt",
        producerIteration = FeatureTaskRuntimeProducerIteration("implement", 1),
        repositoryCheckpointFingerprint = "checkpoint-1",
        projectedUtf8Bytes = -1,
        projectedCollectionItems = 0,
        estimatedTokens = 0,
        privateEvidenceUtf8Bytes = 0,
        deliveredProjectionUtf8Bytes = 0,
      )
    }
  }

  @Test
  fun `private evidence round trips separately and prompt-facing decoder rejects it`() {
    val evidence = FeatureTaskRuntimePrivatePhaseEvidenceRecord(
      workflowId = "wftr-1",
      producerIteration = FeatureTaskRuntimeProducerIteration("implement", 2),
      repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("checkpoint-2"),
      phaseOutput = """{"private":"diagnostic-only"}""",
    )

    assertEquals(evidence, FeatureTaskRuntimePrivatePhaseEvidenceRecord.fromArtifactMap(evidence.toArtifactMap()))
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap(evidence.toArtifactMap())
    }
  }
}
