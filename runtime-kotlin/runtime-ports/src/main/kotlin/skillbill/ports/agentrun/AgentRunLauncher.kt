package skillbill.ports.agentrun

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.model.NativeReviewWorkerRequest

interface AgentRunLauncher {
  fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome

  /** Provider-native fresh-context entry point. Review callers must not adapt this to [launch]. */
  fun launchNativeReview(request: NativeReviewWorkerRequest): AgentRunLaunchOutcome = UnsupportedAgentRunLaunch(
    InstallAgent.fromNormalizedId(request.agentId),
    "Agent '${request.agentId}' does not expose a provider-native governed review start.",
  )
}
