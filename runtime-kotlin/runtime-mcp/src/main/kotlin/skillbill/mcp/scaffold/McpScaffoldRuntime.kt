package skillbill.mcp.scaffold

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.mcp.core.McpRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val NEW_SKILL_SESSION_ID_SUFFIX_LENGTH = 4

object McpScaffoldRuntime {
  fun newSkillScaffold(
    payload: Map<String, Any?>,
    dryRun: Boolean = false,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val sessionId = generateNewSkillSessionId()
    val repoRoot = findRepoRoot()
    val outcome = runCatching {
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
    }
    val error = outcome.exceptionOrNull()
    return if (error == null) {
      outcome.getOrThrow()
    } else {
      when (error) {
        is CancellationException -> throw error
        is Exception -> scaffoldFailureMap(
          sessionId = sessionId,
          payload = payload,
          orchestrated = orchestrated,
          error = error,
        )
        else -> throw error
      }
    }
  }

  private fun generateNewSkillSessionId(): String {
    val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
    val suffix = UUID.randomUUID().toString().take(NEW_SKILL_SESSION_ID_SUFFIX_LENGTH)
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
