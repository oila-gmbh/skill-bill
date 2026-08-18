package skillbill.launcher.mcp

import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

object GovernedReviewMcpConfigWriter {
  fun write(configPath: Path, bridgeCommand: List<String>, socketPath: Path, token: String, lane: String): Path {
    require(bridgeCommand.isNotEmpty()) { "A governed review MCP bridge command is required." }
    val settings = linkedMapOf<String, Any?>(
      "mcpServers" to linkedMapOf(
        GovernedReviewEvidenceCodec.SERVER_NAME to linkedMapOf(
          "type" to "stdio",
          "command" to bridgeCommand.first(),
          "args" to bridgeCommand.drop(1),
          "env" to linkedMapOf(
            GovernedReviewEvidenceCodec.SOCKET_ENV to socketPath.toString(),
            GovernedReviewEvidenceCodec.TOKEN_ENV to token,
            GovernedReviewEvidenceCodec.LANE_ENV to lane,
          ),
        ),
      ),
    )
    try {
      writeJson(configPath, settings)
      Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"))
    } catch (error: IOException) {
      throw GovernedReviewEvidenceTransportError(
        "Failed to write the per-launch governed review MCP config at '$configPath'.",
        error,
      )
    }
    return configPath
  }
}
