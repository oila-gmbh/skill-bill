package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The review-pass bound is enforced twice: as `private_phase_record.review_pass_number` in
 * feature-task-runtime-persistence-schema.yaml and as the `pass >= 1` require in
 * FeatureTaskRuntimePhaseRecord. They drifted: the model was widened for unbounded remediation while
 * the schema kept `maximum: 2`, so the contract of record still declared the two-pass ceiling and any
 * seam validating a private_phase_record rejected every pass from three onward. Only the
 * delivered_projection branch is exercised today, which is why the drift stayed latent. Feeding one
 * record's own wire map to both layers pins them to the same verdict.
 */
class FeatureTaskRuntimePersistenceReviewPassParityTest {
  @Test
  fun `schema and model accept a remediation pass past the retired two-pass ceiling`() {
    listOf(1, 2, 3, 7, 42).forEach { pass ->
      val record = reviewRecord(pass)

      assertEquals(pass, record.reviewPassNumber, "model verdict for pass $pass")
      FeatureTaskRuntimePersistenceSchemaValidator.validate(record.toArtifactMap(), "review.record")
      assertEquals(
        pass,
        FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap()).reviewPassNumber,
        "round-trip verdict for pass $pass",
      )
    }
  }

  @Test
  fun `schema and model agree that a pass number below one is invalid`() {
    val wireMap = reviewRecord(2).toArtifactMap() + ("review_pass_number" to 0)

    assertFailsWith<InvalidFeatureTaskRuntimePersistenceSchemaError> {
      FeatureTaskRuntimePersistenceSchemaValidator.validate(wireMap, "review.record")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(wireMap)
    }
  }

  /**
   * `private_phase_record` is additionalProperties:false and the root schema is a closed oneOf, so a
   * launch pair the model writes but the schema does not declare makes the record match neither branch.
   * Same drift class as the review-pass ceiling above, pinned before it can go latent again.
   */
  @Test
  fun `schema accepts the launch pair the model records`() {
    val merged = reviewRecord(1).copy(launchedModel = "claude-opus-4-8[effort=high]")
    val split = reviewRecord(1).copy(launchedModel = "claude-opus-4-8", launchedEffort = "high")

    listOf(merged, split).forEach { record ->
      FeatureTaskRuntimePersistenceSchemaValidator.validate(record.toArtifactMap(), "review.record")
      assertEquals(
        record.launchedModel to record.launchedEffort,
        FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap())
          .let { it.launchedModel to it.launchedEffort },
      )
    }
  }

  @Test
  fun `schema accepts duplicate-key merge repair evidence the model persists`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "validate",
      status = "completed",
      attemptCount = 1,
      startedAt = "2026-08-16T12:00:00Z",
      resolvedAgentId = "cursor",
      outputArtifact = "{\"phase_id\":\"validate\"}",
      repairEvidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        originalDigest = "a".repeat(64),
        repairedDigest = "b".repeat(64),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS,
        sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("validate", 12, 1, 13),
      ),
    )

    FeatureTaskRuntimePersistenceSchemaValidator.validate(record.toArtifactMap(), "validate.record")
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS,
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap()).repairEvidence?.operation,
    )
  }

  private fun reviewRecord(pass: Int): FeatureTaskRuntimePhaseRecord = FeatureTaskRuntimePhaseRecord(
    phaseId = "review",
    status = "running",
    attemptCount = 1,
    startedAt = "2026-08-03T10:00:00Z",
    resolvedAgentId = "agent-review-1",
    loopId = "review_fix",
    edgeIteration = 1,
    reviewPassNumber = pass,
  )
}
