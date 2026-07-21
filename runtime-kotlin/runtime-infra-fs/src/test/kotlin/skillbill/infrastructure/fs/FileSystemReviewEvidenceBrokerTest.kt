package skillbill.infrastructure.fs

import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.review.context.model.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewRevision
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FileSystemReviewEvidenceBrokerTest {
  @Test fun `assigned reads are bounded and excessive evidence terminates typed`() {
    val root = Files.createTempDirectory("review-evidence")
    Files.writeString(root.resolve("A.kt"), "assigned")
    val broker = FileSystemReviewEvidenceBroker(
      root,
      assignment(listOf("A.kt")),
      emptySet(),
      policy(result = 4, cumulative = 8),
    )
    val result = broker.read(ReviewEvidenceRequest("security", "A.kt"))
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, result.budgetExceeded?.type)
    assertEquals(null, result.content)
    val repeated = broker.read(ReviewEvidenceRequest("security", "A.kt"))
    assertSame(result.budgetExceeded, repeated.budgetExceeded)
  }

  @Test fun `lane result excess terminates subsequent evidence`() {
    val root = Files.createTempDirectory("review-result")
    Files.writeString(root.resolve("A.kt"), "ok")
    val broker = FileSystemReviewEvidenceBroker(root, assignment(listOf("A.kt")), emptySet(), policy())
    assertEquals("lane_result_bytes", broker.validateLaneResult("x".repeat(101))?.budgetKind)
    assertEquals("lane_result_bytes", broker.read(ReviewEvidenceRequest("security", "A.kt")).budgetExceeded?.budgetKind)
  }

  @Test fun `out of assignment reads require reason and consume expansion`() {
    val root = Files.createTempDirectory("review-evidence")
    Files.writeString(root.resolve("B.kt"), "dep")
    val broker = FileSystemReviewEvidenceBroker(root, assignment(emptyList()), emptySet(), policy(expansions = 0))
    assertFailsWith<IllegalArgumentException> { broker.read(ReviewEvidenceRequest("security", "B.kt")) }
    val result = broker.read(ReviewEvidenceRequest("security", "B.kt", "called by assigned symbol"))
    assertEquals("assignment_expansions", result.budgetExceeded?.budgetKind)
  }

  @Test fun `named direct dependency does not consume expansion`() {
    val root = Files.createTempDirectory("review-evidence")
    Files.writeString(root.resolve("B.kt"), "dep")
    val broker = FileSystemReviewEvidenceBroker(root, assignment(emptyList()), setOf("B.kt"), policy())
    assertEquals("dep", broker.read(ReviewEvidenceRequest("security", "B.kt")).content)
  }

  private fun assignment(paths: List<String>) = ReviewAssignment(
    "review",
    "a".repeat(64),
    "security",
    "base",
    "head",
    paths,
    emptyList(),
    reviewRevision = ReviewRevision("rvs-1", 1),
    laneDecision = ReviewLaneDecision(
      "security",
      true,
      "routed",
      ownedPaths = paths.ifEmpty { listOf("A.kt") },
    ),
  )
  private fun policy(result: Long = 100, cumulative: Long = 200, expansions: Int = 1) = ReviewContextBudgetPolicy(
    maxParentPacketBytes = 1_000,
    maxLaneLaunchBytes = 500,
    maxLaneEvidenceBytes = cumulative,
    maxEvidenceResultBytes = result,
    maxLaneResultBytes = 100,
    maxAssignmentExpansions = expansions,
  )
}
