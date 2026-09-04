package skillbill.install.runtime

import skillbill.install.apply.applyInstallPlan
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanWireValidator
import skillbill.install.plan.buildInstallPlan
import skillbill.install.plan.detectAgents
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path
import skillbill.install.plan.codexAgentsPath as planCodexAgentsPath

object InstallOperations {
  fun planInstall(request: InstallPlanRequest, wireValidator: InstallPlanWireValidator): InstallPlan =
    buildInstallPlan(request, wireValidator)

  fun applyInstall(
    plan: InstallPlan,
    telemetryLevelMutator: TelemetryLevelMutator? = null,
    telemetryConfigStore: TelemetryConfigStore? = null,
    mcpRegistrationPort: InstallMcpRegistrationPort,
  ): InstallApplyResult = applyInstallPlan(plan, telemetryLevelMutator, telemetryConfigStore, mcpRegistrationPort)

  fun agentPath(agent: String, home: Path? = null, environment: Map<String, String> = System.getenv()): Path =
    InstallOperationsPaths.agentPath(agent, home, environment)

  fun detectAgentTargets(home: Path? = null, environment: Map<String, String> = System.getenv()): List<AgentTarget> =
    detectAgents(home, environment)

  fun claudeRoots(home: Path? = null, environment: Map<String, String> = System.getenv()): List<Path> =
    InstallOperationsPaths.claudeRoots(home, environment)

  fun codexAgentsPath(home: Path? = null, environment: Map<String, String> = System.getenv()): Path =
    planCodexAgentsPath(home, environment)

  fun codexRoots(home: Path? = null, environment: Map<String, String> = System.getenv()): List<Path> =
    InstallOperationsPaths.codexRoots(home, environment)

  fun claudeAgentsPath(home: Path? = null, environment: Map<String, String> = System.getenv()): Path =
    InstallOperationsPaths.claudeAgentsPath(home, environment)

  fun junieAgentsPath(home: Path? = null): Path = InstallOperationsPaths.junieAgentsPath(home)

  fun cursorAgentsPath(home: Path? = null): Path = InstallOperationsPaths.cursorAgentsPath(home)
}
