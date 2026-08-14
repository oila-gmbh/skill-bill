package skillbill.application.review

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.TestDecompositionManifestFileStore
import skillbill.domain.review.context.model.SpecIntentAbsenceReason
import skillbill.domain.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.domain.review.context.model.SpecIntentResolution
import skillbill.error.UnreadableSpecIntentProjectionError
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
import skillbill.review.context.model.REVIEW_SPEC_INTENT_PROJECTION_BUDGET
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitLaneDecision
import skillbill.review.context.model.ReviewCommitLaneDisposition
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewRevision
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpecIntentProjectionResolverTest {
  @Test
  fun `extraction captures five sections provenance digest and empty optional lists`() {
    val repo = tempRepo()
    val spec = writeSpec(
      repo,
      "spec.md",
      """
      # Feature

      ## Intended Outcome
      Ship the resolver.

      ## Acceptance Criteria
      1. First criterion.
      2. Second criterion.

      ## Constraints
      - Stay contract-first.

      ## Deferred items
      - Stage two.
      """.trimIndent(),
    )
    val projection = extractor().extract(repo, spec, ReviewContextBudgetPolicy.DEFAULT, explicit = true)
    assertEquals("Ship the resolver.", projection.intendedOutcome)
    assertEquals(listOf("First criterion.", "Second criterion."), projection.acceptanceCriteria)
    assertEquals(listOf("Stay contract-first."), projection.constraints)
    assertEquals(emptyList(), projection.nonGoals)
    assertEquals(listOf("Stage two."), projection.deferredItems)
    assertEquals("spec.md", projection.provenance.specPath)
    assertEquals(sha256File(spec), projection.provenance.contentDigest)
  }

  @Test
  fun `an explicit path wins over a manifest and a glob match`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    val explicit = repo.resolve(".feature-specs/SKILL-191-other/spec.md")
    Files.createDirectories(explicit.parent)
    Files.writeString(explicit, governedSpec("Explicit outcome", "Explicit criterion."))
    val resolved = resolver().resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = repo,
        explicitSpecPath = explicit,
        branchName = "feat/SKILL-191-runtime",
      ),
    )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals("Explicit outcome", projection.intendedOutcome)
    assertEquals("Explicit criterion.", projection.acceptanceCriteria.single())
  }

  @Test
  fun `a readable manifest wins over a glob match`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    val resolved = resolver().resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = repo,
        branchName = "feat/SKILL-191-runtime",
      ),
    )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals("Subtask outcome", projection.intendedOutcome)
    assertEquals(
      ".feature-specs/SKILL-191-runtime/spec_subtask_2.md",
      projection.provenance.specPath,
    )
  }

  @Test
  fun `a subtask-owned delta uses the subtask spec as primary and the parent as surrounding context`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    val resolved = resolver().resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = repo,
        branchName = "feat/SKILL-191-runtime",
        changedPaths = listOf("src/Main.kt"),
      ),
    )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals(".feature-specs/SKILL-191-runtime/spec_subtask_2.md", projection.provenance.specPath)
    assertEquals(".feature-specs/SKILL-191-runtime/spec.md", projection.surroundingContext?.specPath)
    assertEquals(sha256File(repo.resolve(".feature-specs/SKILL-191-runtime/spec.md")), projection.surroundingContext?.contentDigest)
  }

  @Test
  fun `two glob matches resolve to none with ambiguous_match`() {
    val repo = tempRepo()
    writeSpec(repo, ".feature-specs/SKILL-191-one/spec.md", governedSpec("One", "AC one."))
    writeSpec(repo, ".feature-specs/SKILL-191-two/spec.md", governedSpec("Two", "AC two."))
    val resolved = resolver().resolve(
      SpecIntentProjectionResolveRequest(repoRoot = repo, branchName = "feat/SKILL-191-runtime"),
    )
    val none = assertIs<SpecIntentResolution.None>(resolved)
    assertEquals(SpecIntentAbsenceReason.AMBIGUOUS_MATCH, none.reason)
  }

  @Test
  fun `no glob match resolves to none with no_spec_found`() {
    val repo = tempRepo()
    Files.createDirectories(repo.resolve(".feature-specs"))
    val resolved = resolver().resolve(
      SpecIntentProjectionResolveRequest(repoRoot = repo, branchName = "feat/SKILL-191-runtime"),
    )
    assertEquals(SpecIntentAbsenceReason.NO_SPEC_FOUND, assertIs<SpecIntentResolution.None>(resolved).reason)
  }

  @Test
  fun `a branch without an issue key resolves to none with not_applicable_scope`() {
    val repo = tempRepo()
    val resolved = resolver().resolve(
      SpecIntentProjectionResolveRequest(repoRoot = repo, branchName = "main"),
    )
    assertEquals(SpecIntentAbsenceReason.NOT_APPLICABLE_SCOPE, assertIs<SpecIntentResolution.None>(resolved).reason)
  }

  @Test
  fun `an explicit missing spec path loud-fails rather than degrading`() {
    val repo = tempRepo()
    val missing = repo.resolve("missing-spec.md")
    val error = assertFailsWith<UnreadableSpecIntentProjectionError> {
      resolver().resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = repo,
          explicitSpecPath = missing,
          branchName = "feat/SKILL-191-runtime",
        ),
      )
    }
    assertTrue("spec_intent_projection" in error.message.orEmpty())
    assertTrue(missing.toString() in error.specPath)
  }

  @Test
  fun `an over-budget projection is rejected by name and is not truncated`() {
    val repo = tempRepo()
    val spec = writeSpec(repo, "spec.md", governedSpec("Outcome", "A criterion."))
    val error = assertFailsWith<ReviewContextBudgetExceededException> {
      extractor().extract(
        repo,
        spec,
        ReviewContextBudgetPolicy.DEFAULT.copy(maxSpecIntentProjectionBytes = 32),
        explicit = true,
      )
    }
    assertEquals(REVIEW_SPEC_INTENT_PROJECTION_BUDGET, error.outcome.budgetKind)
    assertTrue(error.outcome.observedValue > error.outcome.configuredLimit)
  }

  @Test
  fun `each closed-vocabulary none reason emits an observability record`() {
    val records = mutableListOf<LogRecord>()
    val handler = capturingHandler(records)
    specIntentResolverLog.addHandler(handler)
    try {
      val repo = tempRepo()
      resolver().resolve(SpecIntentProjectionResolveRequest(repoRoot = repo, branchName = "main"))
      assertTrue(records.any { "reason=not_applicable_scope" in it.message })
      records.clear()
      Files.createDirectories(repo.resolve(".feature-specs"))
      resolver().resolve(SpecIntentProjectionResolveRequest(repoRoot = repo, branchName = "feat/SKILL-191-runtime"))
      assertTrue(records.any { "reason=no_spec_found" in it.message })
      records.clear()
      writeSpec(repo, ".feature-specs/SKILL-191-one/spec.md", governedSpec("One", "AC one."))
      writeSpec(repo, ".feature-specs/SKILL-191-two/spec.md", governedSpec("Two", "AC two."))
      resolver().resolve(SpecIntentProjectionResolveRequest(repoRoot = repo, branchName = "feat/SKILL-191-runtime"))
      assertTrue(records.any { "reason=ambiguous_match" in it.message })
    } finally {
      specIntentResolverLog.removeHandler(handler)
    }
  }

  @Test
  fun `an unreadable manifest emits a record and falls through to glob search`() {
    val records = mutableListOf<LogRecord>()
    val handler = capturingHandler(records)
    specIntentResolverLog.addHandler(handler)
    try {
      val repo = featureRepo(includeGlob = true, includeManifest = true)
      Files.writeString(repo.resolve(".feature-specs/SKILL-191-runtime/decomposition-manifest.yaml"), "not: [valid")
      val resolved = resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo, branchName = "feat/SKILL-191-runtime"),
      )
      val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
      assertEquals(".feature-specs/SKILL-191-runtime/spec.md", projection.provenance.specPath)
      assertTrue(records.any { "reason=manifest_unreadable" in it.message && "rung=glob" in it.message })
    } finally {
      specIntentResolverLog.removeHandler(handler)
    }
  }

  @Test
  fun `resolved projection criteria populate assignment and launch envelopes`() {
    val repo = tempRepo()
    val spec = writeSpec(repo, "spec.md", governedSpec("Outcome", "First criterion.", "Second criterion."))
    val projection = extractor().extract(repo, spec, ReviewContextBudgetPolicy.DEFAULT, explicit = true)
    val prepared = prepareWithCriteria(projection.acceptanceCriteria)
    val assignment = prepared.assignments.single()
    assertEquals(projection.acceptanceCriteria, assignment.criteriaReferences)
    val launch = skillbill.review.context.model.GovernedReviewLaunch(
      assignment,
      prepared.packet,
      "contract",
      "rubric",
      "broker",
      ReviewContextBudgetPolicy.DEFAULT,
    )
    @Suppress("UNCHECKED_CAST")
    val launchCriteria = launch.toLaunchEnvelope().asWireMap()["criteria_references"] as List<String>
    assertEquals(projection.acceptanceCriteria, launchCriteria)
    assertTrue(launchCriteria.none { it == "independent branch-diff specialist review" })
  }

  @Test
  fun `no resolved spec leaves criteria_references empty`() {
    val prepared = prepareWithCriteria(emptyList())
    assertEquals(emptyList(), prepared.assignments.single().criteriaReferences)
  }
}

private fun extractor() = SpecIntentProjectionExtractor(
  object : ReviewContextEnvelopeValidator {
    override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
  },
)

private fun resolver() = SpecIntentProjectionResolver(
  TestDecompositionManifestFileStore,
  testDecompositionManifestValidator,
  extractor(),
)

private fun prepareWithCriteria(criteria: List<String>): skillbill.application.review.model.ReviewPreparationResult {
  val hunk = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  val unit = ReviewCommitUnit("head", "base", "one commit", 0, listOf(hunk), ReviewCommitSource.COMMIT_RANGE)
  val scope = ReviewScopeFacts(
    "acme/repo",
    "base",
    "head",
    "clean",
    listOf(hunk),
    listOf(unit),
    ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
  )
  val decision = ReviewLaneDecision(
    lane = "security",
    included = true,
    reason = "auth surface changed",
    ownedPaths = listOf("src/A.kt"),
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-security",
  )
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
    override fun resolveMatchedRules(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = emptyList<skillbill.review.context.model.ReviewRuleReference>()
    override fun resolveLearnings(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = emptyList<skillbill.review.context.model.ReviewLearningsReference>()
    override fun resolveBuildTestFacts(scope: ReviewScopeFacts) = emptyList<skillbill.review.context.model.ReviewBuildTestFact>()
    override fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = ReviewLaneSelection(
      listOf(decision),
      ReviewCommitLaneRoutingMatrix(
        listOf("head"),
        listOf("security"),
        listOf(ReviewCommitLaneDecision("head", 0, "security", ReviewCommitLaneDisposition.FOCUSED, "focused")),
      ),
    )
  }
  return ReviewPreparationService(
    ReviewFactPorts(ports, ports, ports, ports, ports, ports),
    object : ReviewContextEnvelopeValidator {
      override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
    },
  ).prepare(
    ReviewPreparationRequest(
      reviewId = "review",
      reviewRevision = ReviewRevision("rvs", 1),
      criteriaReferences = mapOf("security" to criteria),
    ),
  )
}

private fun tempRepo(): Path = Files.createTempDirectory("spec-intent-repo")

private fun writeSpec(repo: Path, relative: String, text: String): Path {
  val path = repo.resolve(relative)
  Files.createDirectories(path.parent)
  Files.writeString(path, text)
  return path
}

private fun governedSpec(outcome: String, vararg criteria: String): String = buildString {
  appendLine("# Feature")
  appendLine()
  appendLine("## Intended Outcome")
  appendLine(outcome)
  appendLine()
  appendLine("## Acceptance Criteria")
  criteria.forEachIndexed { index, criterion -> appendLine("${index + 1}. $criterion") }
}

private fun featureRepo(includeGlob: Boolean, includeManifest: Boolean): Path {
  val repo = tempRepo()
  val dir = repo.resolve(".feature-specs/SKILL-191-runtime")
  Files.createDirectories(dir)
  Files.writeString(dir.resolve("spec.md"), governedSpec("Parent outcome", "Parent criterion."))
  Files.writeString(dir.resolve("spec_subtask_2.md"), governedSpec("Subtask outcome", "Subtask criterion."))
  if (includeManifest) {
    Files.writeString(
      dir.resolve("decomposition-manifest.yaml"),
      """
      contract_version: "0.5"
      issue_key: "SKILL-191"
      feature_name: "runtime"
      parent_spec_path: ".feature-specs/SKILL-191-runtime/spec.md"
      spec_source: local
      status: in_progress
      execution_model: same_branch_commit_per_subtask
      base_branch: main
      feature_branch: feat/SKILL-191-runtime
      stack_branches: []
      current_subtask_intent:
        subtask_id: 2
        action: resume
      subtasks:
      - id: 1
        name: first
        spec_path: ".feature-specs/SKILL-191-runtime/spec_subtask_1.md"
        status: complete
        dependencies: []
      - id: 2
        name: second
        spec_path: ".feature-specs/SKILL-191-runtime/spec_subtask_2.md"
        status: pending
        dependencies: []
      """.trimIndent(),
    )
  }
  if (!includeGlob) {
    Files.deleteIfExists(dir.resolve("spec.md"))
  }
  return repo
}

private fun sha256File(path: Path): String {
  val digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
  return digest.joinToString("") { "%02x".format(it) }
}

private fun capturingHandler(records: MutableList<LogRecord>) = object : Handler() {
  override fun publish(record: LogRecord) {
    records += record
  }
  override fun flush() = Unit
  override fun close() = Unit
}
