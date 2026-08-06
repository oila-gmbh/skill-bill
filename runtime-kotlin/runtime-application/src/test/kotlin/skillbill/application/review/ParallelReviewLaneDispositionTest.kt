package skillbill.application.review

import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitLaneDecision
import skillbill.review.context.model.ReviewCommitLaneDisposition
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleEntry
import skillbill.review.context.model.ReviewLaneBundleSegmentation.Companion.UNREVIEWABLE_SEGMENT_ID
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.ReviewRevision
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lane disposition and segment accounting without driving the full parallel runner. */
class ParallelReviewLaneDispositionTest {
  private val hunk = ReviewChangedHunk("src/A.kt", 1, 1, 1, 1, "+${"x".repeat(400)}")
  private val unit = ReviewCommitUnit("head", "base", "large hunk", 0, listOf(hunk), ReviewCommitSource.COMMIT_RANGE)
  private val decision = ReviewLaneDecision(
    "security",
    true,
    "routed",
    ownedPaths = listOf("src/A.kt"),
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-security",
  )
  private val packet = ReviewContextPacket(
    reviewId = "review",
    repositoryIdentity = "repo",
    baseRevision = "base",
    headRevision = "head",
    status = "clean",
    stack = "kotlin",
    pack = "kotlin",
    addOns = emptyList(),
    selectedLanes = listOf("security"),
    changedHunks = listOf(hunk),
    commitUnits = listOf(unit),
    coverageFact = ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
    routingMatrix = ReviewCommitLaneRoutingMatrix(
      listOf("head"),
      listOf("security"),
      listOf(ReviewCommitLaneDecision("head", 0, "security", ReviewCommitLaneDisposition.FOCUSED, "focused")),
    ),
    reviewRevision = ReviewRevision("rvs", 1),
    laneDecisions = listOf(decision),
  )
  private val assignment = ReviewAssignment(
    reviewId = packet.reviewId,
    packetDigest = packet.digest,
    lane = "security",
    baseRevision = packet.baseRevision,
    headRevision = packet.headRevision,
    assignedPaths = listOf("src/A.kt"),
    assignedHunks = listOf(hunk.hunkId),
    assignedBundle = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("head", 0, listOf(hunk.hunkId)))),
    laneRouting = packet.routingMatrix.decisionsFor("security"),
    reviewRevision = packet.reviewRevision,
    laneDecision = decision,
  )

  private fun governedLaunch(budgetBytes: Long = 50) = GovernedReviewLaunch(
    assignment,
    packet,
    "contract",
    "rubric",
    "broker",
    ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneLaunchBytes = budgetBytes),
  )

  @Test fun `budget-exhausted lane terminates incomplete and names every unreviewed segment id`() {
    val launch = governedLaunch()
    val completion = launch.completionState
    val survivingFinding = ParallelReviewRawFinding(
      ParallelReviewSeverity.MAJOR,
      "High",
      "src/A.kt:1",
      "observed before budget exhaustion",
      repositoryPath = "src/A.kt",
      line = 1,
    )
    val outcome = ParallelReviewLaneOutcome(
      success = true,
      rawOutput = "- [F-001] Major | High | path=\"src/A.kt\" | line=1 | observed before budget exhaustion",
      reviewDisposition = completion.disposition,
      bundleCompositionDigest = completion.bundleCompositionDigest,
      segmentAccounting = completion.segments,
      unreviewedSegmentIds = completion.unreviewedSegmentIds,
      budgetDimension = completion.budgetDimension,
      findings = listOf(survivingFinding),
    )

    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, outcome.reviewDisposition)
    assertEquals(listOf(UNREVIEWABLE_SEGMENT_ID), outcome.unreviewedSegmentIds)
    assertEquals("lane_launch_bytes", outcome.budgetDimension)
    assertEquals(1, outcome.findings.size)
    assertTrue(outcome.segmentAccounting.any { it.segmentId == UNREVIEWABLE_SEGMENT_ID })
  }

  @Test fun `incomplete lane is never clean coverage and differs from a zero-finding complete lane`() {
    val incomplete = governedLaunch().completionState
    val complete = governedLaunch(budgetBytes = 100_000).completionState
    val completeOutcome = ParallelReviewLaneOutcome(
      success = true,
      rawOutput = "",
      reviewDisposition = complete.disposition,
      bundleCompositionDigest = complete.bundleCompositionDigest,
      segmentAccounting = complete.segments,
    )
    val incompleteOutcome = ParallelReviewLaneOutcome(
      success = true,
      rawOutput = "- [F-001] Major | High | path=\"src/A.kt\" | line=1 | partial",
      reviewDisposition = incomplete.disposition,
      bundleCompositionDigest = incomplete.bundleCompositionDigest,
      segmentAccounting = incomplete.segments,
      unreviewedSegmentIds = incomplete.unreviewedSegmentIds,
      budgetDimension = incomplete.budgetDimension,
      findings = listOf(
        ParallelReviewRawFinding(
          ParallelReviewSeverity.MAJOR,
          "High",
          "src/A.kt:1",
          "partial",
          repositoryPath = "src/A.kt",
          line = 1,
        ),
      ),
    )

    assertFalse(incomplete.isCleanCoverage)
    assertTrue(complete.isCleanCoverage)
    assertTrue(completeOutcome.findings.isEmpty())
    assertFalse(incompleteOutcome.findings.isEmpty())
    assertEquals(ReviewLaneReviewDisposition.COMPLETE, completeOutcome.reviewDisposition)
    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, incompleteOutcome.reviewDisposition)
  }

  @Test fun `completion state carries per-segment accounting`() {
    val launch = governedLaunch(budgetBytes = 100_000)
    val completion = launch.completionState

    assertEquals(ReviewLaneReviewDisposition.COMPLETE, completion.disposition)
    assertTrue(completion.segments.isNotEmpty())
    completion.segments.forEach { segment ->
      assertTrue(segment.measuredBytes >= 0)
      assertTrue(segment.entryCount >= 0)
      assertTrue(segment.compositionDigest.matches(Regex("[a-f0-9]{64}")))
    }
    assertEquals(launch.assembledBundle.compositionDigest, completion.bundleCompositionDigest)
  }

  @Test fun `resume selection keeps only incomplete durable lanes`() {
    val complete = skillbill.review.model.ReviewRunLane(
      laneSkillName = "bill-kotlin-code-review-security",
      packSlug = "kotlin",
      area = "security",
      depth = 0,
      required = false,
      orderIndex = 0,
      originLayerChain = listOf("kotlin"),
      resolutionState = skillbill.review.ReviewRunLaneResolver.RESOLVED,
      reviewDisposition = skillbill.review.ReviewRunLaneResolver.COMPLETE_DISPOSITION,
      bundleCompositionDigest = "a".repeat(64),
    )
    val incomplete = complete.copy(
      laneSkillName = "bill-kotlin-code-review-testing",
      area = "testing",
      reviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE.wireValue,
      unreviewedSegmentIds = listOf(UNREVIEWABLE_SEGMENT_ID),
      budgetDimension = "lane_launch_bytes",
    )
    assertEquals(
      listOf(incomplete),
      ReviewRunLaneResolver.lanesToResume(listOf(complete, incomplete)),
    )
  }
}
