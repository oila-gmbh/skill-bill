package skillbill.cli

import skillbill.SAMPLE_REVIEW
import skillbill.SkillBillVersion
import skillbill.cli.core.CliRuntime
import skillbill.cli.core.ExternalCommand
import skillbill.cli.core.ExternalCommandResult
import skillbill.cli.core.ExternalCommandRunner
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class CliRuntimeGoalStatsTest {
  @Test
  fun `goal-stats --format json emits stable payload`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-json")
    val dbPath = tempDir.resolve("metrics.db")
    seedGoalStatsDb(dbPath)
    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
      CliRuntimeContext(),
    )
    val payload = decodeJsonObject(result.stdout)
    assertEquals(0, result.exitCode)
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(1, payload["total_runs"])
    assertEquals(1.0, payload["blocked_rate"])
    assertEquals(dbPath.toAbsolutePath().normalize().toString(), payload["db_path"])
    assertEquals(1, (payload["top_blocked_subtasks"] as List<*>).size)
  }

  @Test
  fun `goal-stats human-readable output includes key fields`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-human")
    val dbPath = tempDir.resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = LifecycleTelemetryStore(connection)
      store.goalStarted(
        GoalStartedRecord(
          issueKey = "SKILL-66",
          featureName = "goal telemetry",
          workflowId = "wf-cli-human",
          subtaskTotal = 1,
          resumed = false,
          startedAt = "2026-06-05T10:00:00Z",
          mode = "runtime",
        ),
        level = "full",
      )
      store.goalFinished(
        GoalFinishedRecord(
          issueKey = "SKILL-66",
          workflowId = "wf-cli-human",
          status = "completed",
          startedAt = "2026-06-05T10:00:00Z",
          finishedAt = "2026-06-05T10:30:00Z",
          durationMs = 1_800_000,
          subtasksComplete = 1,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          mode = "runtime",
        ),
        level = "full",
      )
    }

    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "goal-stats"),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode)
    assertContains(result.stdout, "total_runs")
    assertContains(result.stdout, "blocked_rate")
  }

  @Test
  fun `goal-stats empty store exits 0 with zero counts`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-empty")
    val dbPath = tempDir.resolve("metrics.db")

    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
      CliRuntimeContext(),
    )
    val payload = decodeJsonObject(result.stdout)

    assertEquals(0, result.exitCode)
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(0, payload["total_runs"])
  }
}

}
