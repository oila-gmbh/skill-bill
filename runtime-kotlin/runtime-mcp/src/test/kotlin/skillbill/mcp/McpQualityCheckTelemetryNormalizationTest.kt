package skillbill.mcp

import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.mcp.core.McpRuntimeContext
import skillbill.mcp.core.McpToolDispatcher
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class McpQualityCheckTelemetryNormalizationTest {
  @Test
  fun `quality check lifecycle normalizes routed skill stack fallback and blank routing`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-quality-normalization")
    val env = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    val context = McpRuntimeContext(environment = env, userHome = tempDir)

    val started = recordQualityCheckStarted(context)
    val sessionId = started["session_id"] as String
    recordQualityCheckFinished(context, sessionId)
    recordBlankQualityCheckFinished(context)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertFallbackPayloads(connection, sessionId)
      assertBlankRoutingPayload(connection)
    }
  }
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
  )
}

private fun recordQualityCheckStarted(context: McpRuntimeContext): Map<String, Any?> = McpToolDispatcher.call(
  "quality_check_started",
  mapOf(
    "routed_skill" to "skill-bill:bill-kmp-code-check",
    "detected_stack" to "kmp -> kotlin fallback",
    "fallback" to false,
    "scope_type" to "branch_diff",
    "initial_failure_count" to 1,
    "orchestrated" to false,
  ),
  context,
)

private fun recordQualityCheckFinished(context: McpRuntimeContext, sessionId: String) {
  McpToolDispatcher.call(
    "quality_check_finished",
    mapOf(
      "session_id" to sessionId,
      "final_failure_count" to 0,
      "iterations" to 2,
      "result" to "pass",
      "failing_check_names" to emptyList<String>(),
      "unsupported_reason" to "",
      "orchestrated" to false,
      "routed_skill" to "skill-bill:bill-kmp-code-check",
      "detected_stack" to "kmp -> kotlin fallback",
      "fallback" to false,
      "scope_type" to "branch_diff",
      "initial_failure_count" to 1,
      "duration_seconds" to 5,
    ),
    context,
  )
}

private fun recordBlankQualityCheckFinished(context: McpRuntimeContext) {
  McpToolDispatcher.call(
    "quality_check_finished",
    mapOf(
      "session_id" to "qck-blank-routing",
      "final_failure_count" to 0,
      "iterations" to 1,
      "result" to "skipped",
      "failing_check_names" to emptyList<String>(),
      "unsupported_reason" to "",
      "orchestrated" to false,
      "routed_skill" to "",
      "detected_stack" to "",
      "fallback" to false,
      "scope_type" to "repo",
      "initial_failure_count" to 0,
      "duration_seconds" to 0,
    ),
    context,
  )
}

private fun assertFallbackPayloads(connection: Connection, sessionId: String) {
  val startedPayload = qualityCheckPayload(connection, "skillbill_quality_check_started", sessionId)
  val finishedPayload = qualityCheckPayload(connection, "skillbill_quality_check_finished", sessionId)
  listOf(startedPayload, finishedPayload).forEach { payload ->
    assertEquals("bill-kmp-code-check", payload["routed_skill"])
    assertEquals("kmp", payload["detected_stack"])
    assertEquals(true, payload["fallback"])
    assertEquals("kotlin_quality_check_fallback", payload["fallback_reason"])
  }
}

private fun assertBlankRoutingPayload(connection: Connection) {
  val blankFinishedPayload =
    qualityCheckPayload(connection, "skillbill_quality_check_finished", "qck-blank-routing")
  assertEquals("unrouted", blankFinishedPayload["routed_skill"])
  assertEquals("unknown", blankFinishedPayload["detected_stack"])
  assertEquals(false, blankFinishedPayload["fallback"])
  assertFalse("fallback_reason" in blankFinishedPayload)
}

private fun qualityCheckPayload(connection: Connection, eventName: String, sessionId: String): Map<String, Any?> =
  decodeJsonObject(
    connection.prepareStatement(
      """
      SELECT payload_json
      FROM telemetry_outbox
      WHERE event_name = ?
        AND json_extract(payload_json, '$.session_id') = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, eventName)
      statement.setString(2, sessionId)
      statement.executeQuery().use { resultSet ->
        check(resultSet.next()) { "Expected $eventName payload for session $sessionId." }
        resultSet.getString(1)
      }
    },
  )

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}
