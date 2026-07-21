@file:Suppress("MaxLineLength")

package skillbill.review.context.model

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewContextModelsTest {
  private fun lane(name: String, paths: List<String>, reason: String = "routed") =
    ReviewLaneDecision(name, true, reason, ownedPaths = paths)

  private fun revision(session: String = "review", run: Int = 1) = ReviewRevision(session, run)

  private val launchHunk = ReviewChangedHunk("A.kt", 1, 1, 1, 2, "+alpha")

  private fun launchPacket() = ReviewContextPacket(
    "review", "repo", "base", "head", "clean", "kotlin", "kotlin", emptyList(), listOf("security"),
    listOf(launchHunk),
    reviewRevision = revision(),
    laneDecisions = listOf(lane("security", listOf("A.kt"))),
  )

  private fun launchAssignment(packet: ReviewContextPacket, hunks: List<String> = listOf(launchHunk.hunkId)) =
    ReviewAssignment(
      "review", packet.digest, "security", "base", "head", listOf("A.kt"), hunks,
      reviewRevision = revision(), laneDecision = lane("security", listOf("A.kt")),
    )

  @Test fun `default budget is governed`() {
    assertEquals(524_288, ReviewContextBudgetPolicy.DEFAULT.maxParentPacketBytes)
    assertEquals(96_000, ReviewContextBudgetPolicy.DEFAULT.providerTokenThresholds.totalTokens)
  }

  @Test fun `cached input cannot exceed or exist without input`() {
    assertFailsWith<IllegalArgumentException> { ProviderTokenUsage(cachedInputTokens = 1) }
    assertFailsWith<IllegalArgumentException> { ProviderTokenUsage(inputTokens = 1, cachedInputTokens = 2) }
  }

  @Test fun `inconsistent budgets loud fail`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewContextBudgetPolicy(maxLaneEvidenceBytes = 10, maxEvidenceResultBytes = 11)
    }
  }

  @Test fun `assignment digest is stable`() {
    fun value(paths: List<String>, criteria: List<String>) = ReviewAssignment(
      "review", "a".repeat(64), "security", "base", "head", paths, listOf("hunk"), criteria,
      reviewRevision = revision(), laneDecision = lane("security", paths),
    )
    assertEquals(
      value(listOf("b.kt", "a.kt"), listOf("2", "1")).digest,
      value(listOf("a.kt", "b.kt"), listOf("1", "2")).digest,
    )
  }

  @Test fun `assignment digest tracks revision lane decision and dependency allowlist`() {
    val base = ReviewAssignment(
      "review", "a".repeat(64), "security", "base", "head", listOf("A.kt"), listOf("b".repeat(64)),
      reviewRevision = revision(), laneDecision = lane("security", listOf("A.kt")),
    )
    assertEquals(base.digest, base.copy(assignedPaths = listOf("A.kt")).digest)
    assertTrue(base.digest != base.copy(reviewRevision = ReviewRevision("review", 2)).digest)
    assertTrue(
      base.digest != base.copy(dependencyAllowlist = ReviewDependencyAllowlist(listOf("Dep.kt"))).digest,
    )
    assertTrue(
      base.digest != base.copy(laneDecision = lane("security", listOf("A.kt"), "different reason")).digest,
    )
    assertTrue(base.digest != base.copy(laneDecision = lane("security", listOf("B.kt"))).digest)
  }

  @Test fun `assignments reject lane decisions that do not describe the lane`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        "review",
        "a".repeat(64),
        "security",
        "base",
        "head",
        listOf("A.kt"),
        emptyList(),
        reviewRevision = revision(),
        laneDecision = lane("testing", listOf("A.kt")),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        "review",
        "a".repeat(64),
        "security",
        "base",
        "head",
        listOf("A.kt"),
        emptyList(),
        reviewRevision = revision(),
        laneDecision = ReviewLaneDecision("security", false, "excluded"),
      )
    }
  }

  @Test fun `paths reject traversal`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        "review", "a".repeat(64), "security", "base", "head", listOf("../secret"), emptyList(),
        reviewRevision = revision(), laneDecision = lane("security", listOf("A.kt")),
      )
    }
  }

  @Test fun `packet digest normalizes ordering separators and line endings`() {
    fun packet(path: String, status: String) = ReviewContextPacket(
      "review", "repo", "base", "head", status, "kotlin", "kotlin", listOf("z", "a"), listOf("testing"),
      listOf(ReviewChangedHunk(path, 1, 1, 1, 1, "+line\r\n")),
      reviewRevision = revision(),
      laneDecisions = listOf(lane("testing", listOf(path.replace('\\', '/')))),
    )
    assertEquals(packet("src\\A.kt", "clean\r\n").digest, packet("src/A.kt", "clean\n").digest)
  }

  @Test fun `governed Codex launches reject inherited and omitted turns`() {
    val packet = launchPacket()
    val launch = GovernedReviewLaunch(
      launchAssignment(packet),
      packet,
      "contract",
      "rubric",
      "broker",
      ReviewContextBudgetPolicy.DEFAULT,
    )
    launch.requireCodexForkTurns("none")
    assertFailsWith<IllegalArgumentException> { launch.requireCodexForkTurns(null) }
    assertFailsWith<IllegalArgumentException> { launch.requireCodexForkTurns("all") }
    assertEquals(
      listOf(
        "review_id", "review_revision", "packet_digest", "assignment_digest", "lane", "base_revision",
        "head_revision", "broker_id", "specialist_contract", "rubric", "assigned_paths", "assigned_hunks",
        "criteria_references", "matched_rules", "evidence_targets", "dependency_allowlist",
        "forbidden_rediscovery", "budgets",
      ),
      launch.canonicalPayload.lines().filter { it.isNotBlank() && !it.startsWith("  ") }
        .map { it.substringBefore(':') },
    )
    assertTrue(ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY.all { it in launch.canonicalPayload })
  }

  @Test fun `a launch cannot be projected from an assignment the packet does not attest`() {
    val packet = launchPacket()
    val forged = launchAssignment(packet).copy(packetDigest = "a".repeat(64))
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(forged, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    }
    val widened = launchAssignment(packet).copy(assignedPaths = listOf("A.kt", "Elsewhere.kt"))
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(widened, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    }
  }

  @Test fun `oversized compact launch returns typed budget evidence`() {
    val packet = launchPacket()
    val assignment = launchAssignment(packet, hunks = emptyList())
    val policy =
      ReviewContextBudgetPolicy(
        maxParentPacketBytes = 10_000,
        maxLaneLaunchBytes = 10,
        maxLaneEvidenceBytes = 100,
        maxEvidenceResultBytes = 50,
        maxLaneResultBytes = 50,
      )
    val outcome =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", policy).budgetOutcomeOrNull()
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, outcome?.type)
  }

  @Test fun `inclusive parent is not double counted`() {
    val parent = ProviderTokenUsage(inputTokens = 100, outputTokens = 10, ownership = TokenOwnership.INCLUSIVE)
    assertEquals(
      parent,
      ReviewTreeUsage(parent, listOf(ReviewTreeUsage(ProviderTokenUsage(inputTokens = 50)))).aggregate(),
    )
  }

  @Test fun `fresh tokens separate cached input`() {
    assertEquals(
      70,
      ProviderTokenUsage(inputTokens = 100, cachedInputTokens = 50, outputTokens = 20).freshTokenApproximation,
    )
  }

  @Test fun `explicit mode is authoritative`() {
    val risky = ReviewAutoEligibility(true, true, true)
    assertEquals(CodeReviewExecutionMode.DELEGATED, CodeReviewExecutionMode.DEFAULT)
    assertEquals(
      ResolvedReviewExecutionMode.INLINE,
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.INLINE, risky),
    )
    assertEquals(
      ResolvedReviewExecutionMode.DELEGATED,
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.DELEGATED, ReviewAutoEligibility(false, false, false)),
    )
    assertEquals(
      ResolvedReviewExecutionMode.DELEGATED,
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.AUTO, risky),
    )
  }
}
