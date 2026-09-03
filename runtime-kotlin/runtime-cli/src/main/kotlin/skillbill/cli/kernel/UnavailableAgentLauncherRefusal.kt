package skillbill.cli.kernel

import com.github.ajalt.clikt.core.UsageError
import skillbill.install.model.unavailableAgentLauncherReason
import skillbill.ports.agentrun.ExecutableLookup

// Companion gate for agents whose runtime path exists but whose headless CLI is absent. Install-time
// agent detection keys off the agent's home directory, which an IDE creates without ever installing
// a headless CLI, so this is the first point that can tell an operator the launch will fail — before
// a goal record, branch, or child process exists.
fun refuseUnavailableAgentLaunchers(candidateAgentIds: List<String?>, executableLookup: ExecutableLookup) {
  candidateAgentIds
    .firstNotNullOfOrNull { agentId -> unavailableAgentLauncherReason(agentId, executableLookup::onPath) }
    ?.let { reason -> throw UsageError(reason) }
}
