package skillbill.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonSupport

private const val JSON_RPC_PARSE_ERROR = -32700
private const val JSON_RPC_METHOD_NOT_FOUND = -32601

object McpStdioServer {
  fun run() {
    generateSequence(::readlnOrNull).forEach { line ->
      handleLine(line)?.let(::println)
    }
  }

  fun handleLine(line: String): String? {
    val message = JsonSupport.parseObjectOrNull(line)
    val id = message?.get("id")
    val method = message?.get("method")?.let(JsonSupport::jsonElementToValue)?.toString().orEmpty()
    return when {
      message == null -> errorResponse(null, JSON_RPC_PARSE_ERROR, "Parse error")
      id == null -> null
      method == "initialize" -> successResponse(id, initializeResult())
      method == "tools/list" -> successResponse(id, toolsListResult())
      method == "tools/call" -> successResponse(id, callToolResult(message.arguments()))
      else -> errorResponse(id, JSON_RPC_METHOD_NOT_FOUND, "Method not found: $method")
    }
  }

  private fun initializeResult(): Map<String, Any?> = linkedMapOf(
    "protocolVersion" to "2025-11-25",
    "capabilities" to mapOf(
      "tools" to mapOf("listChanged" to false),
    ),
    "serverInfo" to mapOf(
      "name" to "skill-bill",
      "version" to "0.1.0",
    ),
  )

  private fun toolsListResult(): Map<String, Any?> = mapOf(
    "tools" to McpToolRegistry.tools.map(McpToolSpec::toPayload),
  )

  private fun callToolResult(params: Map<String, Any?>): Map<String, Any?> {
    val toolName = params["name"]?.toString().orEmpty()
    val arguments = JsonSupport.anyToStringAnyMap(params["arguments"]).orEmpty()
    return try {
      val payload = McpToolDispatcher.call(toolName, arguments)
      toolResult(payload, isError = false)
    } catch (error: IllegalArgumentException) {
      toolErrorResult(toolName, error)
    } catch (error: IllegalStateException) {
      toolErrorResult(toolName, error)
    }
  }

  private fun toolErrorResult(toolName: String, error: RuntimeException): Map<String, Any?> = toolResult(
    mapOf("status" to "error", "tool" to toolName, "error" to error.message.orEmpty()),
    isError = true,
  )

  private fun toolResult(payload: Map<String, Any?>, isError: Boolean): Map<String, Any?> = linkedMapOf(
    "content" to listOf(
      mapOf(
        "type" to "text",
        "text" to JsonSupport.mapToJsonString(payload),
      ),
    ),
    "isError" to isError,
  )

  private fun successResponse(id: JsonElement, result: Map<String, Any?>): String = JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "result" to result,
    ),
  )

  private fun errorResponse(id: JsonElement?, code: Int, message: String): String = JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "error" to mapOf("code" to code, "message" to message),
    ),
  )

  private fun JsonObject.arguments(): Map<String, Any?> =
    JsonSupport.anyToStringAnyMap(this["params"]?.let(JsonSupport::jsonElementToValue)).orEmpty()
}
