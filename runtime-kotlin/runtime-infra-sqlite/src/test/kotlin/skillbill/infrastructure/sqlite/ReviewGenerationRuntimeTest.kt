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
      repository.appendGeneration(generation(3))

      assertEquals(listOf(finding), repository.unresolvedBlockers("workflow-1"))
      assertEquals(1, repository.summary("workflow-1").carriedBlockerCount)

      repository.appendDisposition(
        GoalSubtaskReviewFindingDispositionRecord(
          workflowId = "workflow-1",
          generationId = "generation-3",
          findingId = finding.findingId,
          disposition = GoalSubtaskReviewFindingDisposition.RESOLVED,
          evidence = listOf("checkpoint-3:ProducerAttribution.kt:42"),
        ),
      )

      assertEquals(emptyList(), repository.unresolvedBlockers("workflow-1"))
      assertEquals(1, repository.summary("workflow-1").terminalDispositionCounts.getValue("resolved"))
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
