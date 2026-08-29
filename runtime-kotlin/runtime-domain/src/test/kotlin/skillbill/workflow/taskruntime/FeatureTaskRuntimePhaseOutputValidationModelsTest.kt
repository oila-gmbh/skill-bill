package skillbill.workflow.taskruntime

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAccepted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputValidationModelsTest {
  private val normalized = NormalizedFeatureTaskRuntimePhaseOutput(
    canonicalJson = "{\"phase_id\":\"plan\"}",
    envelope = mapOf("phase_id" to "plan"),
  )

  @Test
  fun `typed result distinguishes unchanged repaired and rejected states`() {
    val unchanged = FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(normalized)
    val repaired = FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(
      normalized,
      FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        originalDigest = "a".repeat(64),
        repairedDigest = "b".repeat(64),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
        sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("plan", 12, 1, 13),
      ),
    )
    val rejected = FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
      code = FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
      reason = "Phase output has multiple strictly parseable structural-repair candidates.",
    )

    assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(unchanged)
    assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(repaired)
    assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(rejected)
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION, unchanged.contractVersion)
    assertFalse(rejected.normalizedOutput != null)
  }

  @Test
  fun `repair evidence is versioned payload free and location aware`() {
    val evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = FeatureTaskRuntimePhaseOutputFormat.YAML,
      originalDigest = "0".repeat(64),
      repairedDigest = "1".repeat(64),
      operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("plan", 0, 3, 4),
    )

    assertEquals(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION, evidence.contractVersion)
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION, evidence.validatorVersion)
    assertEquals(FeatureTaskRuntimePhaseOutputFormat.YAML, evidence.format)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER, evidence.operation)
    assertEquals(3, evidence.sourceLocation.line)
    assertEquals(4, evidence.sourceLocation.column)
    assertFalse(evidence.toString().contains("payload"))
  }

  @Test
  fun `repair evidence round trips through the artifact map and rejects unknown fields`() {
    val evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = FeatureTaskRuntimePhaseOutputFormat.JSON,
      originalDigest = "a".repeat(64),
      repairedDigest = "b".repeat(64),
      operation = FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
      sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("plan", 5, 1, 6),
    )

    assertEquals(evidence, FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(evidence.toArtifactMap()))
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(evidence.toArtifactMap() + ("unexpected" to true))
    }
  }

  @Test
  fun `rejected result converts to a typed throwing seam with its stable failure code`() {
    val rejected = FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
      code = FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
      reason = "multiple candidates",
    )

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      rejected.requireAccepted("plan")
    }

    assertEquals("ambiguous_repair", error.failureCode)
    assertFalse(error.acceptedAfterStructuralRepair)
  }

  @Test
  fun `rejected after structural repair maps acceptedAfterStructuralRepair onto the throwing seam`() {
    // Realistic bug: adapter keeps digest evidence on Rejected, but requireAccepted drops it and the
    // corrective retry never learns syntax repair already ran.
    val evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = FeatureTaskRuntimePhaseOutputFormat.JSON,
      originalDigest = "a".repeat(64),
      repairedDigest = "b".repeat(64),
      operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("audit", 0, 1, 1),
    )
    val rejected = FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
      code = FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID,
      reason = "verdict must be a top-level string",
      structuralRepairEvidence = evidence,
    )

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      rejected.requireAccepted("audit")
    }

    assertTrue(error.acceptedAfterStructuralRepair)
    assertEquals(evidence.originalDigest, error.structuralRepairOriginalDigest)
    assertEquals(evidence.repairedDigest, error.structuralRepairRepairedDigest)
    assertEquals(evidence.format.wireValue, error.structuralRepairFormat)
    assertEquals(evidence.operation.wireValue, error.structuralRepairOperation)
    assertEquals(evidence.sourceLocation.sourceLabel, error.structuralRepairSourceLabel)
    assertEquals(evidence.sourceLocation.offset, error.structuralRepairSourceOffset)
    assertEquals(evidence.sourceLocation.line, error.structuralRepairSourceLine)
    assertEquals(evidence.sourceLocation.column, error.structuralRepairSourceColumn)
  }
}
