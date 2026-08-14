package skillbill.application.review

import skillbill.domain.review.context.model.SpecIntentProjection
import skillbill.domain.review.context.model.SpecIntentProvenance
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.ReviewContextEnvelopeValidator
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
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.ReviewSpecAdjudicationAdmission
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewStage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReviewSpecAdjudicationRunnerTest {
  @Test
  fun `a refuted finding is not adjudicated`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val outcome = runner(launcher = { request ->
      launches += request
      facts(request, IN_SCOPE)
    }).run(
      packet = packet(),
      findings = listOf(finding("F-001"), finding("F-002", "src/B.kt:4", "other bug")),
      existingVerdicts = listOf(
        stage1("F-001", ReviewClaimVerdict.REFUTED),
        stage1("F-002", ReviewClaimVerdict.CONFIRMED),
      ),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-refuted"),
      timeout = 1.seconds,
    )
    assertEquals(1, launches.size)
    val prompt = launches.single().skillRunRequest.promptOverride.orEmpty()
    assertTrue("F-002" in prompt)
    assertFalse("F-001" in prompt)
    assertEquals(listOf("F-002"), outcome.verdicts.map { it.findingRef })
  }

  @Test
  fun `no projection skips the stage and the run completes`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val outcome = runner(launcher = { request ->
      launches += request
      facts(request, IN_SCOPE)
    }).run(
      packet = packet(),
      findings = listOf(finding("F-001")),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = null,
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-none"),
      timeout = 1.seconds,
    )
    assertTrue(launches.isEmpty())
    assertTrue(outcome.verdicts.isEmpty())
    assertEquals(ReviewSpecAdjudicationRunner.SPEC_CONTEXT_NONE, outcome.skipReason)
  }

  @Test
  fun `an uncited downgrade is recorded in_scope and the finding survives`() {
    val claim = finding("F-001")
    val outcome = runner(launcher = { request -> facts(request, UNCITED_DOWNGRADE) }).run(
      packet = packet(),
      findings = listOf(claim),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-uncited"),
      timeout = 1.seconds,
    )
    val verdict = outcome.verdicts.single()
    assertEquals(ReviewScopeDisposition.IN_SCOPE, verdict.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.UNCITED_DOWNGRADE, verdict.rejectionReason)
    assertNull(verdict.severityAdjustment)
    assertEquals(ParallelReviewSeverity.MAJOR, claim.severity)
    assertEquals(ReviewClaimVerdict.CONFIRMED, verdict.claimVerdict)
  }

  @Test
  fun `an upward adjustment uses the same delta structure as a downward one`() {
    val raise = runner(launcher = { request -> facts(request, RAISE) }).run(
      packet = packet(),
      findings = listOf(finding("F-001")),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-raise"),
      timeout = 1.seconds,
    ).verdicts.single()
    val lower = runner(launcher = { request -> facts(request, LOWER) }).run(
      packet = packet(),
      findings = listOf(finding("F-001")),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-lower"),
      timeout = 1.seconds,
    ).verdicts.single()
    assertEquals(ReviewScopeDisposition.SPEC_DEVIATION, raise.scopeDisposition)
    assertEquals(ReviewSeverityAdjustmentDirection.RAISE, raise.severityAdjustment?.direction)
    assertEquals(ReviewSeverityAdjustmentDirection.LOWER, lower.severityAdjustment?.direction)
    assertTrue(raise.citations.isNotEmpty())
    assertTrue(lower.citations.isNotEmpty())
    assertEquals(raise.citations, lower.citations)
    assertTrue(!raise.severityAdjustment!!.justification.isBlank())
    assertTrue(!lower.severityAdjustment!!.justification.isBlank())
  }

  @Test
  fun `the original claim is unmodified after a severity adjustment`() {
    val claim = finding("F-001")
    val before = claim.copy()
    val verdict = runner(launcher = { request -> facts(request, RAISE) }).run(
      packet = packet(),
      findings = listOf(claim),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-preserve"),
      timeout = 1.seconds,
    ).verdicts.single()
    assertEquals(before, claim)
    assertEquals(ParallelReviewSeverity.MAJOR, claim.severity)
    assertEquals("src/A.kt:12", claim.location)
    assertEquals("Null is not checked.", claim.description)
    assertEquals(ReviewSeverityAdjustmentDirection.RAISE, verdict.severityAdjustment?.direction)
  }

  @Test
  fun `each surviving finding launches alone without sibling finding text`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val envelopes = mutableListOf<Map<String, Any?>>()
    val outcome = runner(
      launcher = { request ->
        launches += request
        facts(request, IN_SCOPE)
      },
      validator = { envelope, _ -> envelopes += envelope },
    ).run(
      packet = packet(),
      findings = listOf(
        finding("F-001"),
        finding("F-002", "src/B.kt:4", "second"),
        finding("F-003", "src/C.kt:8", "third"),
      ),
      existingVerdicts = listOf(
        stage1("F-001", ReviewClaimVerdict.CONFIRMED),
        stage1("F-002", ReviewClaimVerdict.UNRESOLVED),
        stage1("F-003", ReviewClaimVerdict.CONFIRMED),
      ),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-isolate"),
      timeout = 1.seconds,
    )
    assertEquals(3, launches.size)
    assertEquals(3, envelopes.size)
    val refs = envelopes.map { (it["finding"] as Map<*, *>)["finding_ref"] as String }
    assertEquals(listOf("F-001", "F-002", "F-003"), refs)
    envelopes.forEach { envelope ->
      assertEquals("adjudication_launch", envelope["kind"])
    }
    launches.forEachIndexed { index, launch ->
      val prompt = launch.skillRunRequest.promptOverride.orEmpty()
      val own = refs[index]
      refs.filterNot { it == own }.forEach { sibling ->
        assertFalse(sibling in prompt)
      }
    }
    assertEquals(listOf("F-001", "F-002", "F-003"), outcome.verdicts.map { it.findingRef })
  }

  @Test
  fun `an over-budget adjudication launch is a typed rejection and does not launch`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val error = assertFailsWith<InvalidReviewContextSchemaError> {
      runner(launcher = { request ->
        launches += request
        facts(request, IN_SCOPE)
      }).run(
        packet = packet(),
        findings = listOf(finding("F-001")),
        existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
        projection = projection(),
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneLaunchBytes = 64),
        brokerId = "codex",
        repoRoot = Files.createTempDirectory("adj-budget"),
        timeout = 1.seconds,
      )
    }
    assertTrue(launches.isEmpty())
    assertTrue("for definition 'adjudication_launch'" in error.message)
  }

  @Test
  fun `a result carrying two dispositions or none is recorded in_scope with the rejection reason`() {
    val none = runner(launcher = { request -> facts(request, "{}") }).run(
      packet = packet(),
      findings = listOf(finding("F-001")),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-none-disp"),
      timeout = 1.seconds,
    ).verdicts.single()
    val two = runner(launcher = { request -> facts(request, TWO_DISPOSITIONS) }).run(
      packet = packet(),
      findings = listOf(finding("F-001")),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-two-disp"),
      timeout = 1.seconds,
    ).verdicts.single()
    assertEquals(ReviewScopeDisposition.IN_SCOPE, none.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.AMBIGUOUS, none.rejectionReason)
    assertEquals(ReviewScopeDisposition.IN_SCOPE, two.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.AMBIGUOUS, two.rejectionReason)
  }

  @Test
  fun `spec_deviation citing an element absent from the projection is not admitted`() {
    val verdict = runner(launcher = { request -> facts(request, INVENTED_DEVIATION) }).run(
      packet = packet(),
      findings = listOf(finding("F-001")),
      existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
      projection = projection(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
      brokerId = "codex",
      repoRoot = Files.createTempDirectory("adj-invented"),
      timeout = 1.seconds,
    ).verdicts.single()
    assertEquals(ReviewScopeDisposition.IN_SCOPE, verdict.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.SPEC_DEVIATION_NOT_CONSTRAINT, verdict.rejectionReason)
  }

  private fun runner(
    launcher: GoalRunnerSubtaskLauncher,
    validator: ReviewContextEnvelopeValidator = ReviewContextEnvelopeValidator { _, _ -> },
  ) = ReviewSpecAdjudicationRunner(launcher, validator)

  private fun facts(request: GoalRunnerSubtaskLaunchRequest, stdout: String) = AgentRunLaunchFacts(
    agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
    exitStatus = 0,
    stdout = stdout,
    stderr = "",
    timedOut = false,
    spawnFailed = false,
  )

  private fun finding(
    ref: String,
    location: String = "src/A.kt:12",
    description: String = "Null is not checked.",
  ) = ParallelReviewMergedFinding(
    fNumber = ref,
    agentIds = listOf("codex"),
    severity = ParallelReviewSeverity.MAJOR,
    confidence = "High",
    location = location,
    description = description,
    repositoryPath = location.substringBefore(':'),
    line = location.substringAfter(':').toInt(),
  )

  private fun stage1(ref: String, verdict: ReviewClaimVerdict) = ReviewFindingVerdict(
    stage = ReviewStage.VERIFICATION,
    findingRef = ref,
    claimVerdict = verdict,
    recordedAt = "2026-08-14T08:00:00Z",
  )

  private fun projection() = SpecIntentProjection(
    intendedOutcome = "Ship the adjudication stage.",
    acceptanceCriteria = listOf("Stage two runs."),
    constraints = listOf(CONSTRAINT),
    nonGoals = listOf("Re-testing claims."),
    deferredItems = listOf("Register assembly."),
    provenance = SpecIntentProvenance("spec.md", "a".repeat(64)),
    declaredByteBudget = 4096,
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
    const val CONSTRAINT: String = "Do not launch workers on refuted findings."
    const val IN_SCOPE: String = """{"scope_disposition":"in_scope"}"""
    const val UNCITED_DOWNGRADE: String =
      """{"scope_disposition":"in_scope","severity_adjustment":{"direction":"lower","justification":"too noisy"}}"""
    const val RAISE: String =
      """{"scope_disposition":"spec_deviation","cited_spec_element":"$CONSTRAINT","citations":[{"path":"spec.md","line":8}],"severity_adjustment":{"direction":"raise","adjusted_severity":"Blocker","justification":"contradicts a stated constraint"}}"""
    const val LOWER: String =
      """{"scope_disposition":"spec_deviation","cited_spec_element":"$CONSTRAINT","citations":[{"path":"spec.md","line":8}],"severity_adjustment":{"direction":"lower","adjusted_severity":"Nit","justification":"listed as a non-blocking constraint"}}"""
    const val TWO_DISPOSITIONS: String = """{"scope_disposition":["in_scope","spec_deviation"]}"""
    const val INVENTED_DEVIATION: String =
      """{"scope_disposition":"spec_deviation","cited_spec_element":"Invented constraint","citations":[{"path":"spec.md","line":1}]}"""
  }
}
