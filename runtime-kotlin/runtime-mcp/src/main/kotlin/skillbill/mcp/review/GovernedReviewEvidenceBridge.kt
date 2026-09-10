package skillbill.mcp.review

import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonSupport
import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.readReviewEvidenceFrame
import skillbill.ports.review.model.validReviewEvidenceRequestId
import java.nio.file.Path

private const val ERROR_MESSAGE_CHARACTERS = 512
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INTERNAL_ERROR = -32603

object GovernedReviewEvidenceBridge {
  fun enabled(environment: Map<String, String>): Boolean =
    !environment[GovernedReviewEvidenceCodec.SOCKET_ENV].isNullOrBlank()

  fun run(environment: Map<String, String>) {
    val socketPath = environment[GovernedReviewEvidenceCodec.SOCKET_ENV].orEmpty()
    val token = environment[GovernedReviewEvidenceCodec.TOKEN_ENV].orEmpty()
    connect(Path.of(socketPath), token).use { connection ->
      val input = System.`in`.bufferedReader()
      generateSequence {
        try {
          input.readReviewEvidenceFrame()
        } catch (error: GovernedReviewEvidenceTransportError) {
          recordRefusal(connection::forward)
          throw error
        }
      }.forEach { line ->
        exchange(line, connection::forward) { response ->
          System.out.println(response)
          System.out.flush()
          if (System.out.checkError()) throw GovernedReviewEvidenceTransportError("Worker response write failed.")
        }
      }
    }
  }

  internal fun exchange(line: String, forward: (String) -> String?, write: (String) -> Unit) {
    val response = handleLine(line, forward) ?: return
    write(response)
    deliveryReceipt(response)?.let { receipt ->
      val confirmation = forward(
        JsonSupport.mapToJsonString(
          linkedMapOf(
            "jsonrpc" to "2.0",
            "id" to "delivery",
            "method" to "evidence/delivered",
            "params" to mapOf("receipt" to receipt),
          ),
        ),
      ) ?: throw GovernedReviewEvidenceTransportError("Delivery confirmation disconnected.")
      if (JsonSupport.parseObjectOrNull(confirmation)?.containsKey("error") != false) {
        throw GovernedReviewEvidenceTransportError("Delivery confirmation was refused.")
      }
    }
  }

  internal fun deliveryReceipt(response: String): String? {
    val frame = JsonSupport.parseObjectOrNull(response) ?: return null
    val result = JsonSupport.anyToStringAnyMap(frame["result"]?.let(JsonSupport::jsonElementToValue)).orEmpty()
    val content = result["content"] as? List<*> ?: return null
    val text = content.firstOrNull()?.let(JsonSupport::anyToStringAnyMap)?.get("text") as? String ?: return null
    return JsonSupport.parseObjectOrNull(text)?.get("delivery_receipt")
      ?.let(JsonSupport::jsonElementToValue) as? String
  }

  fun handleLine(line: String, forward: (String) -> String?): String? {
    if (line.toByteArray(Charsets.UTF_8).size > GovernedReviewEvidenceCodec.REQUEST_BYTES) {
      recordRefusal(forward)
      return errorResponse(null, JSON_RPC_INTERNAL_ERROR, "Governed evidence frame exceeds its byte limit.")
    }
    val message = JsonSupport.parseObjectOrNull(line)
    if (message == null) {
      recordRefusal(forward)
      return errorResponse(null, JSON_RPC_INTERNAL_ERROR, "Parse error")
    }
    val id = message["id"]?.let(JsonSupport::jsonElementToValue)
    if (!validReviewEvidenceRequestId(id)) {
      recordRefusal(forward)
      return errorResponse(null, JSON_RPC_INTERNAL_ERROR, "Invalid request id.")
    }
    val method = message["method"]?.let(JsonSupport::jsonElementToValue)?.toString().orEmpty()
    return when {
      id == null -> null
      method == "initialize" -> successResponse(id, initializeResult())
      method == "tools/list" -> successResponse(id, mapOf("tools" to GovernedReviewEvidenceCodec.TOOL_SPECS))
      method == "tools/call" -> forwardToolCall(id, message.toolName(), line, forward)
      else -> {
        recordRefusal(forward)
        errorResponse(id, JSON_RPC_METHOD_NOT_FOUND, "Method not found: $method")
      }
    }
  }

  private fun recordRefusal(forward: (String) -> String?) {
    val response = forward(
      JsonSupport.mapToJsonString(
        mapOf(
          "jsonrpc" to "2.0",
          "id" to "refusal",
          "method" to "evidence/refused",
        ),
      ),
    ) ?: throw GovernedReviewEvidenceTransportError("Refusal accounting disconnected.")
    if (JsonSupport.parseObjectOrNull(response)?.containsKey("error") != false) {
      throw GovernedReviewEvidenceTransportError("Refusal accounting failed.")
    }
  }

  private fun forwardToolCall(id: Any?, name: String, line: String, forward: (String) -> String?): String =
    if (name in GovernedReviewEvidenceCodec.OPERATIONS) {
      forward(line) ?: errorResponse(id, JSON_RPC_INTERNAL_ERROR, "Governed review evidence endpoint closed.")
    } else {
      recordRefusal(forward)
      errorResponse(id, JSON_RPC_METHOD_NOT_FOUND, "Unknown governed operation: $name")
    }

  private fun JsonObject.toolName(): String =
    JsonSupport.anyToStringAnyMap(this["params"]?.let(JsonSupport::jsonElementToValue))
      .orEmpty()["name"]?.toString().orEmpty()

  private fun successResponse(id: Any?, result: Map<String, Any?>): String = JsonSupport.mapToJsonString(
    linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result),
  )

  private fun errorResponse(id: Any?, code: Int, message: String): String = JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "error" to linkedMapOf(
        "code" to code,
        "message" to message.take(ERROR_MESSAGE_CHARACTERS),
      ),
    ),

  )
}
