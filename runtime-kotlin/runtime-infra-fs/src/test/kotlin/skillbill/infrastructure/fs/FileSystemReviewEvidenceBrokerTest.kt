package skillbill.infrastructure.fs

import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.REVIEW_BUDGET_REGRESSION
import skillbill.review.context.model.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.ReviewRevision
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileSystemReviewEvidenceBrokerTest {
  @Test fun `batched assigned reads are measured in one pass`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "second")
    val broker = broker(root, assignment(listOf("A.kt", "B.kt")))
    val result = broker.readBatch(
      ReviewEvidenceBatchRequest(
        "security",
        listOf(ReviewEvidenceRequest("security", "A.kt"), ReviewEvidenceRequest("security", "B.kt")),
      ),
    )
    assertEquals(listOf("assigned", "second"), result.results.map { it.content })
    assertNull(result.terminalOutcome)
    assertEquals(14, result.cumulativeBytes)
    assertEquals(14, broker.accounting().evidenceBytes)
  }

  @Test fun `per-result excess terminates the lane and the rest of the batch`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "second")
    val broker = broker(root, assignment(listOf("A.kt", "B.kt")), policy(result = 4, cumulative = 8))
    val result = broker.readBatch(
      ReviewEvidenceBatchRequest(
        "security",
        listOf(ReviewEvidenceRequest("security", "A.kt"), ReviewEvidenceRequest("security", "B.kt")),
      ),
    )
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, result.terminalOutcome?.type)
    assertEquals("evidence_result_bytes", result.terminalOutcome?.budgetKind)
    assertTrue(result.results.all { it.content == null })
    val repeated = broker.readBatch(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "A.kt")))
    assertSame(result.terminalOutcome, repeated.terminalOutcome)
  }

  @Test fun `cumulative evidence excess terminates the lane`() {
    val root = repo("A.kt" to "12345", "B.kt" to "67890")
    val broker = broker(root, assignment(listOf("A.kt", "B.kt")), policy(result = 8, cumulative = 8))
    val result = broker.readBatch(
      ReviewEvidenceBatchRequest(
        "security",
        listOf(ReviewEvidenceRequest("security", "A.kt"), ReviewEvidenceRequest("security", "B.kt")),
      ),
    )
    assertEquals("lane_evidence_bytes", result.terminalOutcome?.budgetKind)
    assertEquals(10, result.terminalOutcome?.observedValue)
  }

  @Test fun `authorized expansion is admitted and audited with its reachability reason`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val broker = broker(root, assignment(listOf("A.kt")))
    val result = broker.readBatch(
      ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "B.kt", "called by assigned symbol")),
    )
    assertEquals("dep", result.results.single().content)
    val expansion = result.expansions.single()
    assertEquals("B.kt", expansion.requestedPath)
    assertEquals("called by assigned symbol", expansion.reachabilityReason)
    assertTrue(expansion.authorized)
    assertEquals(0, expansion.sequence)
    assertEquals(result.expansions, broker.accounting().expansions)
  }

  @Test fun `unassigned read without a reason is a forbidden operation, not an expansion`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val broker = broker(root, assignment(listOf("A.kt")))
    val result = broker.readBatch(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "B.kt")))
    assertEquals("unassigned_file_access", result.results.single().forbidden?.category)
    assertNull(result.results.single().content)
    assertTrue(result.expansions.isEmpty())
  }

  @Test fun `expansion budget excess terminates the lane`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val broker = broker(root, assignment(listOf("A.kt")), policy(expansions = 0))
    val result = broker.readBatch(
      ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "B.kt", "reachable from assigned symbol")),
    )
    assertEquals("assignment_expansions", result.terminalOutcome?.budgetKind)
  }

  @Test fun `named direct dependency does not consume an expansion`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val broker = broker(root, assignment(listOf("A.kt")), namedDependencies = setOf("B.kt"))
    val result = broker.readBatch(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "B.kt")))
    assertEquals("dep", result.results.single().content)
    assertTrue(result.expansions.isEmpty())
  }

  @Test fun `forbidden tool calls are rejected and never counted`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    val rejected = broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.SHELL_COMMAND, "git status"))
    assertEquals("review_status", rejected.forbidden?.category)
    assertEquals(0, broker.accounting().toolCalls)
  }

  @Test fun `tool-call budget excess terminates the lane`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")), policy(toolCalls = 1))
    assertTrue(broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.RUBRIC_READ, "security")).admitted)
    val second = broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.RUBRIC_READ, "security"))
    assertEquals("specialist_tool_calls", second.budgetExceeded?.budgetKind)
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, second.budgetExceeded?.type)
  }

  @Test fun `model-turn budget excess terminates the lane`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")), policy(modelTurns = 1))
    assertNull(broker.recordModelTurn())
    assertEquals("specialist_model_turns", broker.recordModelTurn()?.budgetKind)
    assertEquals("specialist_model_turns", broker.accounting().terminalOutcome?.budgetKind)
  }

  @Test fun `lane result excess terminates subsequent evidence`() {
    val root = repo("A.kt" to "ok")
    val broker = broker(root, assignment(listOf("A.kt")))
    assertEquals("lane_result_bytes", broker.validateLaneResult("x".repeat(101))?.budgetKind)
    val followUp = broker.readBatch(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "A.kt")))
    assertEquals("lane_result_bytes", followUp.terminalOutcome?.budgetKind)
  }

  @Test fun `non-enforceable provider excess reports a regression without terminating`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    val outcome = broker.evaluateProviderUsage(ProviderTokenUsage(totalTokens = 500_000), enforceable = false)
    assertEquals(REVIEW_BUDGET_REGRESSION, outcome?.type)
    assertEquals(false, outcome?.enforceable)
    assertNull(broker.accounting().terminalOutcome)
    assertEquals("assigned", broker.readBatch(batch("A.kt")).results.single().content)
  }

  @Test fun `enforceable provider excess terminates the lane`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    val outcome = broker.evaluateProviderUsage(ProviderTokenUsage(totalTokens = 500_000), enforceable = true)
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, outcome?.type)
    assertNotNull(broker.accounting().terminalOutcome)
  }

  @Test fun `paths escaping the repository are rejected`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    assertFailsWith<IllegalArgumentException> { broker.readBatch(batch("../outside.kt")) }
  }

  @Test fun `an ordinary bounded review completes with full accounting and no termination`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    broker.recordModelTurn()
    broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.RUBRIC_READ, "security"))
    broker.readBatch(batch("A.kt"))
    assertNull(broker.validateLaneResult("- [F-001] Minor | Low | A.kt:1 | bounded finding"))
    val accounting = broker.accounting()
    assertEquals(8, accounting.evidenceBytes)
    assertEquals(1, accounting.toolCalls)
    assertEquals(1, accounting.modelTurns)
    assertEquals(48, accounting.resultBytes)
    assertNull(accounting.terminalOutcome)
  }

  private fun batch(path: String) = ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", path))

  private fun repo(vararg files: Pair<String, String>): Path {
    val root = Files.createTempDirectory("review-evidence")
    files.forEach { (name, content) -> Files.writeString(root.resolve(name), content) }
    return root
  }

  private fun broker(
    root: Path,
    assignment: ReviewAssignment,
    budget: ReviewContextBudgetPolicy = policy(),
    namedDependencies: Set<String> = emptySet(),
  ) = FileSystemReviewEvidenceBroker(
    ReviewEvidenceBrokerBinding(root, assignment, "security", budget, namedDependencies),
  )

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

  private fun policy(
    result: Long = 100,
    cumulative: Long = 200,
    expansions: Int = 1,
    toolCalls: Int = 40,
    modelTurns: Int = 24,
  ) = ReviewContextBudgetPolicy(
    maxParentPacketBytes = 1_000,
    maxLaneLaunchBytes = 500,
    maxLaneEvidenceBytes = cumulative,
    maxEvidenceResultBytes = result,
    maxLaneResultBytes = 100,
    maxAssignmentExpansions = expansions,
    maxSpecialistToolCalls = toolCalls,
    maxSpecialistModelTurns = modelTurns,
  )
}
