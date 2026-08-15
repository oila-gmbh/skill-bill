package skillbill.application.review

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitLaneDecision
import skillbill.review.context.model.ReviewCommitLaneDisposition
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewPacketConsumerContract
import skillbill.review.context.model.ReviewRevision
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReviewClaimVerificationRunnerTest {
  @Test
  fun `each finding launches alone without siblings narrative or parent transcript`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val envelopes = mutableListOf<Map<String, Any?>>()
    val outcome = runner(
      launcher = { request ->
        launches += request
        facts(request, CONFIRMED)
      },
      validator = { envelope, _ -> envelopes += envelope },
    ).run(
      packet = packet(),
      findings = listOf(finding("F-001"), finding("F-002", "src/B.kt:4", "other bug")),
      existingVerdicts = emptyList(),
      mode = ResolvedReviewExecutionMode.INLINE,
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("verify-isolation"),
      timeout = 1.seconds,
    )
    assertEquals(2, launches.size)
    assertEquals(2, envelopes.size)
    envelopes.forEach { envelope ->
      assertEquals("verification_launch", envelope["kind"])
      assertFalse(envelope.containsKey("spec_intent_projection"))
      assertFalse(envelope.containsKey("narrative"))
      assertFalse(envelope.containsKey("transcript"))
      assertFalse(envelope.containsKey("parent_transcript"))
      val finding = envelope["finding"] as Map<*, *>
      assertEquals(setOf("finding_ref", "severity", "location", "description", "confidence"), finding.keys)
    }
    val refs = envelopes.map { (it["finding"] as Map<*, *>)["finding_ref"] }
    assertEquals(listOf("F-001", "F-002"), refs)
    launches.forEachIndexed { index, launch ->
      val prompt = launch.skillRunRequest.promptOverride.orEmpty()
      val own = refs[index] as String
      val other = refs[1 - index] as String
      assertTrue(own in prompt)
      assertFalse(other in prompt)
    }
    assertEquals(listOf("F-001", "F-002"), outcome.verdicts.map { it.findingRef })
    assertTrue(outcome.verdicts.all { it.claimVerdict == ReviewClaimVerdict.CONFIRMED })
  }

  @Test
  fun `a worker that fails to spawn times out or returns unparseable output leaves the finding unresolved`() {
    val responses = ArrayDeque(
      listOf(
        factsFor { copy(spawnFailed = true, exitStatus = null, stdout = "") },
        factsFor { copy(timedOut = true, exitStatus = null, stdout = "") },
        factsFor { copy(stdout = "not a verdict") },
      ),
    )
    val outcome = runner(
      launcher = { request -> responses.removeFirst().invoke(request) },
    ).run(
      packet = packet(),
      findings = listOf(
        finding("F-001"),
        finding("F-002", "src/B.kt:4", "second"),
        finding("F-003", "src/C.kt:8", "third"),
      ),
      existingVerdicts = emptyList(),
      mode = ResolvedReviewExecutionMode.DELEGATED,
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("verify-failure"),
      timeout = 1.seconds,
    )
    assertEquals(3, outcome.verdicts.size)
    assertTrue(outcome.verdicts.all { it.claimVerdict == ReviewClaimVerdict.UNRESOLVED })
    assertEquals("agent process failed to spawn", outcome.verdicts[0].rejectionReason)
    assertEquals("agent timed out", outcome.verdicts[1].rejectionReason)
    assertEquals("unparseable verification output", outcome.verdicts[2].rejectionReason)
  }

  @Test
  fun `inline bounds evidence to the cited region while delegated permits brokered expansion`() {
    val inline = envelopesFor(ResolvedReviewExecutionMode.INLINE)
    val delegated = envelopesFor(ResolvedReviewExecutionMode.DELEGATED)
    val inlineRules = inline.single()["evidence_surface_rules"] as String
    val delegatedRules = delegated.single()["evidence_surface_rules"] as String
    assertEquals(ReviewPacketConsumerContract.INLINE_VERIFICATION_EVIDENCE_SURFACE, inlineRules)
    assertEquals(ReviewPacketConsumerContract.DELEGATED_VERIFICATION_EVIDENCE_SURFACE, delegatedRules)
    assertTrue("Cited region and direct callers" in inlineRules)
    assertFalse("expansion ledger is permitted" in inlineRules)
    assertTrue("expansion ledger is permitted" in delegatedRules)
    assertEquals(inline.single()["finding"], delegated.single()["finding"])
    assertFalse(inline.single().containsKey("spec_intent_projection"))
    assertFalse(delegated.single().containsKey("spec_intent_projection"))
  }

  private fun envelopesFor(mode: ResolvedReviewExecutionMode): List<Map<String, Any?>> {
    val envelopes = mutableListOf<Map<String, Any?>>()
    runner(
      launcher = { request -> facts(request, CONFIRMED) },
      validator = { envelope, _ -> envelopes += envelope },
    ).run(
      packet = packet(),
      findings = listOf(finding("F-001")),
      existingVerdicts = emptyList(),
      mode = mode,
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("verify-depth"),
      timeout = 1.seconds,
    )
    return envelopes
  }

  private fun runner(
    launcher: GoalRunnerSubtaskLauncher,
    validator: ReviewContextEnvelopeValidator = ReviewContextEnvelopeValidator { _, _ -> },
  ) = ReviewClaimVerificationRunner(launcher, validator)

  private fun facts(request: GoalRunnerSubtaskLaunchRequest, stdout: String) = AgentRunLaunchFacts(
    agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
    exitStatus = 0,
    stdout = stdout,
    stderr = "",
    timedOut = false,
    spawnFailed = false,
  )

  private fun factsFor(
    mutate: AgentRunLaunchFacts.() -> AgentRunLaunchFacts,
  ): (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchFacts = { request ->
    facts(request, CONFIRMED).mutate()
  }

  private fun finding(ref: String, location: String = "src/A.kt:12", description: String = "Null is not checked.") =
    ParallelReviewMergedFinding(
      fNumber = ref,
      agentIds = listOf("codex"),
      severity = ParallelReviewSeverity.MAJOR,
      confidence = "High",
      location = location,
      description = description,
      repositoryPath = location.substringBefore(':'),
      line = location.substringAfter(':').toInt(),
    )

  private fun packet(): ReviewContextPacket {
    val hunk = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
    val lanes = listOf("security")
    return ReviewContextPacket(
      reviewId = "review",
      repositoryIdentity = "repo",
      baseRevision = "base",
      headRevision = "head",
      status = "clean",
      stack = "kotlin",
      pack = "kotlin",
      addOns = emptyList(),
      selectedLanes = lanes,
      changedHunks = listOf(hunk),
      commitUnits = listOf(
        ReviewCommitUnit("head", "base", "change", 0, listOf(hunk), ReviewCommitSource.COMMIT_RANGE),
      ),
      coverageFact = ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
      routingMatrix = ReviewCommitLaneRoutingMatrix(
        listOf("head"),
        lanes,
        listOf(ReviewCommitLaneDecision("head", 0, "security", ReviewCommitLaneDisposition.FOCUSED, "focused")),
      ),
      reviewRevision = ReviewRevision("rvs-1", 1),
      laneDecisions = listOf(
        ReviewLaneDecision(
          "security",
          true,
          "routed",
          ownedPaths = listOf("src/A.kt"),
          originLayerChains = listOf(listOf("kotlin")),
          owningPack = "kotlin",
          specialistSkillName = "bill-kotlin-code-review-security",
        ),
      ),
      dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
    )
  }

  private companion object {
    const val CONFIRMED: String = """{"claim_verdict":"confirmed"}"""
  }
}
