package skillbill.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.TelemetryService

@Inject
class TelemetryLocalCommands(
  statusCommand: TelemetryStatusCommand,
  syncCommand: TelemetrySyncCommand,
  enableCommand: TelemetryEnableCommand,
  disableCommand: TelemetryDisableCommand,
  setLevelCommand: TelemetrySetLevelCommand,
) {
  val commands = listOf(statusCommand, syncCommand, enableCommand, disableCommand, setLevelCommand)
}

@Inject
class TelemetryRemoteCommands(
  capabilitiesCommand: TelemetryCapabilitiesCommand,
  statsCommand: TelemetryStatsCommand,
) {
  val commands = listOf(capabilitiesCommand, statsCommand)
}

@Inject
class TelemetryCommand(
  localCommands: TelemetryLocalCommands,
  remoteCommands: TelemetryRemoteCommands,
) : DocumentedNoOpCliCommand("telemetry", "Inspect, control, and manually sync remote telemetry.") {
  init {
    subcommands(localCommands.commands + remoteCommands.commands)
  }
}

@Inject
class TelemetryStatusCommand(
  private val service: TelemetryService,
  private val state: CliRunState,
) : DocumentedCliCommand("status", "Show local telemetry configuration and sync status.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.status(state.dbOverride), format)
  }
}

@Inject
class TelemetrySyncCommand(
  private val service: TelemetryService,
  private val state: CliRunState,
) : DocumentedCliCommand("sync", "Flush pending telemetry events to the active proxy target.") {
  private val format by formatOption()

  override fun run() {
    val result = service.sync(state.dbOverride)
    state.complete(result.payload, format, result.exitCode)
  }
}

@Inject
class TelemetryCapabilitiesCommand(
  private val service: TelemetryService,
  private val state: CliRunState,
) : DocumentedCliCommand("capabilities", "Show which relay telemetry operations the endpoint supports.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.capabilities(), format)
  }
}

@Inject
class TelemetryStatsCommand(
  private val service: TelemetryService,
  private val state: CliRunState,
) : DocumentedCliCommand("stats", "Fetch aggregate org-wide workflow metrics from the telemetry proxy.") {
  private val workflow by argument(help = "Workflow family to query.").choice("verify", "implement")
  private val since by option("--since", help = "Relative lookback window in <days>d format.").default("")
  private val dateFrom by option("--date-from", help = "Inclusive start date in YYYY-MM-DD format.").default("")
  private val dateTo by option("--date-to", help = "Inclusive end date in YYYY-MM-DD format.").default("")
  private val groupBy by option("--group-by", help = "Optional time bucket series.").default("")
  private val format by formatOption()

  override fun run() {
    state.complete(service.remoteStats(workflow, since, dateFrom, dateTo, groupBy), format)
  }
}

@Inject
class TelemetryEnableCommand(
  private val service: TelemetryService,
  private val state: CliRunState,
) : DocumentedCliCommand("enable", "Enable remote telemetry sync.") {
  private val level by option("--level", help = "Telemetry detail level.")
    .choice("anonymous", "full")
    .default("anonymous")
  private val format by formatOption()

  override fun run() {
    state.complete(service.setLevel(level, state.dbOverride), format)
  }
}

@Inject
class TelemetryDisableCommand(
  private val service: TelemetryService,
  private val state: CliRunState,
) : DocumentedCliCommand("disable", "Disable remote telemetry sync.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.setLevel("off", state.dbOverride), format)
  }
}

@Inject
class TelemetrySetLevelCommand(
  private val service: TelemetryService,
  private val state: CliRunState,
) : DocumentedCliCommand("set-level", "Set the telemetry detail level directly.") {
  private val level by argument(help = "Telemetry level.").choice("off", "anonymous", "full")
  private val format by formatOption()

  override fun run() {
    state.complete(service.setLevel(level, state.dbOverride), format)
  }
}
