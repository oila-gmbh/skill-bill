package skillbill.install

import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.McpRegistrationApplyOutcome
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.launcher.McpRegistrationOperations
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.telemetry.DEFAULT_TELEMETRY_BATCH_SIZE
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.parsePositiveTelemetryInt
import skillbill.telemetry.parseTelemetryLevelValue
import skillbill.telemetry.telemetryLevels
import java.nio.file.Files
import java.nio.file.Path

internal fun applyTelemetryIntent(
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
  telemetryLevelMutator: TelemetryLevelMutator? = null,
): InstallTelemetryApplyOutcome {
  val configPath = plan.request.home.resolve(".skill-bill/config.json").toAbsolutePath().normalize()
  val existedBefore = Files.exists(configPath)
  return runCatching {
    val environmentContext = EnvironmentContext(environment = emptyMap(), userHome = plan.request.home)
    val clearedEvents =
      applyInstallTelemetryLevel(environmentContext, plan.telemetryLevel.id, telemetryLevelMutator)
    val status =
      if (plan.telemetryLevel.id == "off" && !existedBefore && clearedEvents == 0) {
        InstallTelemetryApplyStatus.SKIPPED
      } else {
        InstallTelemetryApplyStatus.SUCCESS
      }
    InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = status,
      configPath = configPath,
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

private fun applyInstallTelemetryLevel(
  context: EnvironmentContext,
  level: String,
  telemetryLevelMutator: TelemetryLevelMutator?,
): Int {
  telemetryLevelMutator?.let { mutator ->
    return mutator.setLevel(level, context.dbPathOverride).clearedEvents
  }
  require(level in telemetryLevels) {
    "Telemetry level must be one of: ${telemetryLevels.joinToString(", ")}."
  }
  val configStore = FileTelemetryConfigStore(context)
  return if (level == "off") {
    configStore.delete()
    0
  } else {
    val payload = configStore.ensure().payload.toMutableMap()
    val telemetry =
      (
        (payload["telemetry"] as? Map<*, *>)
          ?.entries
          ?.filter { it.key is String }
          ?.associate { it.key as String to it.value }
          ?.toMutableMap()
        )
        ?: throw IllegalArgumentException(
          "Telemetry config at '${configStore.configPath()}' must contain a 'telemetry' object.",
        )
    telemetry["level"] = level
    telemetry.remove("enabled")
    payload["telemetry"] = telemetry
    configStore.write(TelemetryConfigDocument(payload))
    validateInstallTelemetryConfig(configStore)
    0
  }
}

private fun validateInstallTelemetryConfig(configStore: FileTelemetryConfigStore) {
  val payload = configStore.read()?.payload
    ?: throw IllegalArgumentException("Telemetry config at '${configStore.configPath()}' is missing.")
  val telemetry =
    (payload["telemetry"] as? Map<*, *>)
      ?.entries
      ?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw IllegalArgumentException(
        "Telemetry config at '${configStore.configPath()}' must contain a 'telemetry' object.",
      )
  val level = parseTelemetryLevelValue(telemetry["level"]?.toString() ?: "anonymous", "telemetry.level")
  val installId = payload["install_id"]?.toString()?.trim().orEmpty()
  require(level == "off" || installId.isNotBlank()) {
    "Telemetry is enabled but no install_id is configured at '${configStore.configPath()}'. " +
      "Run 'skill-bill telemetry enable' to create one."
  }
  val batchSizeRaw = telemetry["batch_size"] ?: DEFAULT_TELEMETRY_BATCH_SIZE
  when (batchSizeRaw) {
    is Int -> require(batchSizeRaw > 0) { "telemetry.batch_size must be greater than zero." }
    is Number -> require(batchSizeRaw.toInt() > 0) { "telemetry.batch_size must be greater than zero." }
    else -> parsePositiveTelemetryInt(batchSizeRaw.toString(), "telemetry.batch_size")
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
    message = mcpRegistrationMessage(result),
    profiles = result.profiles,
  )
}.getOrElse { error ->
  val succeeded = (error as? ClaudeMcpProfileFailure)?.succeeded.orEmpty()
  failedMcpRegistrationOutcome(
    agent = agent,
    message = if (succeeded.isEmpty()) {
      error.message.orEmpty()
    } else {
      "${error.message.orEmpty()}. Already updated: ${succeeded.joinToString(", ") { it.configPath.toString() }}"
    },
    warnings = warnings,
    error = error,
    profiles = succeeded,
  )
}

private fun mcpRegistrationMessage(result: skillbill.install.model.McpMutationResult): String {
  val base = if (result.changed) "MCP registration updated." else "MCP registration already up to date."
  if (result.profiles.size <= 1) {
    return base
  }
  val paths = result.profiles.joinToString(", ") { it.configPath.toString() }
  return "$base Profiles (${result.profiles.size}): $paths"
}

private fun failedMcpRegistrationOutcome(
  agent: InstallAgent,
  message: String,
  warnings: MutableList<InstallApplyIssue>,
  error: Throwable? = null,
  profiles: List<skillbill.install.model.McpProfileOutcome> = emptyList(),
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
    message = if (profiles.isEmpty()) "MCP registration failed." else message,
    issue = issue,
    profiles = profiles,
  )
}
