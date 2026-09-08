package skillbill.install

import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.fs.InstallPlanWireValidatorAdapter
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanWireValidator
import skillbill.install.runtime.InstallOperations
import skillbill.launcher.mcp.McpRegistrationOperations
import skillbill.model.EnvironmentContext
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.telemetry.TelemetryLevelMutator

internal val installTestWireValidator: InstallPlanWireValidator = InstallPlanWireValidatorAdapter()

private class InstallTestMcpRegistrationPort(
  private val environment: Map<String, String>,
) : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      mutation = McpRegistrationOperations.register(
        request.agent,
        request.runtimeMcpBin,
        request.home,
        environment,
      ),
    )

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      mutation = McpRegistrationOperations.unregister(
        request.agent,
        request.home,
        environment,
      ),
    )
}

internal fun planInstallForTest(request: InstallPlanRequest): InstallPlan =
  InstallOperations.planInstall(request, installTestWireValidator)

internal fun applyInstallForTest(
  plan: InstallPlan,
  telemetryLevelMutator: TelemetryLevelMutator? = null,
): InstallApplyResult {
  val environment = plan.request.environment.ifEmpty { installTestEnvironment(plan.request.home) }
  val environmentContext = EnvironmentContext(
    environment = environment,
    userHome = plan.request.home,
  )
  return InstallOperations.applyInstall(
    plan,
    telemetryLevelMutator,
    FileTelemetryConfigStore(environmentContext),
    InstallTestMcpRegistrationPort(environment),
  )
}
