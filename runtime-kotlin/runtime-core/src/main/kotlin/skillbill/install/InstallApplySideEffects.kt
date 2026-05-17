package skillbill.install

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.McpRegistrationApplyOutcome
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.launcher.McpRegistrationOperations
import skillbill.model.RuntimeContext
import java.nio.file.Files
import java.nio.file.Path

internal fun applyTelemetryIntent(
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
): InstallTelemetryApplyOutcome {
  val configPath = plan.request.home.resolve(".skill-bill/config.json").toAbsolutePath().normalize()
  val existedBefore = Files.exists(configPath)
  return runCatching {
    val runtimeContext = RuntimeContext(environment = emptyMap(), userHome = plan.request.home)
    val component = RuntimeComponent::class.create(runtimeContext)
    val payload = component.telemetryService.setLevel(plan.telemetryLevel.id, dbOverride = null)
    val clearedEvents = (payload["cleared_events"] as? Number)?.toInt() ?: 0
    val status =
      if (plan.telemetryLevel.id == "off" && !existedBefore && clearedEvents == 0) {
        InstallTelemetryApplyStatus.SKIPPED
      } else {
        InstallTelemetryApplyStatus.SUCCESS
      }
    InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = status,
      configPath = Path.of(payload["config_path"].toString()).toAbsolutePath().normalize(),
      clearedEvents = clearedEvents,
      message = telemetryOutcomeMessage(plan.telemetryLevel.id, status),
    )
  }.getOrElse { error ->
    val issue = InstallApplyIssue(
      kind = InstallApplyIssueKind.TELEMETRY_APPLY_FAILED,
      message = error.message.orEmpty(),
      causeClass = error::class.qualifiedName,
    )
    warnings.add(issue)
    InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = InstallTelemetryApplyStatus.FAILED,
      configPath = configPath,
      message = "Telemetry setup failed.",
      issue = issue,
    )
  }
}

internal fun applyMcpRegistrationIntent(
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
): List<McpRegistrationApplyOutcome> {
  val intent = plan.mcpRegistrationIntent
  val runtimeMcpBin = intent.runtimeMcpBin
  return when {
    !intent.register -> skippedMcpRegistrationOutcomes(plan, "MCP registration not requested.")
    runtimeMcpBin == null -> intent.agents.map { agent ->
      failedMcpRegistrationOutcome(
        agent = agent,
        message = "MCP registration requested but no runtime-mcp binary was planned.",
        warnings = warnings,
      )
    }
    else -> intent.agents.map { agent ->
      registerMcpAgent(agent, runtimeMcpBin, plan, warnings)
    }
  }
}

internal fun skippedTelemetryOutcome(plan: InstallPlan, message: String): InstallTelemetryApplyOutcome =
  InstallTelemetryApplyOutcome(
    level = plan.telemetryLevel,
    status = InstallTelemetryApplyStatus.SKIPPED,
    configPath = plan.request.home.resolve(".skill-bill/config.json").toAbsolutePath().normalize(),
    message = message,
  )

internal fun skippedMcpRegistrationOutcomes(plan: InstallPlan, message: String): List<McpRegistrationApplyOutcome> =
  plan.mcpRegistrationIntent.agents.map { agent ->
    McpRegistrationApplyOutcome(
      agent = agent,
      status = McpRegistrationApplyStatus.SKIPPED,
      message = message,
    )
  }

private fun telemetryOutcomeMessage(level: String, status: InstallTelemetryApplyStatus): String = when (status) {
  InstallTelemetryApplyStatus.SUCCESS -> "Telemetry level set to '$level'."
  InstallTelemetryApplyStatus.SKIPPED -> "Telemetry was already off."
  InstallTelemetryApplyStatus.FAILED -> "Telemetry setup failed."
}

private fun registerMcpAgent(
  agent: InstallAgent,
  runtimeMcpBin: Path,
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
): McpRegistrationApplyOutcome = runCatching {
  val result = McpRegistrationOperations.register(agent.id, runtimeMcpBin, plan.request.home)
  McpRegistrationApplyOutcome(
    agent = agent,
    status = McpRegistrationApplyStatus.SUCCESS,
    configPath = result.configPath,
    changed = result.changed,
    message = if (result.changed) {
      "MCP registration updated."
    } else {
      "MCP registration already up to date."
    },
  )
}.getOrElse { error ->
  failedMcpRegistrationOutcome(
    agent = agent,
    message = error.message.orEmpty(),
    warnings = warnings,
    error = error,
  )
}

private fun failedMcpRegistrationOutcome(
  agent: InstallAgent,
  message: String,
  warnings: MutableList<InstallApplyIssue>,
  error: Throwable? = null,
): McpRegistrationApplyOutcome {
  val issue = InstallApplyIssue(
    kind = InstallApplyIssueKind.MCP_REGISTRATION_FAILED,
    message = message,
    agent = agent,
    causeClass = error?.let { it::class.qualifiedName },
  )
  warnings.add(issue)
  return McpRegistrationApplyOutcome(
    agent = agent,
    status = McpRegistrationApplyStatus.FAILED,
    message = "MCP registration failed.",
    issue = issue,
  )
}
