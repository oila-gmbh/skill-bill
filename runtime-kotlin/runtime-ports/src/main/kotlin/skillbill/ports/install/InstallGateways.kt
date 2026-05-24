package skillbill.ports.install

import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.McpMutationResult
import skillbill.ports.install.model.InstallCleanupResult
import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path

interface InstallPlanGateway {
  fun planInstall(request: InstallPlanRequest): InstallPlan

  fun applyInstall(plan: InstallPlan, telemetryLevelMutator: TelemetryLevelMutator?): InstallApplyResult

  fun linkSkill(source: Path, targetDir: Path, agent: String, repoRoot: Path?, home: Path?): List<Path>
}

interface InstallAgentGateway {
  fun agentPath(agent: String, home: Path?): Path

  fun detectAgentTargets(home: Path?): List<AgentTarget>

  fun codexAgentsPath(home: Path?): Path

  fun claudeAgentsPath(home: Path?): Path

  fun opencodeAgentsPath(home: Path?): Path

  fun junieAgentsPath(home: Path?): Path

  fun cleanupAgentTarget(
    targetDir: Path,
    skillNames: List<String>,
    legacyNames: List<String>,
    managedInstallMarker: String,
  ): InstallCleanupResult
}

interface NativeAgentInstallGateway {
  fun linkNativeAgents(provider: NativeAgentLinkProvider, request: NativeAgentLinkRequest): NativeAgentLinkOutcome

  fun unlinkNativeAgents(provider: NativeAgentLinkProvider, request: NativeAgentLinkRequest): List<Path>
}

interface McpRegistrationGateway {
  fun registerMcp(agent: String, runtimeMcpBin: Path, home: Path?): McpMutationResult

  fun unregisterMcp(agent: String, home: Path?): McpMutationResult
}
