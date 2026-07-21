package skillbill.application.review

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.REVIEW_RULE_EXCERPT_MAX_CHARS
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewEvidenceTarget
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLearningsReference
import skillbill.review.context.model.ReviewPacketConsumerContract
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.ReviewRuleReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReviewPacketProjectionTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")
  private val rule = ReviewRuleReference("rule-1", "AGENTS.md", "Prefer named strategies.", "b".repeat(64))
  private val learning = ReviewLearningsReference("learn-1", "telemetry", "c".repeat(64))
  private val revision = ReviewRevision("rvs-1", 3)

  private fun packet(
    hunks: List<ReviewChangedHunk> = listOf(hunkA, hunkB),
    lanes: List<String> = listOf("security", "testing"),
    decisions: List<ReviewLaneDecision> = lanes.map { ReviewLaneDecision(it, true, "routed") },
    allowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
  ) = ReviewContextPacket(
    reviewId = "review",
    repositoryIdentity = "acme/repo",
    baseRevision = "base",
    headRevision = "head",
    status = "clean",
    stack = "kotlin",
    pack = "kotlin",
    addOns = listOf("z-addon", "a-addon"),
    selectedLanes = lanes,
    changedHunks = hunks,
    reviewRevision = revision,
    laneDecisions = decisions,
    matchedRules = listOf(rule),
    learningsReferences = listOf(learning),
    buildTestFacts = listOf(ReviewBuildTestFact("test", "gradle test", "passed")),
    dependencyAllowlist = allowlist,
    evidenceTargets = listOf(ReviewEvidenceTarget("src/A.kt", "src/A.kt", listOf(hunkA.hunkId))),
  )

  @Test fun `hunk ids are content addressed not positional`() {
    assertEquals(hunkA.hunkId, ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha").hunkId)
    assertEquals(
      ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha\n").hunkId,
      ReviewChangedHunk("src\\A.kt", 1, 1, 1, 2, "+alpha\r\n").hunkId,
    )
    assertNotEquals(hunkA.hunkId, ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alphaX").hunkId)
    assertTrue(hunkA.hunkId.matches(Regex("[a-f0-9]{64}")))
  }

  @Test fun `packet digest is order insensitive and revision sensitive`() {
    assertEquals(packet(listOf(hunkA, hunkB)).digest, packet(listOf(hunkB, hunkA)).digest)
    assertNotEquals(
      packet().digest,
      packet().copy(reviewRevision = ReviewRevision("rvs-1", 4)).digest,
    )
    assertNotEquals(packet().digest, packet(allowlist = ReviewDependencyAllowlist.EMPTY).digest)
  }

  @Test fun `parent packet envelope is deterministic and schema shaped`() {
    val first = packet(listOf(hunkA, hunkB)).toParentPacketEnvelope().asWireMap()
    val second = packet(listOf(hunkB, hunkA)).toParentPacketEnvelope().asWireMap()
    assertEquals(first.keys.toList(), second.keys.toList())
    assertEquals(first, second)
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, first["contract_version"])
    assertEquals("parent_packet", first["kind"])
    assertEquals(listOf("a-addon", "z-addon"), first["add_ons"])
    assertEquals(listOf("security", "testing"), first["selected_lanes"])
    assertEquals(listOf("src/Dep.kt"), first["dependency_allowlist"])
  }

  @Test fun `lane decisions must cover exactly the selected lanes`() {
    assertFailsWith<IllegalArgumentException> {
      packet(decisions = listOf(ReviewLaneDecision("security", true, "routed")))
    }
    val withExclusion = packet(
      lanes = listOf("security"),
      decisions = listOf(
        ReviewLaneDecision("security", true, "routed"),
        ReviewLaneDecision("testing", false, "no test files changed"),
      ),
    )
    assertEquals(listOf("security"), withExclusion.selectedLanes)
  }

  @Test fun `lane decisions require a reason`() {
    assertFailsWith<IllegalArgumentException> { ReviewLaneDecision("security", false, "  ") }
  }

  @Test fun `expansion records require a reachability reason`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewExpansionRecord("exp-1", "d".repeat(64), "src/C.kt", " ", true, 0)
    }
  }

  @Test fun `rule excerpts are bounded and digested`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewRuleReference("rule-1", "AGENTS.md", "x".repeat(REVIEW_RULE_EXCERPT_MAX_CHARS + 1), "b".repeat(64))
    }
    assertFailsWith<IllegalArgumentException> { ReviewRuleReference("rule-1", "AGENTS.md", "ok", "not-a-digest") }
  }

  @Test fun `dependency allowlists reject traversal and duplicates`() {
    assertFailsWith<IllegalArgumentException> { ReviewDependencyAllowlist(listOf("../secret")) }
    assertFailsWith<IllegalArgumentException> { ReviewDependencyAllowlist(listOf("src/A.kt", "src\\A.kt")) }
  }

  @Test fun `assignment rejects dependency entries overlapping assigned paths`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        reviewId = "review",
        packetDigest = "a".repeat(64),
        lane = "security",
        baseRevision = "base",
        headRevision = "head",
        assignedPaths = listOf("src/A.kt"),
        assignedHunks = listOf(hunkA.hunkId),
        dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/A.kt")),
      )
    }
  }

  @Test fun `assignment envelope omits its own digest from the digest input`() {
    val base = packet()
    val assignment = ReviewAssignment(
      reviewId = base.reviewId,
      packetDigest = base.digest,
      lane = "security",
      baseRevision = base.baseRevision,
      headRevision = base.headRevision,
      assignedPaths = listOf("src/A.kt", "src/B.kt"),
      assignedHunks = listOf(hunkB.hunkId, hunkA.hunkId),
      matchedRules = listOf(rule),
      evidenceTargets = base.evidenceTargets,
      reviewRevision = revision,
      laneDecision = ReviewLaneDecision("security", true, "routed"),
      dependencyAllowlist = base.dependencyAllowlist,
    )
    val envelope = assignment.toAssignmentEnvelope().asWireMap()
    assertEquals(assignment.digest, envelope["assignment_digest"])
    assertEquals(listOf(hunkA.hunkId, hunkB.hunkId).sorted(), envelope["assigned_hunks"])
    val reordered = assignment.copy(assignedHunks = listOf(hunkA.hunkId, hunkB.hunkId))
    assertEquals(assignment.digest, reordered.digest)
  }

  @Test fun `launch envelope carries the forbidden rediscovery list`() {
    val base = packet()
    val assignment = ReviewAssignment(
      reviewId = base.reviewId,
      packetDigest = base.digest,
      lane = "security",
      baseRevision = base.baseRevision,
      headRevision = base.headRevision,
      assignedPaths = listOf("src/A.kt"),
      assignedHunks = listOf(hunkA.hunkId),
      reviewRevision = revision,
    )
    val envelope = GovernedReviewLaunch(assignment, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
      .toLaunchEnvelope().asWireMap()
    assertEquals(ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY, envelope["forbidden_rediscovery"])
    assertEquals("fresh", envelope["isolation"])
    assertEquals(mapOf("session_id" to "rvs-1", "run_revision" to 3), envelope["review_revision"])
  }
}
