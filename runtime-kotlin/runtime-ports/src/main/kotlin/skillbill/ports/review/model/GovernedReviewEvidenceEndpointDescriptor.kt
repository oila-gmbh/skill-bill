package skillbill.ports.review.model

import java.nio.file.Path

/**
 * Per-launch descriptor the command builder needs to point a governed worker at its own evidence
 * endpoint. The token is transport credential only: it never reaches telemetry, prompts, or
 * accounting records.
 */
data class GovernedReviewEvidenceEndpointDescriptor(
  val lane: String,
  val socketPath: Path,
  val mcpConfigPath: Path,
  val token: String,
) {
  init {
    require(lane.isNotBlank()) { "A governed review evidence endpoint must name its lane." }
    require(token.isNotBlank()) { "A governed review evidence endpoint must carry a per-launch token." }
  }
}
