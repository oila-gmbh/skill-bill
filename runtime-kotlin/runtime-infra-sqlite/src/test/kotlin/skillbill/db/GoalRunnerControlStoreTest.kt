package skillbill.db

import skillbill.db.workflow.GoalRunnerControlStore
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerControlStoreTest {
  @Test
  fun `review policy and operator acceptance remain durable outside workflow projection`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-controls").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      val policy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE, parallelReviewAgent = "reviewer")
      val acceptance = GoalRunnerOutOfBandAcceptance(
        subtaskId = 2,
        commitSha = "abc123",
        reason = "work was completed on the feature branch",
        acceptedAt = "2026-08-01T10:00:00Z",
      )

      store.persistReviewPolicy("parent-1", policy)
      store.persistOutOfBandAcceptance("parent-1", acceptance)

      assertEquals(policy, store.reviewPolicy("parent-1"))
      assertEquals(mapOf(2 to acceptance), store.outOfBandAcceptances("parent-1"))
      connection.prepareStatement(
        "SELECT review_policy_json, out_of_band_acceptances_json FROM goal_runner_controls WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, "parent-1")
        statement.executeQuery().use { rows ->
          check(rows.next())
          check(rows.getString("review_policy_json").contains("code_review_mode"))
          check(rows.getString("out_of_band_acceptances_json").contains("commit_sha"))
        }
      }
    }
  }
}
