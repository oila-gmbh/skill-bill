package skillbill.mcp

import skillbill.contracts.JsonSupport
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object ReadianMcpRuntime {
  private const val AUTHENTICATED_ENV = "SKILL_BILL_READIAN_AUTHENTICATED"
  private const val COMMAND_ENV = "SKILL_BILL_READIAN_MCP_COMMAND"
  private const val LEGACY_COMMAND_ENV = "READIAN_MCP_COMMAND"
  private const val REQUEST_TIMEOUT_SECONDS = 20L

  private val delegatedTools =
    setOf(
      "readian_auth_status",
      "readian_get_articles_for_topic_query",
      "readian_get_spotlight",
    )

  fun authStatus(context: McpRuntimeContext): Map<String, Any?> {
    if (context.environment[AUTHENTICATED_ENV].isTruthy()) {
      return developmentAuthStatus()
    }
    return logSafePayload(
      ReadianMcpStdioBridge(context).call("readian_auth_status", emptyMap()).withAuthStatusMetadata(),
    )
  }

  fun call(toolName: String, arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> = when {
    context.environment[AUTHENTICATED_ENV].isTruthy() -> developmentCall(toolName, arguments)
    toolName in delegatedTools -> logSafePayload(
      ReadianMcpStdioBridge(context).call(toolName, arguments.toReadianMcpArguments(toolName)),
    )
    else -> {
      val status = authStatus(context)
      if (status["status"] == "ok") {
        developmentCall(toolName, arguments)
      } else {
        authRequired(toolName, arguments)
      }
    }
  }

  internal fun logSafePayload(payload: Map<String, Any?>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return ReadianSecretRedactor.redact(payload) as Map<String, Any?>
  }

  private fun developmentAuthStatus(): Map<String, Any?> = logSafePayload(
    linkedMapOf(
      "status" to "ok",
      "authenticated" to true,
      "auth_required" to false,
      "boundary" to "readian_mcp",
      "credential_handling" to
        "Readian credentials, refresh tokens, session cookies, and auth headers stay below the MCP boundary.",
    ),
  )

  private fun developmentCall(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> {
    val data =
      when (toolName) {
        "readian_get_spotlight" -> linkedMapOf(
          "feed_source" to "spotlight",
          "articles" to emptyList<Map<String, Any?>>(),
          "note" to
            "Readian MCP boundary is authenticated; Spotlight adapter returned no fixture data in this runtime.",
        )
        "readian_get_articles_for_topic_query" -> linkedMapOf(
          "feed_source" to "topic_query",
          "topic_query" to arguments["topic_query"],
          "subscribed_only" to (arguments["subscribed_only"] ?: true),
          "articles" to emptyList<Map<String, Any?>>(),
          "note" to
            "Readian MCP boundary is authenticated; subscribed topic-query adapter returned no fixture data " +
            "in this runtime.",
        )
        "readian_get_article" -> linkedMapOf(
          "article_id" to arguments["article_id"],
          "article" to null,
          "note" to "Readian MCP boundary is authenticated; article adapter returned no fixture data in this runtime.",
        )
        "readian_save_candidate" -> linkedMapOf(
          "candidate_id" to arguments["candidate_id"],
          "saved" to false,
          "note" to
            "Readian MCP boundary accepted the request; write adapter is not configured in this runtime.",
        )
        "readian_mark_story_status" -> linkedMapOf(
          "story_id" to arguments["story_id"],
          "status_value" to arguments["status"],
          "updated" to false,
          "note" to
            "Readian MCP boundary accepted the request; status adapter is not configured in this runtime.",
        )
        else -> error("Unknown Readian MCP tool '$toolName'.")
      }
    return logSafePayload(okPayload(toolName, arguments, data))
  }

  private fun authRequired(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> = logSafePayload(
    linkedMapOf(
      "status" to "auth_required",
      "auth_required" to true,
      "tool" to toolName,
      "error" to linkedMapOf(
        "code" to "auth_required",
        "message" to "Readian authentication is required inside the Readian MCP boundary before this tool can run.",
      ),
      "next_action" to "Authenticate Readian through the MCP server, then retry the workflow.",
      "log_safe_arguments" to arguments,
    ),
  )

  private fun okPayload(toolName: String, arguments: Map<String, Any?>, data: Map<String, Any?>): Map<String, Any?> =
    linkedMapOf(
      "status" to "ok",
      "auth_required" to false,
      "tool" to toolName,
      "data" to data,
      "log_safe_arguments" to arguments,
    )

  private fun Map<String, Any?>.toReadianMcpArguments(toolName: String): Map<String, Any?> {
    val mapped = toMutableMap()
    if (toolName == "readian_get_articles_for_topic_query" && mapped["query"] == null) {
      mapped["query"] = mapped["topic_query"]
      mapped.remove("topic_query")
    }
    if (toolName == "readian_get_spotlight" && mapped["client_date"] == null) {
      mapped["client_date"] = mapped["date"]
      mapped.remove("date")
    }
    return mapped
  }

  private fun Map<String, Any?>.withAuthStatusMetadata(): Map<String, Any?> {
    val payload = toMutableMap()
    val authenticated = payload["authenticated"] == true || payload["status"] == "ok"
    payload.putIfAbsent("authenticated", authenticated)
    payload.putIfAbsent("auth_required", !authenticated)
    payload["boundary"] = "readian_mcp"
    payload["credential_handling"] =
      "Readian credentials, refresh tokens, session cookies, and auth headers stay below the MCP boundary."
    return payload
  }

  private fun String?.isTruthy(): Boolean = this?.lowercase() in setOf("1", "true", "yes", "authenticated")

  private class ReadianMcpStdioBridge(private val context: McpRuntimeContext) {
    fun call(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> {
      val process =
        try {
          ProcessBuilder(command(), "stdio")
            .redirectErrorStream(true)
            .apply { environment().putAll(context.environment) }
            .start()
        } catch (error: IOException) {
          return unavailablePayload(error)
        }

      val response = CompletableFuture.supplyAsync {
        parseToolPayload(process.inputStream.bufferedReader().lineSequence())
      }
      val writer = process.outputStream.bufferedWriter()
      return try {
        requestMessages(toolName, arguments).forEach { message ->
          writer.appendLine(JsonSupport.mapToJsonString(message))
        }
        writer.flush()
        response.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          ?: malformedPayload(process.exitValueOrNull())
      } catch (_: TimeoutException) {
        timeoutPayload()
      } finally {
        writer.close()
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
          process.destroyForcibly()
        }
      }
    }

    private fun command(): String = context.environment[COMMAND_ENV]?.takeIf(String::isNotBlank)
      ?: context.environment[LEGACY_COMMAND_ENV]?.takeIf(String::isNotBlank)
      ?: resolveNvmCommand(context.userHome)
      ?: "readian-mcp"

    private fun requestMessages(toolName: String, arguments: Map<String, Any?>): List<Map<String, Any?>> = listOf(
      linkedMapOf(
        "jsonrpc" to "2.0",
        "id" to 1,
        "method" to "initialize",
        "params" to linkedMapOf(
          "protocolVersion" to "2024-11-05",
          "capabilities" to emptyMap<String, Any?>(),
          "clientInfo" to linkedMapOf("name" to "skill-bill-readian-bridge", "version" to "0"),
        ),
      ),
      linkedMapOf(
        "jsonrpc" to "2.0",
        "method" to "notifications/initialized",
        "params" to emptyMap<String, Any?>(),
      ),
      linkedMapOf(
        "jsonrpc" to "2.0",
        "id" to 2,
        "method" to "tools/call",
        "params" to linkedMapOf(
          "name" to toolName,
          "arguments" to arguments,
        ),
      ),
    )

    private fun parseToolPayload(lines: Sequence<String>): Map<String, Any?>? = lines
      .mapNotNull(JsonSupport::parseObjectOrNull)
      .firstOrNull { response -> response["id"]?.let(JsonSupport::jsonElementToValue) == 2 }
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.let { response -> JsonSupport.anyToStringAnyMap(response["result"]) }
      ?.let(::extractPayload)

    private fun extractPayload(result: Map<String, Any?>): Map<String, Any?>? =
      JsonSupport.anyToStringAnyMap(result["structuredContent"])
        ?: result.textContentPayload()

    private fun Map<String, Any?>.textContentPayload(): Map<String, Any?>? = (this["content"] as? List<*>)
      ?.asSequence()
      ?.mapNotNull(JsonSupport::anyToStringAnyMap)
      ?.mapNotNull { item -> (item["text"] as? String)?.let(JsonSupport::parseObjectOrNull) }
      ?.map(JsonSupport::jsonElementToValue)
      ?.mapNotNull(JsonSupport::anyToStringAnyMap)
      ?.firstOrNull()

    private fun unavailablePayload(error: IOException): Map<String, Any?> = linkedMapOf(
      "status" to "error",
      "error_type" to "readian_mcp_unavailable",
      "message" to "Skill Bill could not launch the standalone Readian MCP client.",
      "detail" to error.message.orEmpty(),
      "next_action" to
        "Install @readian/mcp-client or set $COMMAND_ENV to the absolute readian-mcp executable path.",
    )

    private fun timeoutPayload(): Map<String, Any?> = linkedMapOf(
      "status" to "error",
      "error_type" to "readian_mcp_timeout",
      "message" to "The standalone Readian MCP client did not return before the Skill Bill timeout.",
    )

    private fun malformedPayload(exitCode: Int?): Map<String, Any?> = linkedMapOf(
      "status" to "error",
      "error_type" to "readian_mcp_malformed_response",
      "message" to "The standalone Readian MCP client returned no parseable tool payload.",
      "exit_code" to exitCode,
    )

    private fun resolveNvmCommand(userHome: Path): String? {
      val versions = userHome.resolve(".nvm").resolve("versions").resolve("node")
      if (!Files.isDirectory(versions)) return null
      return Files.list(versions).use { paths ->
        paths
          .map { path -> path.resolve("bin").resolve("readian-mcp") }
          .filter(Files::isRegularFile)
          .max(Comparator.comparing { path -> path.fileName.toString() })
          .map(Path::toString)
          .orElse(null)
      }
    }
  }
}

private fun Process.exitValueOrNull(): Int? = try {
  exitValue()
} catch (_: IllegalThreadStateException) {
  null
}
