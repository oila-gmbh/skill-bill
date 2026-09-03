package skillbill.cli.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.application.telemetry.TelemetryService
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.diagnostics.RuntimeDiagnostics
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
  val diagnostics: RuntimeDiagnostics,
  val state: CliRunState,
  val inputs: CliRunInputs,
)
