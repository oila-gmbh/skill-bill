@file:Suppress("LongMethod", "TooGenericExceptionCaught", "MagicNumber", "UnusedParameter")

package skillbill.mcp

import skillbill.scaffold.scaffold
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

object McpScaffoldRuntime {
  fun newSkillScaffold(
    payload: Map<String, Any?>,
    dryRun: Boolean = false,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val sessionId = generateNewSkillSessionId()
    val repoRoot = findRepoRoot()
    return try {
      val result = scaffold(payload + ("repo_root" to repoRoot.toString()), dryRun = dryRun)
      val outcome = if (dryRun) "dry-run" else "success"
      val baseTelemetryPayload =
        mapOf(
          "session_id" to sessionId,
          "kind" to result.kind,
          "skill_name" to result.skillName,
          "platform" to payload["platform"].orEmpty(),
          "family" to payload["family"].orEmpty(),
          "area" to payload["area"].orEmpty(),
          "result" to outcome,
          "duration_seconds" to 0,
          "skill" to "bill-create-skill",
        )

      if (orchestrated) {
        mapOf(
          "mode" to "orchestrated",
          "telemetry_payload" to baseTelemetryPayload - "session_id",
          "skill_path" to result.skillPath.toString(),
          "notes" to result.notes,
        )
      } else {
        mapOf(
          "status" to "ok",
          "session_id" to sessionId,
          "skill_path" to result.skillPath.toString(),
          "notes" to result.notes,
        )
      }
    } catch (error: Throwable) {
      if (orchestrated) {
        mapOf(
          "mode" to "orchestrated",
          "telemetry_payload" to
            mapOf(
              "session_id" to sessionId,
              "kind" to payload["kind"].orEmpty(),
              "skill_name" to payload["name"].orEmpty(),
              "platform" to payload["platform"].orEmpty(),
              "family" to payload["family"].orEmpty(),
              "area" to payload["area"].orEmpty(),
              "result" to "failed",
              "duration_seconds" to 0,
              "skill" to "bill-create-skill",
              "error" to error.message.orEmpty(),
            ) - "session_id",
          "error" to error.message.orEmpty(),
        )
      } else {
        mapOf(
          "status" to "error",
          "session_id" to sessionId,
          "error" to error.message.orEmpty(),
        )
      }
    }
  }

  private fun generateNewSkillSessionId(): String {
    val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
    val suffix = UUID.randomUUID().toString().take(4)
    return "nss-$date-$suffix"
  }

  private fun findRepoRoot(start: Path = Path.of("").toAbsolutePath().normalize()): Path {
    var current = start
    while (true) {
      val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
      val hasSkills = Files.isDirectory(current.resolve("skills"))
      if (hasSettings && hasSkills) {
        return current
      }
      val parent = current.parent ?: break
      current = parent
    }
    return start
  }

  private fun Any?.orEmpty(): String = this as? String ?: ""
}
