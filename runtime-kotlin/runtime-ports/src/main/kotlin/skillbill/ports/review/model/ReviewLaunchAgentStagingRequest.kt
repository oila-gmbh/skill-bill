package skillbill.ports.review.model

import skillbill.agent.model.AgentId
import java.nio.file.Path

data class ReviewLaunchAgentStagingRequest(
  val agentId: AgentId,
  val reviewLaunchDirectory: Path,
  val logicalWorkerNames: List<String>,
)
