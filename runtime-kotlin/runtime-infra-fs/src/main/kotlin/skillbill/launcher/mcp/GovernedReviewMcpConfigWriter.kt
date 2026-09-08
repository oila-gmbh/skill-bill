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
    val env = linkedMapOf(
      GovernedReviewEvidenceCodec.SOCKET_ENV to socketPath.toString(),
      GovernedReviewEvidenceCodec.TOKEN_ENV to token,
      GovernedReviewEvidenceCodec.LANE_ENV to lane,
    )
    val settings = linkedMapOf<String, Any?>(
      "mcpServers" to linkedMapOf(
        GovernedReviewEvidenceCodec.SERVER_NAME to linkedMapOf(
          "type" to "stdio",
          "command" to bridgeCommand.first(),
          "args" to bridgeCommand.drop(1),
          "env" to env,
        ),
      ),
    )
    try {
      writeJson(configPath, settings)
      Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"))
      val cursorConfig = cursorProjectConfigPath(configPath)
      Files.createDirectories(cursorConfig.parent)
      writeJson(cursorConfig, settings)
      Files.setPosixFilePermissions(cursorConfig, PosixFilePermissions.fromString("rw-------"))
      val tomlPath = tomlConfigPath(configPath)
      McpTomlConfig.writeGovernedServer(
        tomlPath,
        McpTomlConfig.GovernedServer(
          serverName = GovernedReviewEvidenceCodec.SERVER_NAME,
          command = bridgeCommand.first(),
          args = bridgeCommand.drop(1),
          env = env,
          enabledTools = GovernedReviewEvidenceCodec.OPERATIONS,
        ),
      )
      Files.setPosixFilePermissions(tomlPath, PosixFilePermissions.fromString("rw-------"))
    } catch (error: IOException) {
      throw GovernedReviewEvidenceTransportError(
        "Failed to write the per-launch governed review MCP config at '$configPath'.",
        error,
      )
    }
    return configPath
  }

  fun cursorProjectConfigPath(mcpConfigPath: Path): Path = mcpConfigPath.parent.resolve(".cursor").resolve("mcp.json")

  fun tomlConfigPath(mcpConfigPath: Path): Path = mcpConfigPath.resolveSibling("mcp.toml")

  fun codexConfigOverrides(mcpConfigPath: Path, socketPath: Path, token: String, lane: String): List<String> {
    val prefix = "mcp_servers.${GovernedReviewEvidenceCodec.SERVER_NAME}"
    val server = if (Files.isRegularFile(mcpConfigPath)) {
      mutableStringAnyMap(readJsonObject(mcpConfigPath)["mcpServers"])
        .let { mutableStringAnyMap(it[GovernedReviewEvidenceCodec.SERVER_NAME]) }
    } else {
      linkedMapOf()
    }
    val command = server["command"]?.toString().orEmpty()
    val args = (server["args"] as? List<*>)?.map { it.toString() }.orEmpty()
    val env = mutableStringAnyMap(server["env"]).ifEmpty {
      linkedMapOf(
        GovernedReviewEvidenceCodec.SOCKET_ENV to socketPath.toString(),
        GovernedReviewEvidenceCodec.TOKEN_ENV to token,
        GovernedReviewEvidenceCodec.LANE_ENV to lane,
      )
    }
    return buildList {
      if (command.isNotEmpty()) add("$prefix.command=\"$command\"")
      add("$prefix.args=[${args.joinToString(", ") { "\"$it\"" }}]")
      add("$prefix.enabled_tools=[${GovernedReviewEvidenceCodec.OPERATIONS.joinToString(", ") { "\"$it\"" }}]")
      add("$prefix.env={${env.entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }}}")
    }
  }
}
