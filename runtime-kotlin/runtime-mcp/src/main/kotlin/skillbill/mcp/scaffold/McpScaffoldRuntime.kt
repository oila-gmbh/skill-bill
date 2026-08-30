@file:Suppress("TooGenericExceptionCaught", "MagicNumber", "UnusedParameter")

package skillbill.mcp.scaffold

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.mcp.core.McpRuntimeContext
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
      val request = parseMcpScaffoldCommandRequest(payload + ("repo_root" to repoRoot.toString()))
      val result =
        RuntimeComponent::class.create(context.toRuntimeContext())
          .scaffoldService
          .scaffold(request, dryRun)
      scaffoldSuccessMap(
        sessionId = sessionId,
        payload = payload,
        result = result,
        dryRun = dryRun,
        orchestrated = orchestrated,
      )
    } catch (error: Throwable) {
      scaffoldFailureMap(
        sessionId = sessionId,
        payload = payload,
        orchestrated = orchestrated,
        error = error,
      )
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
}
