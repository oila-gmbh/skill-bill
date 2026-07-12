package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertGoal(connection, "goal-a", null, "2026-05-01T12:00:00Z", "2026-05-01T12:00:00Z", true)
      insertGoal(connection, "goal-z", "SKILL-117", "2026-05-01T12:00:00Z", "2026-05-01T12:01:00Z", false)
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
    assertContains(table.stdout, "SKILL-117")
    assertContains(table.stdout, "-")
    assertContains(table.stdout, "~ estimated")
    assertEquals(0, json.exitCode, json.stdout)
    assertEquals("goal-z", first["workflow_id"])
    assertEquals("goal-a", second["workflow_id"])
    assertEquals("feature-goal", first["workflow_kind"])
    assertEquals("2026-05-01T12:00:00Z", first["started_at"])
    assertEquals(false, first["state_entered_at_estimated"])
    assertEquals(null, second["issue_key"])
    assertEquals(true, second["state_entered_at_estimated"])
    assertEquals(listOf("goal-z"), limitedWork.map { (it as Map<*, *>)["workflow_id"] })
    assertFalse(invalidLimit.exitCode == 0)
    assertContains(invalidLimit.stdout, "--limit must be a positive integer.")
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
