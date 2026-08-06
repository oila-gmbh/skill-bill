@file:Suppress("MaxLineLength")

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
import skillbill.ports.review.model.ReviewLaneSelection
import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitLaneDecision
import skillbill.review.context.model.ReviewCommitLaneDisposition
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneAssembledBundle
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.segmentAssembledBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Launch fan-out stays one worker per selected lane regardless of commit or segment count. */
class ParallelReviewFanOutInvariantTest {
  private val hunkTemplate = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")

  private fun decision(lane: String, paths: List<String>) = ReviewLaneDecision(
    lane = lane,
    included = true,
    reason = "routed",
    ownedPaths = paths,
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$lane",
  )

  private fun focusedMatrix(scope: ReviewScopeFacts, lanes: List<String>) = ReviewCommitLaneRoutingMatrix(
    scope.commitUnits.sortedBy { it.orderIndex }.map { it.commitSha },
    lanes,
    scope.commitUnits.sortedBy { it.orderIndex }.flatMap { unit ->
      lanes.map {
        ReviewCommitLaneDecision(unit.commitSha, unit.orderIndex, it, ReviewCommitLaneDisposition.FOCUSED, "focused")
      }
    },
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

      override fun resolveMatchedRules(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = emptyList()
      override fun resolveLearnings(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = emptyList()
      override fun resolveBuildTestFacts(scope: ReviewScopeFacts) = emptyList()
      override fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
        ReviewLaneSelection(decisions, focusedMatrix(scope, decisions.filter { it.included }.map { it.lane }))
    }
    return ReviewPreparationService(
      ReviewFactPorts(ports, ports, ports, ports, ports, ports),
      object : ReviewContextEnvelopeValidator {
        override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
      },
    )
  }

  private fun scopeWithCommitCount(count: Int): ReviewScopeFacts {
    val units = (0 until count).map { index ->
      val sha = if (index == count - 1) "head" else "c$index"
      val parent = if (index == 0) "base" else if (index == count - 1 && count > 1) "c${index - 1}" else "c${index - 1}"
      val hunk = hunkTemplate.copy(path = "src/File$index.kt", content = "+line-$index")
      ReviewCommitUnit(sha, parent, "commit $sha", index, listOf(hunk), ReviewCommitSource.COMMIT_RANGE)
    }
    return ReviewScopeFacts(
      "acme/repo",
      "base",
      "head",
      "clean",
      units.flatMap { it.hunks },
      units,
      ReviewCommitCoverageFact("base", "head", count, chainVerified = true, pathCoverageVerified = true),
    )
  }

  private fun prepare(count: Int): skillbill.application.review.model.ReviewPreparationResult {
    val scope = scopeWithCommitCount(count)
    val paths = scope.changedHunks.map { it.path }.distinct().sorted()
    val decisions = listOf(decision("security", paths), decision("testing", paths))
    return service(scope, decisions).prepare(
      ReviewPreparationRequest(
        reviewId = "review",
        reviewRevision = ReviewRevision("rvs", 1),
        criteriaReferences = emptyMap(),
      ),
    )
  }

  @Test fun `varying commit counts keep launch count equal to selected lane count`() {
    val expectedLaneCount = 2
    listOf(1, 5, 20).forEach { commitCount ->
      val prepared = prepare(commitCount)
      assertEquals(expectedLaneCount, prepared.packet.selectedLanes.size)
      assertEquals(expectedLaneCount, prepared.assignments.size)
    }
  }

  @Test fun `a multi-segment lane still produces exactly one assignment launch`() {
    val prepared = prepare(3)
    val security = prepared.assignments.single { it.lane == "security" }
    val assembled = ReviewLaneAssembledBundle.assemble(security, prepared.packet)
    val segmentation = segmentAssembledBundle(assembled, maxLaneLaunchBytes = 25) { entries -> entries.size * 10L }
    assertTrue(segmentation.segments.size >= 2, "Fixture must force segmentation without multiplying launches.")
    assertEquals(1, prepared.assignments.count { it.lane == "security" })
    assertEquals(prepared.packet.selectedLanes.size, prepared.assignments.size)
    // Real launch construction still yields one GovernedReviewLaunch per assignment.
    GovernedReviewLaunch(
      security,
      prepared.packet,
      "contract",
      "rubric",
      "broker",
      ReviewContextBudgetPolicy.DEFAULT,
    )
  }

  @Test fun `synthesized launch set larger than selected lane count is rejected loudly`() {
    val prepared = prepare(2)
    val extra = prepared.assignments.first().copy(lane = "forged-lane")
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(scopeWithCommitCount(2), prepared.packet.laneDecisions).validateAgainstPacket(
        prepared.packet,
        prepared.assignments + extra,
      )
    }
    assertTrue(
      "synthesized ${prepared.assignments.size + 1} assignment" in failure.message.orEmpty() ||
        "must cover exactly the packet's selected lanes" in failure.message.orEmpty(),
      failure.message.orEmpty(),
    )
  }
}
