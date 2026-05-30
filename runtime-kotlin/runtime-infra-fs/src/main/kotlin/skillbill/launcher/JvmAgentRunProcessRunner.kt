@file:Suppress("TooManyFunctions")

package skillbill.launcher

import me.tatarka.inject.annotations.Inject
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.time.DurationUnit

@Inject
class JvmAgentRunProcessRunner : AgentRunProcessRunner {
  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    val processStart = startProcess(request)
    if (processStart is ProcessStart.Failed) {
      return spawnFailure(processStart.error)
    }
    val process = (processStart as ProcessStart.Started).process

    val outputTracker = OutputObservationTracker()
    val stdout = CappedUtf8Drain(
      input = process.inputStream,
      limitBytes = AGENT_RUN_OUTPUT_LIMIT_BYTES,
      outputStream = AgentRunOutputStream.STDOUT,
      outputSink = request.outputSink,
      onChunkRead = { outputTracker.markObserved() },
    ).also { it.start() }
    val stderr = CappedUtf8Drain(
      input = process.errorStream,
      limitBytes = AGENT_RUN_OUTPUT_LIMIT_BYTES,
      outputStream = AgentRunOutputStream.STDERR,
      outputSink = request.outputSink,
      onChunkRead = { outputTracker.markObserved() },
    ).also { it.start() }
    writeAndCloseStdin(process, request.stdinText)
    val wait = waitForProcess(process, request, outputTracker)
    val finished = wait.finished
    if (!finished) {
      process.destroyForcibly()
      process.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }
    stdout.join()
    stderr.join()
    return AgentRunProcessResult(
      exitStatus = if (finished) process.exitValue() else null,
      stdout = stdout.text(),
      stderr = stderr.text().withTimeoutMessage(wait, request),
      timedOut = !finished,
      spawnFailed = false,
      liveness = wait.liveness,
    )
  }

  private fun waitForProcess(
    process: Process,
    request: AgentRunProcessRequest,
    outputTracker: OutputObservationTracker,
  ): ProcessWait = ProcessWaitLoop(process, request, outputTracker).wait()

  private fun spawnFailure(error: Exception): AgentRunProcessResult = AgentRunProcessResult(
    exitStatus = null,
    stdout = "",
    stderr = error.message.orEmpty(),
    timedOut = false,
    spawnFailed = true,
  )

  private fun startProcess(request: AgentRunProcessRequest): ProcessStart = try {
    ProcessStart.Started(
      ProcessBuilder(request.command)
        .directory(request.workingDirectory.toFile())
        .apply {
          if (!request.inheritEnvironment) {
            environment().clear()
          }
          environment().putAll(request.environment)
        }
        .start(),
    )
  } catch (error: IOException) {
    ProcessStart.Failed(error)
  } catch (error: SecurityException) {
    ProcessStart.Failed(error)
  }
}

private fun writeAndCloseStdin(process: Process, stdinText: String?) {
  runCatching {
    process.outputStream.use { output ->
      if (stdinText != null) {
        output.write(stdinText.toByteArray(StandardCharsets.UTF_8))
      }
    }
  }
}

private data class ProcessWait(
  val finished: Boolean,
  val progressIdleTimedOut: Boolean,
  val fileActivityGraceExhausted: Boolean,
  val wallClockTimedOut: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
)

@Suppress("TooManyFunctions")
private class ProcessWaitLoop(
  private val process: Process,
  private val request: AgentRunProcessRequest,
  private val outputTracker: OutputObservationTracker,
) {
  private val timeoutMillis = request.timeout
    ?.toLong(DurationUnit.MILLISECONDS)
    ?.coerceAtLeast(MIN_TIMEOUT_MILLIS)
  private val idleTimeoutNanos = request.progressIdleTimeout
    ?.toLong(DurationUnit.NANOSECONDS)
    ?.coerceAtLeast(MIN_TIMEOUT_NANOS)
  private val fileActivityGraceNanos = request.fileActivityGraceTimeout
    .toLong(DurationUnit.NANOSECONDS)
    .coerceAtLeast(MIN_TIMEOUT_NANOS)
  private val statusHeartbeatNanos = request.statusHeartbeatInterval
    .toLong(DurationUnit.NANOSECONDS)
    .coerceAtLeast(MIN_TIMEOUT_NANOS)
  private val startNanos = System.nanoTime()
  private var lastWorkflowProgressNanos = startNanos
  private var lastStatusHeartbeatNanos = startNanos
  private var lastProgressToken = request.progressProbe.safeProgressToken()
  private var lastActivityToken = request.activityProbe.safeActivityToken()
  private var fileActivityWindowStartNanos: Long? = null
  private var lastProgressLabel: String? = request.progressProbe.safeProgressLabel()
  private var lastProgressInstant: Instant? = null
  private var lastSnapshotInstant: Instant? = null
  private var lastActivityLabel: String? = null
  private var lastActivityInstant: Instant? = null

  fun wait(): ProcessWait {
    var wait: ProcessWait? = null
    while (wait == null) {
      wait = nextWait()
    }
    return wait
  }

  private fun nextWait(): ProcessWait? {
    val waitMillis = waitMillisBeforeNextPoll() ?: return ProcessWait(
      finished = false,
      progressIdleTimedOut = false,
      fileActivityGraceExhausted = false,
      wallClockTimedOut = true,
      liveness = liveness("watchdog", "wall_clock_timeout", "killed"),
    )
    return when {
      process.waitFor(waitMillis, TimeUnit.MILLISECONDS) ->
        ProcessWait(
          finished = true,
          progressIdleTimedOut = false,
          fileActivityGraceExhausted = false,
          wallClockTimedOut = false,
          liveness = liveness("watchdog", "process_exited", "exited"),
        )
      else -> pollProgress()
    }
  }

  private fun waitMillisBeforeNextPoll(): Long? {
    val configuredTimeoutMillis = timeoutMillis ?: return PROGRESS_POLL_INTERVAL_MILLIS
    val remainingMillis = configuredTimeoutMillis - elapsedMillis()
    return if (remainingMillis <= 0) {
      null
    } else {
      min(PROGRESS_POLL_INTERVAL_MILLIS, remainingMillis)
    }
  }

  private fun pollProgress(): ProcessWait? {
    val nowNanos = System.nanoTime()
    pollWorkflowProgress(nowNanos)
    pollFileActivity(nowNanos)
    pollStatusHeartbeat(nowNanos)
    return if (idleTimeoutNanos != null && nowNanos - lastWorkflowProgressNanos >= idleTimeoutNanos) {
      val graceActive = fileActivityWindowStartNanos?.let { windowStart ->
        nowNanos - windowStart < fileActivityGraceNanos
      } == true
      if (graceActive) {
        null
      } else {
        ProcessWait(
          finished = false,
          progressIdleTimedOut = true,
          fileActivityGraceExhausted = fileActivityWindowStartNanos != null,
          wallClockTimedOut = false,
          liveness = liveness("watchdog", "progress_idle_timeout", "killed"),
        )
      }
    } else {
      null
    }
  }

  private fun pollWorkflowProgress(nowNanos: Long) {
    val progressToken = request.progressProbe.safeProgressToken()
    if (progressToken != lastProgressToken) {
      lastProgressToken = progressToken
      lastWorkflowProgressNanos = nowNanos
      lastProgressInstant = Instant.now()
      fileActivityWindowStartNanos = null
      writeProgressLabel()
    }
  }

  private fun pollFileActivity(nowNanos: Long) {
    val activityToken = request.activityProbe.safeActivityToken()
    if (activityToken != lastActivityToken) {
      lastActivityToken = activityToken
      lastActivityInstant = Instant.now()
      if (fileActivityWindowStartNanos == null) {
        fileActivityWindowStartNanos = nowNanos
      }
      writeActivityLabel()
    }
  }

  private fun pollStatusHeartbeat(nowNanos: Long) {
    if (nowNanos - lastStatusHeartbeatNanos < statusHeartbeatNanos) {
      return
    }
    lastStatusHeartbeatNanos = nowNanos
    val workflowLabel = lastProgressLabel?.takeIf(String::isNotBlank)
    val activityLabel = lastActivityLabel?.takeIf(String::isNotBlank)
    val details = listOfNotNull(
      workflowLabel?.let { "workflow: $it" },
      activityLabel?.let { "file activity: $it" },
    ).joinToString("; ")
      .takeIf(String::isNotBlank)
      ?.let { "; $it" }
      .orEmpty()
    request.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: status heartbeat (${request.statusHeartbeatInterval}): child run still active$details\n",
    )
  }

  private fun writeProgressLabel() {
    request.progressProbe.safeProgressLabel()
      ?.takeIf(String::isNotBlank)
      ?.let { label ->
        lastProgressLabel = label
        lastSnapshotInstant = Instant.now()
        request.outputSink.write(AgentRunOutputStream.STDERR, "skill-bill: workflow progress: $label\n")
      }
  }

  private fun writeActivityLabel() {
    val activityLabel = request.activityProbe.safeActivityLabel()?.takeIf(String::isNotBlank)
    if (activityLabel != null) {
      lastActivityLabel = activityLabel
    }
    val workflowLabel = request.progressProbe.safeProgressLabel()?.takeIf(String::isNotBlank)
    val suffix = listOfNotNull(activityLabel, workflowLabel).joinToString("; ")
      .takeIf(String::isNotBlank)
      ?.let { label -> ": $label" }
      .orEmpty()
    request.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: file activity observed; durable workflow progress is still pending$suffix\n",
    )
  }

  private fun liveness(phase: String, reason: String, processState: String): AgentRunLivenessSnapshot {
    val (workflowId, workflowStep) = parseWorkflowIdAndStep(lastProgressLabel)
    return AgentRunLivenessSnapshot(
      phase = phase,
      reason = reason,
      processState = processState,
      workflowId = workflowId,
      workflowStep = workflowStep,
      lastDurableProgressAt = lastProgressInstant?.toIsoUtc(),
      lastDurableProgressLabel = lastProgressLabel?.takeIf(String::isNotBlank),
      lastWorkflowSnapshotAt = lastSnapshotInstant?.toIsoUtc(),
      lastFileActivityAt = lastActivityInstant?.toIsoUtc(),
      lastFileActivityLabel = lastActivityLabel?.takeIf(String::isNotBlank),
      lastOutputAt = outputTracker.lastObservedAt()?.toIsoUtc(),
    )
  }

  private fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
}

private fun skillbill.ports.agentrun.model.AgentRunProgressProbe.safeProgressToken(): String? =
  runCatching { progressToken() }.getOrNull()

private fun skillbill.ports.agentrun.model.AgentRunProgressProbe.safeProgressLabel(): String? =
  runCatching { progressLabel() }.getOrNull()

private fun AgentRunActivityProbe.safeActivityToken(): String? = runCatching { activityToken() }.getOrNull()

private fun AgentRunActivityProbe.safeActivityLabel(): String? = runCatching { activityLabel() }.getOrNull()

private fun String.withTimeoutMessage(wait: ProcessWait, request: AgentRunProcessRequest): String = when {
  wait.progressIdleTimedOut -> withProgressTimeoutMessage(request, wait.fileActivityGraceExhausted, wait.liveness)
  wait.wallClockTimedOut -> withWallClockTimeoutMessage(request, wait.liveness)
  else -> this
}

private fun String.withProgressTimeoutMessage(
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

private fun String.withWallClockTimeoutMessage(
  request: AgentRunProcessRequest,
  liveness: AgentRunLivenessSnapshot?,
): String {
  val message = "Agent run stopped after optional wall-clock cap ${request.timeout}.${liveness.detailsSuffix()}"
  return if (isBlank()) message else "$this\n$message"
}

private fun AgentRunLivenessSnapshot?.detailsSuffix(): String = this?.let { snapshot ->
  val detail = listOfNotNull(
    snapshot.workflowId?.let { workflowId -> "workflow_id=$workflowId" },
    snapshot.workflowStep?.let { workflowStep -> "step=$workflowStep" },
    snapshot.lastDurableProgressAt?.let { timestamp -> "last_durable_progress_at=$timestamp" },
    snapshot.lastFileActivityAt?.let { timestamp -> "last_file_activity_at=$timestamp" },
    snapshot.lastOutputAt?.let { timestamp -> "last_output_at=$timestamp" },
  ).joinToString(", ")
  if (detail.isBlank()) "" else " Last observations: $detail."
} ?: ""

private sealed interface ProcessStart {
  data class Started(val process: Process) : ProcessStart
  data class Failed(val error: Exception) : ProcessStart
}

private class CappedUtf8Drain(
  private val input: InputStream,
  private val limitBytes: Int,
  private val outputStream: AgentRunOutputStream,
  private val outputSink: AgentRunOutputSink,
  private val onChunkRead: () -> Unit,
) {
  private val output = ByteArrayOutputStream(limitBytes.coerceAtMost(INITIAL_OUTPUT_BUFFER_BYTES))
  private val worker = thread(start = false, isDaemon = true, name = "skillbill-agent-run-output-drain") {
    try {
      input.use { stream ->
        val buffer = ByteArray(DEFAULT_DRAIN_BUFFER_BYTES)
        var remaining = limitBytes
        while (remaining > 0) {
          val read = stream.read(buffer, 0, remaining.coerceAtMost(buffer.size))
          if (read == -1) {
            break
          }
          onChunkRead()
          output.write(buffer, 0, read)
          outputSink.write(outputStream, String(buffer, 0, read, StandardCharsets.UTF_8))
          remaining -= read
        }
        while (stream.read(buffer) != -1) {
          // Keep draining so the child cannot block on a full pipe after the cap is reached.
        }
      }
    } catch (_: IOException) {
      // Forced process teardown can close pipes while drain threads are blocked in read().
    }
  }

  fun start() {
    worker.start()
  }

  fun join() {
    worker.join(DRAIN_JOIN_TIMEOUT_MILLIS)
  }

  fun text(): String = String(output.toByteArray(), StandardCharsets.UTF_8)
}

private class OutputObservationTracker {
  private val lastObservedMillis = AtomicLong(0L)

  fun markObserved() {
    lastObservedMillis.set(System.currentTimeMillis())
  }

  fun lastObservedAt(): Instant? = lastObservedMillis.get()
    .takeIf { millis -> millis > 0L }
    ?.let(Instant::ofEpochMilli)
}

private fun parseWorkflowIdAndStep(label: String?): Pair<String?, String?> {
  val text = label?.takeIf(String::isNotBlank) ?: return null to null
  val workflow = Regex("""workflow\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  val step = Regex("""step\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  return workflow to step
}

private fun Instant.toIsoUtc(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  .format(atOffset(ZoneOffset.UTC))

private const val DEFAULT_DRAIN_BUFFER_BYTES = 8192
private const val INITIAL_OUTPUT_BUFFER_BYTES = DEFAULT_DRAIN_BUFFER_BYTES
private const val DRAIN_JOIN_TIMEOUT_MILLIS = 1_000L
private const val MIN_TIMEOUT_MILLIS = 1L
private const val MIN_TIMEOUT_NANOS = 1L
private const val PROGRESS_POLL_INTERVAL_MILLIS = 250L
private const val DESTROY_WAIT_TIMEOUT_MILLIS = 1_000L
