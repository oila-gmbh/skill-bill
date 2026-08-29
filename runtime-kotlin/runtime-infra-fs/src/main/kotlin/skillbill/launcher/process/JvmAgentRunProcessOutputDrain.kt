package skillbill.launcher.process

import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CoderResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal fun String.withTimeoutMessage(wait: ProcessWait, request: AgentRunProcessRequest): String = when {
  wait.progressIdleTimedOut -> withProgressTimeoutMessage(request, wait.fileActivityGraceExhausted, wait.liveness)
  wait.wallClockTimedOut -> withWallClockTimeoutMessage(request, wait.liveness)
  else -> this
}

internal fun String.withProgressTimeoutMessage(
  request: AgentRunProcessRequest,
  fileActivityGraceExhausted: Boolean,
  liveness: AgentRunLivenessSnapshot?,
): String {
  val fileActivityDetail = if (fileActivityGraceExhausted) {
    " File activity was observed, but the ${request.fileActivityGraceTimeout} file-activity grace window was exhausted."
  } else {
    " No file activity was observed."
  }
  val livenessDetail = liveness.detailsSuffix()
  val message = "Agent run stopped after ${request.progressIdleTimeout} " +
    "without durable workflow progress.$fileActivityDetail$livenessDetail"
  return if (isBlank()) message else "$this\n$message"
}

internal fun String.withWallClockTimeoutMessage(
  request: AgentRunProcessRequest,
  liveness: AgentRunLivenessSnapshot?,
): String {
  val message = "Agent run stopped after optional wall-clock cap ${request.timeout}.${liveness.detailsSuffix()}"
  return if (isBlank()) message else "$this\n$message"
}

internal fun AgentRunLivenessSnapshot?.detailsSuffix(): String = this?.let { snapshot ->
  val detail = listOfNotNull(
    snapshot.workflowId?.let { workflowId -> "workflow_id=$workflowId" },
    snapshot.workflowStep?.let { workflowStep -> "step=$workflowStep" },
    snapshot.lastDurableProgressAt?.let { timestamp -> "last_durable_progress_at=$timestamp" },
    snapshot.lastFileActivityAt?.let { timestamp -> "last_file_activity_at=$timestamp" },
    snapshot.lastOutputAt?.let { timestamp -> "last_output_at=$timestamp" },
  ).joinToString(", ")
  if (detail.isBlank()) "" else " Last observations: $detail."
} ?: ""

internal sealed interface ProcessStart {
  data class Started(val process: Process) : ProcessStart
  data class Failed(val error: Exception) : ProcessStart
}

internal class CappedUtf8Drain(
  private val input: InputStream,
  private val limitBytes: Int?,
  private val outputStream: AgentRunOutputStream,
  private val outputSink: AgentRunOutputSink,
  private val onChunkRead: (String) -> Unit,
) {
  private val output = ByteArrayOutputStream(
    limitBytes?.coerceAtMost(INITIAL_OUTPUT_BUFFER_BYTES) ?: INITIAL_OUTPUT_BUFFER_BYTES,
  )

  @Volatile private var truncated = false
  private var totalByteSize = 0L
  private val digest = MessageDigest.getInstance("SHA-256")
  private val worker = thread(start = false, isDaemon = true, name = "skillbill-agent-run-output-drain") {
    try {
      input.use { stream ->
        val buffer = ByteArray(DEFAULT_DRAIN_BUFFER_BYTES)
        var remaining = limitBytes
        val decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val carry = ByteBuffer.allocate(DEFAULT_DRAIN_BUFFER_BYTES + UTF8_MAX_BYTES_PER_CODE_POINT)
        val decoded = CharBuffer.allocate(DEFAULT_DRAIN_BUFFER_BYTES)
        while (true) {
          val read = stream.read(buffer)
          if (read == -1) {
            break
          }
          totalByteSize += read
          digest.update(buffer, 0, read)
          val withinCap = remaining == null || remaining > 0
          carry.put(buffer, 0, read)
          carry.flip()
          decodeAvailable(decoded, withinCap) { decoder.decode(carry, decoded, false) }
          carry.compact()

          val forwarded = remaining?.coerceAtMost(read) ?: read
          if (forwarded > 0) remaining = remaining?.minus(forwarded)
          retain(buffer, read)
        }
        val withinCap = remaining == null || remaining > 0
        carry.flip()
        decodeAvailable(decoded, withinCap) { decoder.decode(carry, decoded, true) }
        decodeAvailable(decoded, withinCap) { decoder.flush(decoded) }
      }
    } catch (_: IOException) {
    }
  }

  private fun retain(buffer: ByteArray, read: Int) {
    output.write(buffer, 0, read)
    val limit = limitBytes ?: return
    if (totalByteSize > limit) truncated = true
    if (output.size() > limit * 2) compactToTail(limit)
  }

  private fun compactToTail(limit: Int) {
    val retained = output.toByteArray()
    output.reset()
    output.write(retained, retained.size - limit, limit)
  }

  private fun alignToLineStart(bytes: ByteArray): ByteArray {
    val newline = bytes.indexOf('\n'.code.toByte())
    return if (newline < 0) bytes else bytes.copyOfRange(newline + 1, bytes.size)
  }

  private fun decodeAvailable(decoded: CharBuffer, forwardToSink: Boolean, decode: () -> CoderResult) {
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

  fun start() {
    worker.start()
  }

  fun join() {
    worker.join(DRAIN_JOIN_TIMEOUT_MILLIS)
  }

  fun text(): String = String(bytes(), StandardCharsets.UTF_8)

  fun bytes(): ByteArray {
    val limit = limitBytes ?: return output.toByteArray()
    val retained = output.toByteArray()
    if (retained.size <= limit) return retained
    return alignToLineStart(retained.copyOfRange(retained.size - limit, retained.size))
  }

  fun wasTruncated(): Boolean = truncated

  fun totalByteSize(): Long = totalByteSize

  fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

internal class OutputObservationTracker {
  private val lastObservedMillis = AtomicLong(0L)

  fun markObserved() {
    lastObservedMillis.set(System.currentTimeMillis())
  }

  fun lastObservedAt(): Instant? = lastObservedMillis.get()
    .takeIf { millis -> millis > 0L }
    ?.let(Instant::ofEpochMilli)
}

internal fun parseWorkflowIdAndStep(label: String?): Pair<String?, String?> {
  val text = label?.takeIf(String::isNotBlank) ?: return null to null
  val workflow = Regex("""workflow\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  val step = Regex("""step\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  return workflow to step
}

internal fun Instant.toIsoUtc(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  .format(atOffset(ZoneOffset.UTC))

internal const val DEFAULT_DRAIN_BUFFER_BYTES = 8192
internal const val UTF8_MAX_BYTES_PER_CODE_POINT = 4
internal const val INITIAL_OUTPUT_BUFFER_BYTES = DEFAULT_DRAIN_BUFFER_BYTES
internal const val DRAIN_JOIN_TIMEOUT_MILLIS = 1_000L
internal const val MIN_TIMEOUT_MILLIS = 1L
internal const val MIN_TIMEOUT_NANOS = 1L
internal const val PROGRESS_POLL_INTERVAL_MILLIS = 250L
internal const val DESTROY_WAIT_TIMEOUT_MILLIS = 1_000L
