package skillbill.cli

import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.cli.core.CliRuntime
import skillbill.cli.work.padTerminalEnd
import skillbill.cli.work.terminalDisplayWidth
import skillbill.cli.work.truncateTerminalDisplayWidth
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.model.RuntimeContext
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliWorkListRuntimeTest {
  @Test
  fun `work list renders empty tables and stable empty json through the global database override`() {
    val dbPath = Files.createTempDirectory("skillbill-cli-work-empty").resolve("metrics.db")

    val table = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "list"))
    val json = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "list", "--format", "json"))

    assertEquals(0, table.exitCode, table.stdout)
    assertContains(table.stdout, "ISSUE")
    assertContains(table.stdout, "KIND")
    assertContains(table.stdout, "WORKFLOW")
    assertContains(table.stdout, "STARTED")
    assertContains(table.stdout, "STATE")
    assertContains(table.stdout, "STATE SINCE")
    assertEquals(0, json.exitCode, json.stdout)
    assertEquals(emptyList<Any?>(), decodeJsonObject(json.stdout)["work"])
  }

  @Test
  fun `work list table and json expose ordering unknown estimated values limits and utc instants`() {
    val dbPath = Files.createTempDirectory("skillbill-cli-work-list").resolve("metrics.db")
    val component = RuntimeComponent::class.create(
      RuntimeContext(environment = emptyMap(), userHome = Files.createTempDirectory("skillbill-cli-work-list-home")),
    )
    val prose = component.openWorkflow(WorkflowFamilyKind.TASK_PROSE, dbPath, "SKILL-117")
    val runtime = component.openWorkflow(WorkflowFamilyKind.TASK_RUNTIME, dbPath, "SKILL-117")
    val verify = component.openWorkflow(WorkflowFamilyKind.VERIFY, dbPath, "SKILL-118")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      updateStartedAt(connection, "feature_task_workflows", prose, "2026-05-01T12:00:00.000001Z")
      updateStartedAt(connection, "feature_task_workflows", runtime, "2026-05-01T12:00:00.000004Z")
      updateStartedAt(connection, "feature_verify_workflows", verify, "2026-05-01T12:00:00.000003Z")
      insertGoal(connection, "goal-a", null, "2026-05-01T12:00:00.000001Z", "2026-05-01T12:00:00.000001Z", true)
      insertGoal(connection, "goal-z", "SKILL-117", "2026-05-01T12:00:00.000002Z", "2026-05-01T12:01:00Z", false)
      insertGoal(connection, "goal-control", "BAD\u001b]8;;https://example.test\u0007", "2026-05-01T12:00:00Z", "2026-05-01T12:00:00Z", false)
    }

    val table = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "list"))
    val json = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "list", "--format", "json"))
    val limited = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "list", "--limit", "1", "--format", "json"))
    val invalidLimit = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "list", "--limit", "0"))
    val payload = decodeJsonObject(json.stdout)
    val work = payload["work"] as List<*>
    val first = work.first() as Map<*, *>
    val second = work[1] as Map<*, *>
    val limitedWork = decodeJsonObject(limited.stdout)["work"] as List<*>

    assertEquals(0, table.exitCode, table.stdout)
    assertContains(table.stdout, "feature-task-prose")
    assertContains(table.stdout, "feature-task-runtime")
    assertContains(table.stdout, "feature-verify")
    assertContains(table.stdout, "feature-goal")
    assertContains(table.stdout, "SKILL-117")
    assertContains(table.stdout, "-")
    assertContains(table.stdout, "~ estimated")
    assertContains(table.stdout, "BAD�]8;;https://example.test�")
    assertFalse(table.stdout.contains('\u001b'))
    assertEquals(0, json.exitCode, json.stdout)
    assertEquals(
      listOf(runtime, verify, "goal-z", prose, "goal-a", "goal-control"),
      work.map { (it as Map<*, *>)["workflow_id"] },
    )
    assertEquals("feature-task-runtime", first["workflow_kind"])
    assertEquals("2026-05-01T12:00:00.000004Z", first["started_at"])
    assertEquals(false, first["state_entered_at_estimated"])
    assertEquals("feature-verify", second["workflow_kind"])
    assertEquals("2026-05-01T12:00:00.000003Z", second["started_at"])
    assertEquals(listOf(runtime), limitedWork.map { (it as Map<*, *>)["workflow_id"] })
    assertFalse(invalidLimit.exitCode == 0)
    assertContains(invalidLimit.stdout, "--limit must be a positive integer.")
  }

  @Test
  fun `work list table aligns wide and combining issue keys by terminal display cells`() {
    val dbPath = Files.createTempDirectory("skillbill-cli-work-unicode").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertGoal(connection, "goal-wide", "界", "2026-05-01T12:00:00Z", "2026-05-01T12:00:00Z", false)
      insertGoal(connection, "goal-combining", "A\u0301", "2026-05-01T12:00:01Z", "2026-05-01T12:00:01Z", false)
    }

    val table = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "list"))
    val wideLine = table.stdout.lineSequence().first { it.startsWith("界") }
    val combiningLine = table.stdout.lineSequence().first { it.startsWith("A\u0301") }

    assertEquals(0, table.exitCode, table.stdout)
    assertEquals(7, terminalDisplayWidth(wideLine.substringBefore("feature-goal")))
    assertEquals(7, terminalDisplayWidth(combiningLine.substringBefore("feature-goal")))
    assertEquals(3, terminalDisplayWidth("A\u0301界"))
    assertEquals("A\u0301…", "A\u0301界".truncateTerminalDisplayWidth(2))
    assertEquals("界  ", "界".padTerminalEnd(4))
    assertEquals("A\u0301   ", "A\u0301".padTerminalEnd(4))
  }

  private fun RuntimeComponent.openWorkflow(kind: WorkflowFamilyKind, dbPath: java.nio.file.Path, issueKey: String): String =
    assertIs<WorkflowOpenResult.Ok>(
      workflowService.open(kind = kind, dbOverride = dbPath.toString(), issueKey = issueKey),
    ).workflowId

  private fun updateStartedAt(connection: Connection, table: String, workflowId: String, startedAt: String) {
    connection.prepareStatement("UPDATE $table SET started_at = ?, state_entered_at = ? WHERE workflow_id = ?").use { statement ->
      statement.setString(1, startedAt)
      statement.setString(2, startedAt)
      statement.setString(3, workflowId)
      statement.executeUpdate()
    }
  }

  private fun insertGoal(
    connection: Connection,
    workflowId: String,
    issueKey: String?,
    startedAt: String,
    stateEnteredAt: String,
    estimated: Boolean,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO goal_issue_progress (
        parent_workflow_id, issue_key, first_started_at, status, state_entered_at, state_entered_at_estimated
      ) VALUES (?, ?, ?, 'running', ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, issueKey.orEmpty())
      statement.setString(3, startedAt)
      statement.setString(4, stateEnteredAt)
      statement.setInt(5, if (estimated) 1 else 0)
      statement.executeUpdate()
    }
  }
}

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
}
