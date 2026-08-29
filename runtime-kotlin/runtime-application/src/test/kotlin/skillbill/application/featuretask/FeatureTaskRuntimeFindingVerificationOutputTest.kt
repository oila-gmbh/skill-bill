package skillbill.application.featuretask

import skillbill.application.featuretask.FeatureTaskRuntimeCensusCoverageTestSupport.assertVerifyCoverageContains
import skillbill.application.featuretask.FeatureTaskRuntimeCensusCoverageTestSupport.parseVerifyDispositions
import skillbill.application.featuretask.FeatureTaskRuntimeCensusCoverageTestSupport.verifyDisposition
import skillbill.error.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeFindingVerificationOutputTest {
  @Test
  fun `verify_findings wire verdict settles findings_verified`() {
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      mapOf(
        "verdict" to FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED.wireValue,
        "produced_outputs" to mapOf(
          "finding_dispositions" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "disposition" to "verified",
            ),
          ),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings wire verdict settles no_findings_verified`() {
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      mapOf(
        "verdict" to FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED.wireValue,
        "produced_outputs" to mapOf(
          "finding_dispositions" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "disposition" to "rejected",
            ),
          ),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings wire verdict findings_verified settles when census has zero verified rows`() {
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      mapOf(
        "verdict" to FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED.wireValue,
        "produced_outputs" to mapOf(
          "finding_dispositions" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "disposition" to "rejected",
            ),
          ),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings wire verdict no_findings_verified settles when census has verified rows`() {
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      mapOf(
        "verdict" to FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED.wireValue,
        "produced_outputs" to mapOf(
          "finding_dispositions" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "disposition" to "verified",
            ),
          ),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings without wire verdict loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        mapOf(
          "produced_outputs" to mapOf(
            "finding_dispositions" to listOf(
              mapOf(
                "finding_id" to "F-001",
                "disposition" to "verified",
              ),
            ),
          ),
        ),
      )
    }
  }

  @Test
  fun `validateDispositionCoverage rejects duplicate finding ids`() {
    val dispositions = parseVerifyDispositions(
      listOf(
        verifyDisposition("F-001", "verified"),
        verifyDisposition("F-001", "rejected"),
      ),
    )
    assertVerifyCoverageContains(dispositions, setOf("F-001", "F-002"), "duplicate finding_id: F-001.")
  }

  @Test
  fun `validateDispositionCoverage accepts empty review and empty dispositions`() {
    assertNull(validateDispositionCoverage(emptyList(), emptySet()))
  }

  @Test
  fun `validateDispositionCoverage rejects foreign disposition when review findings are empty`() {
    val dispositions = parseVerifyDispositions(listOf(verifyDisposition("F-001")))
    assertVerifyCoverageContains(dispositions, emptySet(), "absent from the preceding review pass: F-001")
  }

  @Test
  fun `finding verification disposition coverage rejects omitted review findings`() {
    val dispositions = parseVerifyDispositions(listOf(verifyDisposition("F-001")))
    assertVerifyCoverageContains(dispositions, setOf("F-001", "F-002"), "omitted finding_id: F-002")
  }

  @Test
  fun `optional reason round-trips through artifact map`() {
    val disposition = FeatureTaskRuntimeFindingVerificationDisposition(
      findingId = "F-001",
      disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
      reason = "Matches spec intent.",
    )
    assertEquals(
      disposition,
      FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(
        disposition.toArtifactMap(),
        "finding_dispositions[0]",
      ),
    )
  }

  @Test
  fun `census-only disposition ignores extra keys`() {
    val disposition = FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(
      mapOf(
        "finding_id" to "F-001",
        "disposition" to "verified",
        "severity" to "major",
        "location" to "Example.kt",
        "message" to "Finding",
      ),
      "finding_dispositions[0]",
    )
    assertEquals("F-001", disposition.findingId)
    assertNull(disposition.reason)
  }

  @Test
  fun `malformed finding verification checkpoint loud-fails when raw is not an array`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.parseList(
        mapOf("finding_id" to "F-001"),
        "finding_verification_checkpoint",
      )
    }
    assertTrue(error.reason.contains("finding_verification_checkpoint"))
    assertTrue(error.reason.contains("array"))
  }

  @Test
  fun `retired disposition field loud-fails with named verification record error`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(
        mapOf(
          "finding_id" to "F-001",
          "verdict" to "verified",
        ),
        "finding_verification_checkpoint[0]",
      )
    }
    assertTrue(error.reason.contains("disposition"))
  }
}
