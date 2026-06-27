package skillbill.cli.core

import com.github.ajalt.clikt.core.UsageError
import skillbill.install.model.OPENCODE_RUNTIME_REFUSAL_MESSAGE
import skillbill.install.model.isRuntimeRefusedAgent

// Shared runtime-refusal gate for every CLI entry point that can resolve a runtime agent
// (feature-task, goal, code-review-parallel). Callers collect the agent ids reachable on their own
// command surface and pass them here, so the predicate and the actionable message stay in one place
// instead of being re-derived per command. Refuses before any workflow, branch, or subprocess.
fun refuseRuntimeRefusedAgents(candidateAgentIds: List<String?>) {
  if (candidateAgentIds.any(::isRuntimeRefusedAgent)) {
    throw UsageError(OPENCODE_RUNTIME_REFUSAL_MESSAGE)
  }
}
