package skillbill.mcp.review

import skillbill.contracts.JsonSupport
import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.readReviewEvidenceFrame
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

internal class GovernedReviewEvidenceConnection(
  private val channel: SocketChannel,
  val reader: BufferedReader,
  val writer: BufferedWriter,
) : AutoCloseable {
  fun forward(frame: String): String? {
    writer.appendLine(frame)
    writer.flush()
    return reader.readReviewEvidenceFrame(GovernedReviewEvidenceCodec.RESPONSE_FRAME_BYTES)
  }

  override fun close() {
    channel.close()
  }
}

internal fun connect(socketPath: Path, token: String): GovernedReviewEvidenceConnection {
  val connection = openSocketChannel(socketPath)
  val writer = Channels.newOutputStream(connection).bufferedWriter()
  val reader = Channels.newInputStream(connection).bufferedReader()
  writer.appendLine(
    JsonSupport.mapToJsonString(
      linkedMapOf("jsonrpc" to "2.0", "method" to "handshake", "params" to mapOf("token" to token)),
    ),
  )
  writer.flush()
  reader.readReviewEvidenceFrame()
    ?: throw GovernedReviewEvidenceTransportError("Governed review evidence endpoint refused this launch's token.")
  return GovernedReviewEvidenceConnection(connection, reader, writer)
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
