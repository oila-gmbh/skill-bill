package skillbill.mcp.review

import kotlinx.serialization.json.JsonObject
import skillbill.SkillBillVersion
import skillbill.contracts.JsonSupport
import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INTERNAL_ERROR = -32603

/**
 * Pipe between a governed review worker's stdio MCP client and its parent evidence endpoint. It
 * holds no broker reference, reads no file, and makes no policy decision: the only frames it
 * forwards are calls to the two governed operations.
 */
object GovernedReviewEvidenceBridge {
  fun enabled(environment: Map<String, String>): Boolean =
    !environment[GovernedReviewEvidenceCodec.SOCKET_ENV].isNullOrBlank()

  fun run(environment: Map<String, String> = System.getenv()) {
    val socketPath = environment[GovernedReviewEvidenceCodec.SOCKET_ENV].orEmpty()
    val token = environment[GovernedReviewEvidenceCodec.TOKEN_ENV].orEmpty()
    connect(Path.of(socketPath), token).use { connection ->
      generateSequence(::readlnOrNull).forEach { line ->
        handleLine(line) { frame -> connection.forward(frame) }?.let(::println)
      }
    }
  }

  fun handleLine(line: String, forward: (String) -> String?): String? {
    val message = JsonSupport.parseObjectOrNull(line)
    val id = message?.get("id")?.let(JsonSupport::jsonElementToValue)
    val method = message?.get("method")?.let(JsonSupport::jsonElementToValue)?.toString().orEmpty()
    return when {
      message == null -> errorResponse(null, JSON_RPC_INTERNAL_ERROR, "Parse error")
      id == null -> null
      method == "initialize" -> successResponse(id, initializeResult())
      method == "tools/list" -> successResponse(id, mapOf("tools" to GovernedReviewEvidenceCodec.TOOL_SPECS))
      method == "tools/call" -> forwardToolCall(id, message.toolName(), line, forward)
      else -> errorResponse(id, JSON_RPC_METHOD_NOT_FOUND, "Method not found: $method")
    }
  }

  private fun forwardToolCall(id: Any?, name: String, line: String, forward: (String) -> String?): String =
    if (name in GovernedReviewEvidenceCodec.OPERATIONS) {
      forward(line) ?: errorResponse(id, JSON_RPC_INTERNAL_ERROR, "Governed review evidence endpoint closed.")
    } else {
      errorResponse(id, JSON_RPC_METHOD_NOT_FOUND, "Unknown governed operation: $name")
    }

  private fun JsonObject.toolName(): String =
    JsonSupport.anyToStringAnyMap(this["params"]?.let(JsonSupport::jsonElementToValue))
      .orEmpty()["name"]?.toString().orEmpty()

  private class Connection(
    private val channel: SocketChannel,
    val reader: BufferedReader,
    val writer: BufferedWriter,
  ) : AutoCloseable {
    fun forward(frame: String): String? {
      writer.appendLine(frame)
      writer.flush()
      return reader.readLine()
    }

    override fun close() {
      channel.close()
    }
  }

  private fun connect(socketPath: Path, token: String): Connection {
    val connection = openSocketChannel(socketPath)
    val writer = Channels.newOutputStream(connection).bufferedWriter()
    val reader = Channels.newInputStream(connection).bufferedReader()
    writer.appendLine(
      JsonSupport.mapToJsonString(
        linkedMapOf("jsonrpc" to "2.0", "method" to "handshake", "params" to mapOf("token" to token)),
      ),
    )
    writer.flush()
    reader.readLine()
      ?: throw GovernedReviewEvidenceTransportError("Governed review evidence endpoint refused this launch's token.")
    return Connection(connection, reader, writer)
  }

  private fun openSocketChannel(socketPath: Path): SocketChannel = try {
    SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
  } catch (error: IOException) {
    throw GovernedReviewEvidenceTransportError(
      "Governed review evidence endpoint at '$socketPath' is unreachable.",
      error,
    )
  } catch (error: UnsupportedOperationException) {
    throw GovernedReviewEvidenceTransportError(
      "This platform cannot reach the governed review evidence endpoint at '$socketPath'.",
      error,
    )
  }

  private fun initializeResult(): Map<String, Any?> = linkedMapOf(
    "protocolVersion" to "2025-11-25",
    "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
    "serverInfo" to mapOf(
      "name" to GovernedReviewEvidenceCodec.SERVER_NAME,
      "version" to SkillBillVersion.VALUE,
    ),
  )

  private fun successResponse(id: Any?, result: Map<String, Any?>): String = JsonSupport.mapToJsonString(
    linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result),
  )

  private fun errorResponse(id: Any?, code: Int, message: String): String = JsonSupport.mapToJsonString(
    linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to linkedMapOf("code" to code, "message" to message)),
  )
}
