package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeFindingVerificationOutputTest {
  @Test
  fun `verify_findings with a verified disposition settles findings_verified`() {
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      mapOf(
        "produced_outputs" to mapOf(
          "finding_dispositions" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "disposition" to "verified",
              "reason" to "Matches spec intent AC-002.",
              "severity" to "major",
              "location" to "FeatureTaskRuntimePhaseWorkflowDefinition.kt",
              "message" to "Missing verify_findings wiring",
            ),
          ),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings with all rejected dispositions settles no_findings_verified`() {
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      mapOf(
        "produced_outputs" to mapOf(
          "finding_dispositions" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "disposition" to "rejected",
              "reason" to "False positive against spec intent.",
              "severity" to "minor",
              "location" to "README.md",
              "message" to "Nit finding",
            ),
          ),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `validateDispositionCoverage rejects duplicate finding ids`() {
    val dispositions = listOf(
      mapOf(
        "finding_id" to "F-001",
        "disposition" to "verified",
        "reason" to "Matches spec intent.",
        "severity" to "major",
        "location" to "Example.kt",
        "message" to "Finding one",
      ),
      mapOf(
        "finding_id" to "F-001",
        "disposition" to "rejected",
        "reason" to "Duplicate id.",
        "severity" to "minor",
        "location" to "Example.kt",
        "message" to "Finding one again",
      ),
    ).mapIndexed { index, entry ->
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(
        entry,
        "finding_dispositions[$index]",
      )
    }
    assertTrue(
      validateDispositionCoverage(
        dispositions,
        setOf("F-001", "F-002"),
      )?.contains("duplicate finding_id: F-001.") == true,
    )
  }

  @Test
  fun `Minor-only verified dispositions still settle findings_verified`() {
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      mapOf(
        "produced_outputs" to mapOf(
          "finding_dispositions" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "disposition" to "verified",
              "reason" to "Nit still matches spec intent.",
              "severity" to "nit",
              "location" to "Example.kt",
              "message" to "Naming nit",
            ),
          ),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings worktree gate rejects any changed path`() {
    val reason = FeatureTaskRuntimeVerificationGateReasons.verifyFindingsWorktree(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      FeatureTaskRuntimePhaseFileManifest(before = listOf("A.kt"), after = listOf("A.kt", "B.kt")),
    )
    assertNotNull(reason)
    assertTrue(reason.contains("verify_findings must not edit the worktree"))
    assertTrue(reason.contains("B.kt"))
  }

  @Test
  fun `verify_findings worktree gate allows an unchanged tree`() {
    val reason = FeatureTaskRuntimeVerificationGateReasons.verifyFindingsWorktree(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      FeatureTaskRuntimePhaseFileManifest(before = listOf("A.kt"), after = listOf("A.kt")),
    )
    assertNull(reason)
  }

  @Test
  fun `validateDispositionCoverage accepts empty review and empty dispositions`() {
    assertNull(validateDispositionCoverage(emptyList(), emptySet()))
  }

  @Test
  fun `validateDispositionCoverage rejects foreign disposition when review findings are empty`() {
    val dispositions = listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches spec intent.",
        severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
        location = "Example.kt",
        message = "Foreign finding",
      ),
    )
    assertTrue(
      validateDispositionCoverage(
        dispositions,
        emptySet(),
      )?.contains("absent from the preceding review pass: F-001") == true,
    )
  }

  @Test
  fun `finding verification disposition coverage rejects omitted review findings`() {
    val dispositions = listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches spec intent.",
        severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
        location = "Example.kt",
        message = "Finding one",
      ),
    )
    assertTrue(
      validateDispositionCoverage(dispositions, setOf("F-001", "F-002"))?.contains("omitted finding_id: F-002") == true,
    )
  }

  @Test
  fun `blank reason fails with named verification record error`() {
    assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(
        mapOf(
          "finding_id" to "F-001",
          "disposition" to "verified",
          "reason" to "   ",
          "severity" to "major",
          "location" to "Example.kt",
          "message" to "Finding",
        ),
        "finding_dispositions[0]",
      )
    }
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
  fun `invalid severity loud-fails with named verification record error`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(
        mapOf(
          "finding_id" to "F-001",
          "disposition" to "verified",
          "reason" to "Matches spec intent.",
          "severity" to "catastrophic",
          "location" to "Example.kt",
          "message" to "Finding",
        ),
        "finding_verification_checkpoint[0]",
      )
    }
    assertTrue(error.reason.contains("severity"))
    assertTrue(error.reason.contains("catastrophic"))
  }

  @Test
  fun `retired disposition field loud-fails with named verification record error`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(
        mapOf(
          "finding_id" to "F-001",
          "verdict" to "verified",
          "reason" to "Matches spec intent.",
          "severity" to "major",
          "location" to "Example.kt",
          "message" to "Finding",
        ),
        "finding_verification_checkpoint[0]",
      )
    }
    assertTrue(error.reason.contains("disposition"))
  }
}
