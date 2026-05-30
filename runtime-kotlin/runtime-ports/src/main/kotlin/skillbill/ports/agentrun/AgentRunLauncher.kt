package skillbill.ports.agentrun

import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest

interface AgentRunLauncher {
  fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome
}
