package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDispositionRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGeneration
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGenerationIdentity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewGenerationRuntimeTest {
  @Test
  fun `producer attribution blocker survives unrelated generations until explicit evidence`() {
    val dbPath = Files.createTempDirectory("lossless-review-generations").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnitOfWork(connection, dbPath).reviewGenerations
      val finding = blocker()
      val first = generation(1)
      repository.appendGeneration(first)
      repository.appendPass("workflow-1", "generation-1", 1, "checkpoint-1")
      repository.appendFinding("workflow-1", "generation-1", 1, finding)

      repository.appendGeneration(generation(2))
      repository.appendPass("workflow-1", "generation-2", 1, "checkpoint-2")
      assertEquals(
        false,
        repository.unresolvedBlockers("workflow-1").isEmpty(),
        "the first unrelated successor generation must remain advancement-blocking",
      )
      repository.appendGeneration(generation(3))
      repository.appendPass("workflow-1", "generation-3", 1, "checkpoint-3")

      assertEquals(listOf(finding), repository.unresolvedBlockers("workflow-1"))
      assertEquals(1, repository.summary("workflow-1").carriedBlockerCount)
      assertEquals(
        false,
        repository.unresolvedBlockers("workflow-1").isEmpty(),
        "the second unrelated successor generation must remain advancement-blocking",
      )

      repository.appendDisposition(
        GoalSubtaskReviewFindingDispositionRecord(
          workflowId = "workflow-1",
          generationId = "generation-3",
          findingId = finding.findingId,
          disposition = GoalSubtaskReviewFindingDisposition.ACCEPTED,
          evidence = listOf("operator:accept_and_advance at checkpoint-3"),
        ),
      )

      assertEquals(emptyList(), repository.unresolvedBlockers("workflow-1"))
      assertEquals(1, repository.summary("workflow-1").terminalDispositionCounts.getValue("accepted"))
    }
  }

  @Test
  fun `repeated immutable writes are idempotent and conflicts fail loudly`() {
    val dbPath = Files.createTempDirectory("idempotent-review-generation").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnitOfWork(connection, dbPath).reviewGenerations
      val generation = generation(1)

      repository.appendGeneration(generation)
      repository.appendGeneration(generation)

      assertFailsWith<IllegalArgumentException> {
        repository.appendGeneration(
          generation.copy(identity = generation.identity.copy(repositoryCheckpoint = "changed-checkpoint")),
        )
      }
    }
  }

  @Test
  fun `pass local finding ids are namespaced when reused by a later generation`() {
    val dbPath = Files.createTempDirectory("review-finding-id-reuse").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnitOfWork(connection, dbPath).reviewGenerations
      repository.appendGeneration(generation(1))
      repository.appendPass("workflow-1", "generation-1", 1, "checkpoint-1")
      repository.appendFinding("workflow-1", "generation-1", 1, blocker().copy(findingId = "F-001"))
      repository.appendGeneration(generation(2))
      repository.appendPass("workflow-1", "generation-2", 1, "checkpoint-2")
      repository.appendFinding(
        "workflow-1",
        "generation-2",
        1,
        blocker().copy(
          findingId = "F-001",
          summary = "A distinct later blocker reused the pass-local register id.",
          sourceGenerationId = "generation-2",
        ),
      )

      assertEquals(
        setOf("F-001", "generation-2:F-001"),
        repository.unresolvedBlockers("workflow-1").map { it.findingId }.toSet(),
      )
    }
  }

  private fun generation(number: Int) = GoalSubtaskReviewGeneration(
    identity = GoalSubtaskReviewGenerationIdentity(
      workflowId = "workflow-1",
      generationId = "generation-$number",
      reviewBase = "a".repeat(40),
      reviewedDeltaDigest = number.toString().repeat(64),
      repositoryCheckpoint = "checkpoint-$number",
    ),
  )

  private fun blocker() = GoalSubtaskReviewFinding(
    findingId = "skill-134-producer-attribution",
    severity = "blocker",
    category = "platform-correctness",
    location = "ProducerAttribution.kt:42",
    summary = "Producer identity is attributed to the consumer.",
    sourceGenerationId = "generation-1",
  )
}
