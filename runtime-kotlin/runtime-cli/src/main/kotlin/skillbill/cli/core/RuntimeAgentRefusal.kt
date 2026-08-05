package skillbill.cli.core

import com.github.ajalt.clikt.core.UsageError
import skillbill.install.model.RUNTIME_REFUSED_AGENT_MESSAGE
import skillbill.install.model.isRuntimeRefusedAgent
import skillbill.install.model.unavailableAgentLauncherReason
import skillbill.ports.agentrun.ExecutableLookup

// Shared runtime-refusal gate for every CLI entry point that can resolve a runtime agent
// (feature-task, goal, code-review-parallel). Callers collect the agent ids reachable on their own
// command surface and pass them here, so the predicate and the actionable message stay in one place
// instead of being re-derived per command. Refuses before any workflow, branch, or subprocess.
fun refuseRuntimeRefusedAgents(candidateAgentIds: List<String?>) {
  if (candidateAgentIds.any(::isRuntimeRefusedAgent)) {
    throw UsageError(RUNTIME_REFUSED_AGENT_MESSAGE)
  }
}

// Companion gate for agents whose runtime path exists but whose headless CLI is absent. Install-time
// agent detection keys off the agent's home directory, which an IDE creates without ever installing
// a headless CLI, so this is the first point that can tell an operator the launch will fail — before
// a goal record, branch, or child process exists.
fun refuseUnavailableAgentLaunchers(candidateAgentIds: List<String?>, executableLookup: ExecutableLookup) {
  candidateAgentIds
    .firstNotNullOfOrNull { agentId -> unavailableAgentLauncherReason(agentId, executableLookup::onPath) }
    ?.let { reason -> throw UsageError(reason) }
}
