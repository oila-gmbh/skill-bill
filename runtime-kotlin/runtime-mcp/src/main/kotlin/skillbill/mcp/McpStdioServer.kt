package skillbill.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonSupport

private const val JSON_RPC_PARSE_ERROR = -32700
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602

object McpStdioServer {
  fun run(context: McpRuntimeContext = McpRuntimeContext()) {
    generateSequence(::readlnOrNull).forEach { line ->
      handleLine(line, context)?.let(::println)
    }
  }

  fun handleLine(line: String, context: McpRuntimeContext = McpRuntimeContext()): String? {
    val message = JsonSupport.parseObjectOrNull(line)
    val id = message?.get("id")
    val method = message?.get("method")?.let(JsonSupport::jsonElementToValue)?.toString().orEmpty()
    return when {
      message == null -> errorResponse(null, JSON_RPC_PARSE_ERROR, "Parse error")
      id == null -> null
      method == "initialize" -> successResponse(id, initializeResult())
      method == "tools/list" -> successResponse(id, toolsListResult())
      method == "tools/call" -> callToolResponse(id, message.arguments(), context)
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

  private fun callToolResponse(id: JsonElement, params: Map<String, Any?>, context: McpRuntimeContext): String =
    validateStrictArguments(params)?.let { error ->
      errorResponse(id, JSON_RPC_INVALID_PARAMS, error)
    } ?: successResponse(id, callToolResult(params, context))

  private fun callToolResult(params: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> {
    val toolName = params["name"]?.toString().orEmpty()
    val arguments = JsonSupport.anyToStringAnyMap(params["arguments"]).orEmpty()
    return try {
      val payload = McpToolDispatcher.call(toolName, arguments, context)
      mcpToolResult(payload, isError = false)
    } catch (error: IllegalArgumentException) {
      mcpToolErrorResult(toolName, error)
    } catch (error: IllegalStateException) {
      mcpToolErrorResult(toolName, error)
    }
  }

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

private fun mcpToolErrorResult(toolName: String, error: RuntimeException): Map<String, Any?> = mcpToolResult(
  mapOf("status" to "error", "tool" to toolName, "error" to error.message.orEmpty()),
  isError = true,
)

private fun mcpToolResult(payload: Map<String, Any?>, isError: Boolean): Map<String, Any?> = linkedMapOf(
  "content" to listOf(
    mapOf(
      "type" to "text",
      "text" to JsonSupport.mapToJsonString(payload),
    ),
  ),
  "isError" to isError,
)

private fun validateStrictArguments(params: Map<String, Any?>): String? {
  val toolName = params["name"]?.toString().orEmpty()
  val arguments = JsonSupport.anyToStringAnyMap(params["arguments"]).orEmpty()
  val schema = McpToolRegistry.toolNamed(toolName)?.inputSchema
  val unknownArguments = schema?.let { unknownProperties(arguments, it, path = "") }.orEmpty()
  return unknownArguments.takeIf { it.isNotEmpty() }?.let {
    "Unknown argument(s) for $toolName: ${it.joinToString(", ")}"
  }
}

private fun unknownProperties(value: Any?, schema: Map<String, Any?>, path: String): List<String> {
  val objectValue = JsonSupport.anyToStringAnyMap(value)
  val arrayValue = value as? List<*>
  return when {
    objectValue != null -> unknownObjectProperties(objectValue, schema, path)
    arrayValue != null -> unknownArrayProperties(arrayValue, schema, path)
    else -> emptyList()
  }
}

private fun unknownObjectProperties(value: Map<String, Any?>, schema: Map<String, Any?>, path: String): List<String> {
  val properties = JsonSupport.anyToStringAnyMap(schema["properties"]).orEmpty()
  val localUnknown = if (schema["additionalProperties"] == false) {
    value.keys.filterNot(properties::containsKey).sorted().map { propertyName ->
      if (path.isBlank()) propertyName else "$path.$propertyName"
    }
  } else {
    emptyList()
  }
  val nestedUnknown = value.flatMap { (propertyName, propertyValue) ->
    JsonSupport.anyToStringAnyMap(properties[propertyName])?.let { propertySchema ->
      unknownProperties(propertyValue, propertySchema, nestedPath(path, propertyName))
    }.orEmpty()
  }
  return localUnknown + nestedUnknown
}

private fun unknownArrayProperties(value: List<*>, schema: Map<String, Any?>, path: String): List<String> {
  val itemSchema = JsonSupport.anyToStringAnyMap(schema["items"]) ?: return emptyList()
  return value.flatMapIndexed { index, item ->
    unknownProperties(item, itemSchema, "$path[$index]")
  }
}

private fun nestedPath(parent: String, child: String): String = if (parent.isBlank()) child else "$parent.$child"
