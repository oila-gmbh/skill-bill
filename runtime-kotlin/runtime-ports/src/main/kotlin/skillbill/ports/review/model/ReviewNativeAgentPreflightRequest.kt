package skillbill.ports.review.model

import java.nio.file.Path

data class ReviewNativeAgentPreflightRequest(
  val repoRoot: Path,
  val assignments: List<ReviewNativeAgentAssignment>,
) {
  constructor(repoRoot: Path, agentIds: List<String>, logicalNames: List<String>) : this(
    repoRoot = repoRoot,
    assignments = agentIds.flatMap { agentId ->
      logicalNames.map { logicalName -> ReviewNativeAgentAssignment(agentId, logicalName) }
    },
  )
}

data class ReviewNativeAgentAssignment(
  val agentId: String,
  val logicalName: String,
)
