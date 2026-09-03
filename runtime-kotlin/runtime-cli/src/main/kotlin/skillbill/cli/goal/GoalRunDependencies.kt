package skillbill.cli.goal

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.system.RuntimeProvenanceService
import skillbill.application.telemetry.TelemetryService
import skillbill.cli.core.CliRunInputs
import skillbill.cli.core.CliRunState
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.system.HostPlatformPort

@Inject
data class GoalRunDependencies(
  val goalRunner: GoalRunner,
  val runtimeProvenanceService: RuntimeProvenanceService,
  val agentAddonSelectionPort: AgentAddonSelectionPort,
  val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  val executableLookup: ExecutableLookup,
  val telemetryService: TelemetryService,
  val state: CliRunState,
  val inputs: CliRunInputs,
  val hostPlatform: HostPlatformPort,
)
