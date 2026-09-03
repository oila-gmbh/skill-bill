package skillbill.cli.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.application.telemetry.TelemetryService
import skillbill.cli.core.CliRunInputs
import skillbill.cli.core.CliRunState
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource

@Inject
data class FeatureTaskRuntimeRunDependencies(
  val runner: FeatureTaskRuntimeRunner,
  val workerCoordinator: FeatureTaskRuntimeWorkerCoordinator,
  val runInvariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  val specPathResolver: FeatureSpecPathResolverPort,
  val configResolutionService: ConfigResolutionService,
  val agentAddonSelectionPort: AgentAddonSelectionPort,
  val executableLookup: ExecutableLookup,
  val telemetryService: TelemetryService,
  val state: CliRunState,
  val inputs: CliRunInputs,
)
