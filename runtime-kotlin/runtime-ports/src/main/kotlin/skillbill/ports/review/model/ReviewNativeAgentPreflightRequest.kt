package skillbill.ports.review.model

import java.nio.file.Path

data class ReviewNativeAgentPreflightRequest(
  val repoRoot: Path,
  val agentIds: List<String>,
  val logicalNames: List<String>,
)
