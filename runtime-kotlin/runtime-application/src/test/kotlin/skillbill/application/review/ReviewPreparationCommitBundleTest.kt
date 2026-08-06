package skillbill.application.review

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.ReviewBuildTestFactsPort
import skillbill.ports.review.ReviewGuidancePort
import skillbill.ports.review.ReviewLaneSelectionPort
import skillbill.ports.review.ReviewLearningsPort
import skillbill.ports.review.ReviewScopeResolverPort
import skillbill.ports.review.ReviewStackRoutingPort
import skillbill.ports.review.model.ReviewFactPorts
import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleEntry
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLearningsReference
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.ReviewRuleReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Preparation threads commit evidence into packets and per-lane bundles without changing routing. */
class ReviewPreparationCommitBundleTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")

  private fun decision(lane: String, path: String) = ReviewLaneDecision(
    lane = lane,
    included = true,
    reason = "routed",
    ownedPaths = listOf(path),
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$lane",
  )

  private fun service(scope: ReviewScopeFacts, decisions: List<ReviewLaneDecision>): ReviewPreparationService {
    val ports = object :
      ReviewScopeResolverPort,
      ReviewStackRoutingPort,
      ReviewGuidancePort,
      ReviewLearningsPort,
      ReviewBuildTestFactsPort,
      ReviewLaneSelectionPort {
      override fun resolveScope(reviewId: String) = scope
      override fun resolveStackRouting(scope: ReviewScopeFacts) =
        ReviewStackRoutingFacts("kotlin", "kotlin", emptyList(), listOf("kotlin"))

      override fun resolveMatchedRules(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
        emptyList<ReviewRuleReference>()

      override fun resolveLearnings(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
        emptyList<ReviewLearningsReference>()

      override fun resolveBuildTestFacts(scope: ReviewScopeFacts) = emptyList<ReviewBuildTestFact>()
      override fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = decisions
    }
    return ReviewPreparationService(
      ReviewFactPorts(ports, ports, ports, ports, ports, ports),
      object : ReviewContextEnvelopeValidator {
        override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
      },
    )
  }

  private fun request() = ReviewPreparationRequest(
    reviewId = "review",
    reviewRevision = ReviewRevision("rvs", 1),
    criteriaReferences = emptyMap(),
  )

  private val multiCommitScope = ReviewScopeFacts(
    "acme/repo",
    "base",
    "head",
    "clean",
    listOf(hunkA, hunkB),
    listOf(
      ReviewCommitUnit("c1", "base", "first", 0, listOf(hunkA), ReviewCommitSource.COMMIT_RANGE),
      ReviewCommitUnit("head", "c1", "second", 1, listOf(hunkB), ReviewCommitSource.COMMIT_RANGE),
    ),
    ReviewCommitCoverageFact("base", "head", 2, chainVerified = true, pathCoverageVerified = true),
  )

  // AC-001, AC-008
  @Test fun `a multi-commit packet carries ordered units and per-lane bundles`() {
    val result = service(
      multiCommitScope,
      listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt")),
    ).prepare(request())

    assertEquals(listOf("c1", "head"), result.packet.commitUnits.sortedBy { it.orderIndex }.map { it.commitSha })
    val security = result.assignments.single { it.lane == "security" }
    assertEquals(
      ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)))),
      security.assignedBundle,
    )
    val testing = result.assignments.single { it.lane == "testing" }
    assertEquals(
      ReviewLaneBundle(listOf(ReviewLaneBundleEntry("head", 1, listOf(hunkB.hunkId)))),
      testing.assignedBundle,
    )
    assertTrue(result.packet.coverageFact.chainVerified)
  }

  // AC-005
  @Test fun `a staged-scope packet carries exactly one synthetic unit`() {
    val scope = ReviewScopeFacts(
      "acme/repo",
      "base",
      "head",
      "staged",
      listOf(hunkA, hunkB),
      listOf(ReviewCommitUnit.synthetic(ReviewCommitSource.SYNTHETIC_WORKING_TREE, listOf(hunkA, hunkB))),
      ReviewCommitCoverageFact(
        "base",
        "head",
        1,
        chainVerified = false,
        pathCoverageVerified = true,
        degradedReason = "staged scope",
      ),
    )
    val result = service(scope, listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt")))
      .prepare(request())

    assertEquals(1, result.packet.commitUnits.size)
    assertEquals(ReviewCommitSource.SYNTHETIC_WORKING_TREE, result.packet.commitUnits.single().source)
    assertEquals(listOf("src/A.kt"), result.assignments.single { it.lane == "security" }.assignedPaths)
    assertEquals(
      listOf(hunkA.hunkId),
      result.assignments.single { it.lane == "security" }.assignedBundle.hunkIds,
    )
  }

  // AC-003
  @Test fun `validation rejects an assignment claiming a commit unit outside its packet`() {
    val prepared = service(
      multiCommitScope,
      listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt")),
    ).prepare(request())
    val forged = prepared.assignments.map { assignment ->
      if (assignment.lane != "security") {
        assignment
      } else {
        assignment.copy(
          assignedBundle = ReviewLaneBundle(
            listOf(ReviewLaneBundleEntry("not-a-packet-commit", 0, listOf(hunkA.hunkId))),
          ),
        )
      }
    }
    assertFailsWith<InvalidReviewContextSchemaError> {
      service(multiCommitScope, prepared.packet.laneDecisions).validateAgainstPacket(prepared.packet, forged)
    }
  }
}
