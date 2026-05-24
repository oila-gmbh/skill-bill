package skillbill.ports.install.apply.model

import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.ports.telemetry.TelemetryLevelMutator

data class InstallApplyExecutionRequest(
  val plan: InstallPlan,
  val telemetryLevelMutator: TelemetryLevelMutator?,
)

data class InstallApplyExecutionResult(
  val result: InstallApplyResult,
)
