package skillbill.contracts.review

import skillbill.application.review.toAssignmentEnvelope
import skillbill.application.review.toLaunchEnvelope
import skillbill.application.review.toParentPacketEnvelope
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewEvidenceTarget
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLearningsReference
import skillbill.review.context.model.ReviewPacketConsumerContract
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.ReviewRuleReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewContextSchemaValidatorTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")
  private val revision = ReviewRevision("rvs-1", 2)
  private val rule = ReviewRuleReference("rule-1", "AGENTS.md", "Prefer named strategies.", "b".repeat(64))

  private val packet = ReviewContextPacket(
    reviewId = "review",
    repositoryIdentity = "acme/repo",
    baseRevision = "base",
    headRevision = "head",
    status = "clean",
    stack = "kotlin",
    pack = "kotlin",
    addOns = listOf("addon-a"),
    selectedLanes = listOf("security"),
    changedHunks = listOf(hunkA, hunkB),
    reviewRevision = revision,
    laneDecisions = listOf(
      ReviewLaneDecision("security", true, "auth surface changed", listOf("src/A.kt")),
      ReviewLaneDecision("ui", false, "no UI files changed"),
    ),
    matchedRules = listOf(rule),
    learningsReferences = listOf(ReviewLearningsReference("learn-1", "telemetry", "c".repeat(64))),
    buildTestFacts = listOf(ReviewBuildTestFact("test", "gradle test", "passed")),
    dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
    evidenceTargets = listOf(ReviewEvidenceTarget("src/A.kt", "src/A.kt", listOf(hunkA.hunkId))),
  )

  private val assignment = ReviewAssignment(
    reviewId = packet.reviewId,
    packetDigest = packet.digest,
    lane = "security",
    baseRevision = packet.baseRevision,
    headRevision = packet.headRevision,
    assignedPaths = listOf("src/A.kt", "src/B.kt"),
    assignedHunks = listOf(hunkA.hunkId, hunkB.hunkId),
    criteriaReferences = listOf("AC-002"),
    matchedRules = listOf(rule),
    evidenceTargets = packet.evidenceTargets,
    reviewRevision = revision,
    laneDecision = packet.laneDecisions.first { it.included },
    dependencyAllowlist = packet.dependencyAllowlist,
  )

  @Test fun `projected envelopes satisfy the canonical schema`() {
    ReviewContextSchemaValidator.validateParentPacket(packet.toParentPacketEnvelope().asWireMap(), "packet")
    ReviewContextSchemaValidator.validateAssignment(assignment.toAssignmentEnvelope().asWireMap(), "assignment")
    val launch = GovernedReviewLaunch(assignment, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    ReviewContextSchemaValidator.validateLaunch(launch.toLaunchEnvelope().asWireMap(), "launch")
  }

  @Test fun `launch envelope carries the governed forbidden rediscovery list`() {
    val launch = GovernedReviewLaunch(assignment, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    assertEquals(
      ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY,
      launch.toLaunchEnvelope().asWireMap()["forbidden_rediscovery"],
    )
  }

  @Test fun `wrong kind discriminator fails loudly`() {
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateAssignment(packet.toParentPacketEnvelope().asWireMap(), "packet")
    }
    assertTrue("kind='parent_packet'" in failure.message.orEmpty())
  }

  @Test fun `missing required fields fail with a field path`() {
    val stripped = packet.toParentPacketEnvelope().asWireMap().toMutableMap().apply { remove("lane_decisions") }
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validate(stripped, "packet")
    }
    assertTrue("lane_decisions" in failure.message.orEmpty())
  }

  @Test fun `unknown additional properties are rejected`() {
    val extended = packet.toParentPacketEnvelope().asWireMap() + ("smuggled_diff" to "@@ -1 +1 @@")
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validate(extended, "packet")
    }
    assertTrue("smuggled_diff" in failure.message.orEmpty())
  }

  @Test fun `blank lane decision reasons are rejected`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["lane_decisions"] = listOf(
      mapOf("lane" to "security", "included" to true, "reason" to "", "signals" to emptyList<String>()),
    )
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }

  @Test fun `blank expansion reachability reasons are rejected`() {
    val envelope = assignment.toAssignmentEnvelope().asWireMap().toMutableMap()
    envelope["expansions"] = listOf(
      mapOf(
        "expansion_id" to "exp-1",
        "assignment_digest" to assignment.digest,
        "requested_path" to "src/C.kt",
        "reachability_reason" to "",
        "authorized" to true,
        "sequence" to 0,
      ),
    )
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "assignment") }
  }

  @Test fun `over long rule excerpts are rejected by the schema`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["matched_rules"] = listOf(
      mapOf(
        "rule_id" to "rule-1",
        "source_path" to "AGENTS.md",
        "excerpt" to "x".repeat(2_001),
        "digest" to "b".repeat(64),
      ),
    )
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }

  @Test fun `traversal dependency paths are rejected by the schema`() {
    val envelope = assignment.toAssignmentEnvelope().asWireMap().toMutableMap()
    envelope["dependency_allowlist"] = listOf("../secret")
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "assignment") }
  }

  @Test fun `positional hunk identifiers are rejected`() {
    val envelope = assignment.toAssignmentEnvelope().asWireMap().toMutableMap()
    envelope["assigned_hunks"] = listOf("@@ -1 +1 @@")
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "assignment") }
  }

  @Test fun `stale contract versions are rejected`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["contract_version"] = "0.1"
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }
}
