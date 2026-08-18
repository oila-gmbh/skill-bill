package skillbill.launcher.review

import skillbill.contracts.JsonSupport
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovernedReviewEvidenceEndpointTest {
  private class RecordingProtocol : NativeReviewOperationProtocol {
    val reads = mutableListOf<ReviewEvidenceBatchRequest>()

    override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
      error("unused")

    override fun read(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult {
      reads += request
      return ReviewEvidenceBatchResult(
        results = listOf(
          ReviewEvidenceResult(
            content = "package secrets",
            bytes = 15,
            cumulativeBytes = 0,
            expansionCount = 0,
            forbidden = ForbiddenReviewOperation("unreachable_path", request.requests.single().path, "not assigned"),
          ),
        ),
        cumulativeBytes = 0,
        expansions = emptyList(),
      )
    }

    override fun tool(call: ReviewToolCall): ReviewToolCallResult = error("unused")
    override fun modelTurn(): ReviewBudgetOutcome? = null
    override fun laneResultChunk(chunk: String): ReviewBudgetOutcome? = null
    override fun providerUsage(usage: ProviderTokenUsage): ReviewBudgetOutcome? = null
  }

  @Test
  fun `an out-of-surface read is refused with no content and still reaches the broker`() {
    val protocol = RecordingProtocol()
    GovernedReviewEvidenceEndpoint.bind("architecture", protocol, listOf("/bin/true")).use { endpoint ->
      connect(endpoint, endpoint.descriptor.token).use { connection ->
        val reply = requireNotNull(connection.call(readFrame("src/Elsewhere.kt")))
        val payload = toolPayload(reply)

        @Suppress("UNCHECKED_CAST")
        val result = (payload["results"] as List<Map<String, Any?>>).single()
        assertEquals(true, result["refused"])
        assertFalse(result.containsKey("content"))
      }
      assertEquals(listOf("src/Elsewhere.kt"), protocol.reads.map { it.requests.single().path })
    }
  }

  @Test
  fun `a connection presenting a foreign token is served nothing`() {
    val protocol = RecordingProtocol()
    GovernedReviewEvidenceEndpoint.bind("architecture", protocol, listOf("/bin/true")).use { endpoint ->
      Client(SocketChannel.open(UnixDomainSocketAddress.of(endpoint.descriptor.socketPath))).use { connection ->
        assertNull(connection.handshake("not-this-launch"))
      }
      assertTrue(protocol.reads.isEmpty())
    }
  }

  @Test
  fun `closing the endpoint removes the per-launch socket and config`() {
    val endpoint = GovernedReviewEvidenceEndpoint.bind("architecture", RecordingProtocol(), listOf("/bin/true"))
    assertTrue(Files.exists(endpoint.descriptor.socketPath))
    assertTrue(Files.exists(endpoint.descriptor.mcpConfigPath))

    endpoint.close()

    assertFalse(Files.exists(endpoint.descriptor.socketPath))
    assertFalse(Files.exists(endpoint.descriptor.mcpConfigPath))
  }

  private class Client(private val channel: SocketChannel) : AutoCloseable {
    private val reader = Channels.newInputStream(channel).bufferedReader()
    private val writer = Channels.newOutputStream(channel).bufferedWriter()

    fun handshake(token: String): String? {
      send(
        JsonSupport.mapToJsonString(
          linkedMapOf("jsonrpc" to "2.0", "method" to "handshake", "params" to mapOf("token" to token)),
        ),
      )
      return reader.readLine()
    }

    fun call(frame: String): String? {
      send(frame)
      return reader.readLine()
    }

    private fun send(frame: String) {
      writer.appendLine(frame)
      writer.flush()
    }

    override fun close() {
      channel.close()
    }
  }

  private fun connect(endpoint: GovernedReviewEvidenceEndpoint, token: String): Client =
    Client(SocketChannel.open(UnixDomainSocketAddress.of(endpoint.descriptor.socketPath)))
      .also { it.handshake(token) }

  private fun readFrame(path: String): String = JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to 1,
      "method" to "tools/call",
      "params" to linkedMapOf(
        "name" to "read_evidence",
        "arguments" to mapOf("requests" to listOf(mapOf("path" to path))),
      ),
    ),
  )

  private fun toolPayload(reply: String): Map<String, Any?> {
    val message = requireNotNull(JsonSupport.parseObjectOrNull(reply))
    val result = JsonSupport.anyToStringAnyMap(message["result"]?.let(JsonSupport::jsonElementToValue)).orEmpty()

    @Suppress("UNCHECKED_CAST")
    val content = (result["content"] as List<Map<String, Any?>>).single()
    return requireNotNull(
      JsonSupport.anyToStringAnyMap(
        JsonSupport.parseObjectOrNull(content["text"].toString())?.let(JsonSupport::jsonElementToValue),
      ),
    )
  }
}
