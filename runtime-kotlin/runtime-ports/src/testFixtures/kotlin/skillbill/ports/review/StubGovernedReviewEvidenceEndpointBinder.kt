package skillbill.ports.review

import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import java.nio.file.Path

/**
 * Binds nothing. Fixtures that never spawn a child process still have to satisfy the
 * supplied-together requirement a governed launch carries.
 */
fun stubGovernedReviewEvidenceEndpointBinder(root: Path): GovernedReviewEvidenceEndpointBinder =
  GovernedReviewEvidenceEndpointBinder { lane, _ ->
    object : GovernedReviewEvidenceEndpointHandle {
      override val descriptor = GovernedReviewEvidenceEndpointDescriptor(
        lane = lane,
        socketPath = root.resolve("evidence.sock"),
        mcpConfigPath = root.resolve("mcp.json"),
        token = "stub-token",
      )

      override fun close() = Unit
    }
  }
