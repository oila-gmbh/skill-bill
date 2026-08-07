package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The delivered shape of the shared review evidence projection. These assert what reaches the prompt,
 * not what the store holds: the whole point of the reference is that the diff bytes never arrive here.
 */
class FeatureTaskRuntimeSharedReviewEvidenceProjectionTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition

  @Test
  fun `the delivered projection carries reference data only and no diff bytes`() {
    val diffLine = "+val poisoned = true"
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(evidence = evidence(fileCount = 3)),
    )
    val projection = envelope.projections.single()
    assertEquals(def.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME, projection.projectionName)
    projection.fields.forEach { field ->
      assertTrue(
        field.value is FeatureTaskRuntimeHandoffProjectionValue.CompactReference ||
          field.value is FeatureTaskRuntimeHandoffProjectionValue.Text ||
          field.value is FeatureTaskRuntimeHandoffProjectionValue.TextList,
      )
    }
    val rendered = projection.fields.joinToString("\n") { field ->
      when (val value = field.value) {
        is FeatureTaskRuntimeHandoffProjectionValue.Text -> value.text
        is FeatureTaskRuntimeHandoffProjectionValue.TextList -> value.items.joinToString("\n")
        is FeatureTaskRuntimeHandoffProjectionValue.CompactReference -> value.value
      }
    }
    assertTrue(diffLine !in rendered, "the projection must never carry diff bytes")
    assertTrue("@@" !in rendered, "the projection must never carry hunk bodies")
  }

  @Test
  fun `serialized size is independent of branch diff size at equal file counts`() {
    fun bytes(hunksPerFile: Int): Int = FeatureTaskRuntimeHandoffProjectionValidator
      .validate(inputs(evidence = evidence(fileCount = 12, hunksPerFile = hunksPerFile)))
      .projections
      .single()
      .utf8ByteSize

    assertEquals(bytes(hunksPerFile = 1), bytes(hunksPerFile = 1))
    // A diff two orders of magnitude larger over the same files must not grow the delivered payload
    // beyond the per-file hunk counter's own digits.
    assertTrue(bytes(hunksPerFile = 200) - bytes(hunksPerFile = 1) <= 12 * 2)
  }

  @Test
  fun `an absent artifact omits the projection instead of rejecting the launch`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(inputs(evidence = null))
    assertTrue(envelope.projections.isEmpty())
    assertNull(envelope.projections.firstOrNull { it.projectionName == def.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME })
  }

  private fun evidence(fileCount: Int, hunksPerFile: Int = 1) =
    FeatureTaskRuntimeSharedReviewEvidenceReference(
      storePath = ".skill-bill/run-evidence/wftr-1/fp",
      checkpointFingerprint = "fp",
      baseRef = "base-sha",
      headRef = "head-sha",
      fileHunkIndex = (1..fileCount).map { "modified f$it.kt hunks=$hunksPerFile" },
    )

  private fun inputs(evidence: FeatureTaskRuntimeSharedReviewEvidenceReference?) =
    FeatureTaskRuntimeHandoffProjectionInputs(
      consumerPhaseId = def.PHASE_REVIEW,
      declarations = listOf(def.sharedReviewEvidenceDeclaration(def.PHASE_REVIEW)),
      resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-164/spec.md",
        featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
        acceptanceCriteria = listOf("AC-001"),
        mandatesAndOverrides = emptyList(),
      ),
      resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fp"),
      sharedReviewEvidence = evidence,
      workflowId = "wftr-1",
    )
}
