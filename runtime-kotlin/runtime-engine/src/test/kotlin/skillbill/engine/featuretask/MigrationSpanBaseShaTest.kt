package skillbill.engine.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName

class MigrationSpanBaseShaTest {
  @Test
  fun `collapsed review base on HEAD uses the active checkpoint parent`() {
    val head = "a".repeat(40)
    val parent = "b".repeat(40)
    val active = listOf(checkpoint(head, parent, 0))
    assertEquals(parent, migrationSpanBaseSha(head, head, active))
  }

  @Test
  fun `distinct durable base is preserved`() {
    val head = "a".repeat(40)
    val base = "b".repeat(40)
    assertEquals(base, migrationSpanBaseSha(base, head, emptyList()))
  }

  @Test
  fun `first subtask empty ledger keeps HEAD as the recorded branch base`() {
    val head = "a".repeat(40)
    assertEquals(head, migrationSpanBaseSha(head, head, emptyList()))
  }

  @Test
  fun `a proven non-ancestor durable base defaults the owned span to HEAD`() {
    val provenNonAncestor = WorkflowGitOperationResult(
      status = "ok",
      value = "false",
    )
    assertTrue(unprovenAncestorDefaultsOwnedSpanToHead(provenNonAncestor))
  }

  @Test
  fun `unreadable ancestry still refuses instead of defaulting to HEAD`() {
    val unreadable = WorkflowGitOperationResult(
      status = "error",
      error = "cat-file failed",
    )
    assertFalse(unprovenAncestorDefaultsOwnedSpanToHead(unreadable))
  }

  @Test
  fun `collapsed base without recoverable parent refuses`() {
    val head = "a".repeat(40)
    assertNull(migrationSpanBaseSha(head, head, listOf(checkpoint(head, null, 0))))
  }

  private fun checkpoint(commitSha: String, parentSha: String?, sequence: Int) =
    FeatureTaskRuntimeCheckpointIdentity(
      sequenceNumber = sequence,
      issueKey = "SKILL-233",
      subtaskId = "6",
      checkpointRef = featureTaskRuntimeCheckpointRefName("SKILL-233", "6", sequence),
      branch = "feat/SKILL-233-runtime-architecture-health-remediation",
      phaseId = "audit",
      generation = 0,
      ownedPathDigest = "0".repeat(64),
      ownedPathCount = 0,
      commitSha = commitSha,
      recordedAt = "2026-09-10T00:00:00Z",
      parentSha = parentSha,
    )
}
