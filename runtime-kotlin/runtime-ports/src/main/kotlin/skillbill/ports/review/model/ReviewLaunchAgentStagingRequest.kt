package skillbill.ports.review.model

import java.nio.file.Path

data class ReviewLaunchAgentStagingRequest(
  val agentId: String,
  val reviewLaunchDirectory: Path,
  val logicalWorkerNames: List<String>,
)
