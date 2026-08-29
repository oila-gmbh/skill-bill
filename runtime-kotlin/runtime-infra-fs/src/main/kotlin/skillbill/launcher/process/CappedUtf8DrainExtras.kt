package skillbill.launcher.process

import java.nio.CharBuffer
import java.nio.charset.CoderResult

internal fun CappedUtf8Drain.retain(buffer: ByteArray, read: Int) {
  output.write(buffer, 0, read)
  val limit = limitBytes ?: return
  if (totalByteSize > limit) truncated = true
  if (output.size() > limit * 2) compactToTail(limit)
}
internal fun CappedUtf8Drain.compactToTail(limit: Int) {
  val retained = output.toByteArray()
  output.reset()
  output.write(retained, retained.size - limit, limit)
}
internal fun CappedUtf8Drain.alignToLineStart(bytes: ByteArray): ByteArray {
  val newline = bytes.indexOf('\n'.code.toByte())
  return if (newline < 0) bytes else bytes.copyOfRange(newline + 1, bytes.size)
}
internal fun CappedUtf8Drain.decodeAvailable(decoded: CharBuffer, forwardToSink: Boolean, decode: () -> CoderResult) {
  while (true) {
    val result = decode()
    decoded.flip()
    if (decoded.hasRemaining()) {
      val chunk = decoded.toString()
      onChunkRead(chunk)
      if (forwardToSink) outputSink.write(outputStream, chunk)
    }
    decoded.clear()
    if (!result.isOverflow) return
  }
}
