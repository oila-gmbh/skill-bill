package skillbill.mcp.scaffold

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.mcpClock
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
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
    val runtimeComponent = RuntimeComponent::class.create(context.toRuntimeContext())
    val resolvedRoot = runtimeComponent.resolvedEnvironmentContext.repositoryRoot
    val sessionId = generateNewSkillSessionId(mcpClock(runtimeComponent))
    val outcome = runCatching {
      val request = parseMcpScaffoldCommandRequest(payload + ("repo_root" to resolvedRoot.toString()))
      val result = runtimeComponent.scaffoldGateway.scaffold(request, dryRun)
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

  private fun generateNewSkillSessionId(clock: Clock): String {
    val date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
    val suffix = UUID.randomUUID().toString().take(NEW_SKILL_SESSION_ID_SUFFIX_LENGTH)
    return "nss-$date-$suffix"
  }
}
