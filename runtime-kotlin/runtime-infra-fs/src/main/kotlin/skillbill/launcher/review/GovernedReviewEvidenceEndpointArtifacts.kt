package skillbill.launcher.review

import skillbill.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import java.nio.file.Files
import java.nio.file.Path

internal fun deleteGovernedReviewEndpointArtifacts(
  descriptor: GovernedReviewEvidenceEndpointDescriptor,
  directory: Path,
) {
  runCatching { Files.deleteIfExists(descriptor.socketPath) }
  runCatching { Files.deleteIfExists(descriptor.mcpConfigPath) }
  runCatching { Files.deleteIfExists(GovernedReviewMcpConfigWriter.tomlConfigPath(descriptor.mcpConfigPath)) }
  val cursorConfig = GovernedReviewMcpConfigWriter.cursorProjectConfigPath(descriptor.mcpConfigPath)
  runCatching { Files.deleteIfExists(cursorConfig) }
  runCatching { Files.deleteIfExists(cursorConfig.parent) }
  runCatching { Files.deleteIfExists(directory) }
}
