package skillbill.mcp

import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.mcp.core.McpRuntimeContext
import skillbill.mcp.core.McpToolDispatcher
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PrDescriptionTelemetryEditDetectionTest {
  @Test
  fun `pr description telemetry detects edited final body through mcp tool`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-pr-description-edited")
    val context = McpRuntimeContext(environment = enabledTelemetryEnvironment(tempDir), userHome = tempDir)

    McpToolDispatcher.call(
      "pr_description_generated",
      mapOf(
        "commit_count" to 2,
        "files_changed_count" to 4,
        "was_edited_by_user" to false,
        "pr_created" to true,
        "pr_title" to "SKILL-109 reliable telemetry",
        "orchestrated" to false,
        "generated_description" to "## Summary\n\n- generated body\n",
        "final_pr_body" to "## Summary\n\n- edited body\n",
      ),
      context,
    )

    DatabaseRuntime.ensureDatabase(tempDir.resolve("metrics.db")).use { connection ->
      val payload = decodeJsonObject(
        scalarString(
          connection,
          "SELECT payload_json FROM telemetry_outbox WHERE event_name = 'skillbill_pr_description_generated'",
        ),
      )
      assertEquals(true, payload["was_edited_by_user"])
      assertEquals(true, payload["pr_created"])
    }
  }

  @Test
  fun `orchestrated pr description telemetry detects edited final body in returned child payload`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-pr-description-orchestrated-edited")
    val context = McpRuntimeContext(environment = enabledTelemetryEnvironment(tempDir), userHome = tempDir)

    val result = McpToolDispatcher.call(
      "pr_description_generated",
      mapOf(
        "commit_count" to 2,
        "files_changed_count" to 4,
        "was_edited_by_user" to false,
        "pr_created" to true,
        "pr_title" to "SKILL-109 reliable telemetry",
        "orchestrated" to true,
        "generated_description" to "## Summary\r\n\r\n- generated body\r\n",
        "final_pr_body" to "## Summary\n\n- generated body with reviewer edit\n",
      ),
      context,
    )

    val payload = result["telemetry_payload"] as Map<*, *>
    assertEquals("orchestrated", result["mode"])
    assertEquals(true, payload["was_edited_by_user"])
    assertFalse(Files.exists(tempDir.resolve("metrics.db")))
  }

  private fun enabledTelemetryEnvironment(tempDir: Path): Map<String, String> {
    val configPath = tempDir.resolve("config.json")
    Files.writeString(
      configPath,
      """
      {
        "install_id": "test-install-id",
        "telemetry": {
          "level": "anonymous",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    return mapOf(
      "SKILL_BILL_REVIEW_DB" to tempDir.resolve("metrics.db").toString(),
      CONFIG_ENVIRONMENT_KEY to configPath.toString(),
      TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "http://127.0.0.1:9/skill-bill-test-telemetry",
    )
  }

  private fun scalarString(connection: Connection, sql: String): String =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { rows ->
        check(rows.next()) { "Expected scalar row for query: $sql" }
        rows.getString(1)
      }
    }

  private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
    val parsed = requireNotNull(JsonSupport.parseObjectOrNull(rawJson)) { "Expected JSON object: $rawJson" }
    return parsed.entries.associate { (key, value) -> key to JsonSupport.jsonElementToValue(value) }
  }
}
