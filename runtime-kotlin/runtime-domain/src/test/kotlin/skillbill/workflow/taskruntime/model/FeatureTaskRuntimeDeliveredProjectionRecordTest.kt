package skillbill.workflow.taskruntime.model

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

private const val PRIVATE_EVIDENCE = """{"phase_id":"plan","produced_outputs":{"secret":"private-evidence-body"}}"""

class FeatureTaskRuntimeDeliveredProjectionRecordTest {
  @Test
  fun `private evidence and the delivered projection persist under separate artifact keys`() {
    assertNotEquals(
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY,
      FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY,
      "merging the private-evidence and delivered-projection stores is exactly the substitution this split prevents",
    )
  }

  @Test
  fun `a delivered projection round trips without absorbing the private phase output`() {
    val record = deliveredProjection()

    val restored = FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap(record.toArtifactMap())

    assertEquals(record, restored)
    val serialized = JsonSupport.mapToJsonString(record.toArtifactMap())
    assertFalse(
      serialized.contains("private-evidence-body"),
      "the delivered-projection record absorbed the private phase output body",
    )
  }

  @Test
  fun `a private phase record round trips and is not decodable as a delivered projection`() {
    val phaseRecord = FeatureTaskRuntimePhaseRecord(
      phaseId = "plan",
      status = "completed",
      attemptCount = 1,
      startedAt = "2026-07-23T00:00:00Z",
      resolvedAgentId = "claude",
      outputArtifact = PRIVATE_EVIDENCE,
    )

    val restored = FeatureTaskRuntimePhaseRecord.fromArtifactMap(phaseRecord.toArtifactMap())
    assertEquals(PRIVATE_EVIDENCE, restored.outputArtifact)

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap(phaseRecord.toArtifactMap())
    }
  }

  @Test
  fun `a delivered projection is not decodable as a private phase record`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(deliveredProjection().toArtifactMap())
    }
  }

  @Test
  fun `an envelope addressed to another consumer phase is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeDeliveredProjectionRecord(
        workflowId = "wftr-1",
        consumerPhaseId = "audit",
        iteration = 1,
        envelope = FeatureTaskRuntimeHandoffEnvelope(consumerPhaseId = "implement"),
      )
    }
  }

  private fun deliveredProjection() = FeatureTaskRuntimeDeliveredProjectionRecord(
    workflowId = "wftr-1",
    consumerPhaseId = "implement",
    iteration = 1,
    envelope = FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = "implement",
      projections = listOf(
        FeatureTaskRuntimeHandoffProjection(
          projectionName = "plan_receipt",
          sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput("plan"),
          projectionContractId = "feature_task_runtime.upstream_phase_receipt",
          projectionContractVersion = "0.1",
          promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
          fields = listOf(
            FeatureTaskRuntimeHandoffProjectionField(
              name = "phase_output_receipt",
              value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
                kind = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
                value = "feature_task_runtime_phase_records/plan#1",
              ),
            ),
          ),
        ),
      ),
      repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
    ),
  )
}
