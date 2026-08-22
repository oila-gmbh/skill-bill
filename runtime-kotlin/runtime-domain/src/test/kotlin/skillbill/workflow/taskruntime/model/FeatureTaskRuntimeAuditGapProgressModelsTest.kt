package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidWorkflowStateSchemaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditGapProgressModelsTest {
  @Test
  fun `audit-gap progress round-trips through its artifact map`() {
    val progress = FeatureTaskRuntimeAuditGapProgress(
      criterionRefs = setOf("AC-002", "AC-001"),
      repositoryFingerprint = "fingerprint-1",
    )
    val decoded = FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(progress.toArtifactMap())
    assertEquals(setOf("AC-001", "AC-002"), decoded.criterionRefs)
    assertEquals("fingerprint-1", decoded.repositoryFingerprint)
  }

  @Test
  fun `audit-gap progress round-trips a null fingerprint`() {
    val progress = FeatureTaskRuntimeAuditGapProgress(criterionRefs = setOf("AC-002"))
    val decoded = FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(progress.toArtifactMap())
    assertEquals(setOf("AC-002"), decoded.criterionRefs)
    assertEquals(null, decoded.repositoryFingerprint)
  }

  @Test
  fun `audit-gap progress decode loud-fails on a malformed key set`() {
    val map = FeatureTaskRuntimeAuditGapProgress(setOf("AC-002")).toArtifactMap().toMutableMap()
    map["previous_criterion_refs"] = listOf(42)
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(map)
    }
  }

  @Test
  fun `audit-gap progress decode loud-fails on an unknown key`() {
    val map = FeatureTaskRuntimeAuditGapProgress(setOf("AC-002")).toArtifactMap().toMutableMap()
    map["unexpected"] = "x"
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(map)
    }
  }

  @Test
  fun `audit-gap pause round-trips through its artifact map`() {
    val pause = FeatureTaskRuntimeAuditGapPause(
      pauseKind = AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD,
      reason = "crossed threshold",
      edgeIteration = 4,
      operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX,
      grantConsumed = false,
    )
    val decoded = FeatureTaskRuntimeAuditGapPause.fromArtifactMap(pause.toArtifactMap())
    assertEquals(pause, decoded)
    assertTrue(decoded.toArtifactMap() == pause.toArtifactMap())
  }

  @Test
  fun `audit-gap pause rejects accept-and-advance and bad kinds`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGapPause(
        pauseKind = "accept_and_advance",
        reason = "x",
        edgeIteration = 1,
      )
    }
  }
}
