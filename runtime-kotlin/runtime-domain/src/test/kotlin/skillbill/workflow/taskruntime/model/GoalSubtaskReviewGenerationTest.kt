package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalSubtaskReviewGenerationTest {
  @Test
  fun `generation identity contains the reviewed delta and checkpoint`() {
    val identity = GoalSubtaskReviewGenerationIdentity(
      workflowId = "workflow-1",
      generationId = "generation-1",
      reviewBase = "a".repeat(40),
      reviewedDeltaDigest = "b".repeat(64),
      repositoryCheckpoint = "checkpoint-1",
    )

    assertEquals("workflow-1", identity.workflowId)
    assertEquals("a".repeat(40), identity.reviewBase)
    assertEquals("b".repeat(64), identity.reviewedDeltaDigest)
    assertEquals("checkpoint-1", identity.repositoryCheckpoint)
  }

  @Test
  fun `finding metadata and governed disposition stay bounded`() {
    val finding = GoalSubtaskReviewFinding(
      findingId = "producer-attribution",
      severity = "blocker",
      category = "platform-correctness",
      location = "Runtime.kt:42",
      summary = "Producer identity is attributed to the consumer.",
      sourceGenerationId = "generation-1",
    )
    val disposition = GoalSubtaskReviewFindingDispositionRecord(
      workflowId = "workflow-1",
      generationId = "generation-3",
      findingId = finding.findingId,
      disposition = GoalSubtaskReviewFindingDisposition.STILL_PRESENT,
      evidence = listOf("checkpoint-3:Runtime.kt:42"),
    )

    assertTrue(finding.isBlocker)
    assertFalse(disposition.disposition.terminal)
    assertFailsWith<IllegalArgumentException> {
      finding.copy(summary = "raw\nreview output")
    }
  }
}
