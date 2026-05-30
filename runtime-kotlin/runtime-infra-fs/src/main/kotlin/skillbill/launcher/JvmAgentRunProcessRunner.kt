package skillbill.launcher

import me.tatarka.inject.annotations.Inject
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.DurationUnit

@Inject
class JvmAgentRunProcessRunner : AgentRunProcessRunner {
  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    val processStart = startProcess(request)
    if (processStart is ProcessStart.Failed) {
      return spawnFailure(processStart.error)
    }
    val process = (processStart as ProcessStart.Started).process

    val stdout = CappedUtf8Drain(
      input = process.inputStream,
      limitBytes = AGENT_RUN_OUTPUT_LIMIT_BYTES,
      outputStream = AgentRunOutputStream.STDOUT,
      outputSink = request.outputSink,
    ).also { it.start() }
    val stderr = CappedUtf8Drain(
      input = process.errorStream,
      limitBytes = AGENT_RUN_OUTPUT_LIMIT_BYTES,
      outputStream = AgentRunOutputStream.STDERR,
      outputSink = request.outputSink,
    ).also { it.start() }
    val timeoutMillis = request.timeout.toLong(DurationUnit.MILLISECONDS).coerceAtLeast(MIN_TIMEOUT_MILLIS)
    val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      process.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }
    stdout.join()
    stderr.join()
    return AgentRunProcessResult(
      exitStatus = if (finished) process.exitValue() else null,
      stdout = stdout.text(),
      stderr = stderr.text(),
      timedOut = !finished,
      spawnFailed = false,
    )
  }

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

private sealed interface ProcessStart {
  data class Started(val process: Process) : ProcessStart
  data class Failed(val error: Exception) : ProcessStart
}

private class CappedUtf8Drain(
  private val input: InputStream,
  private val limitBytes: Int,
  private val outputStream: AgentRunOutputStream,
  private val outputSink: AgentRunOutputSink,
) {
  private val output = ByteArrayOutputStream(limitBytes.coerceAtMost(INITIAL_OUTPUT_BUFFER_BYTES))
  private val worker = thread(start = false, isDaemon = true, name = "skillbill-agent-run-output-drain") {
    input.use { stream ->
      val buffer = ByteArray(DEFAULT_DRAIN_BUFFER_BYTES)
      var remaining = limitBytes
      while (remaining > 0) {
        val read = stream.read(buffer, 0, remaining.coerceAtMost(buffer.size))
        if (read == -1) {
          break
        }
        output.write(buffer, 0, read)
        outputSink.write(outputStream, String(buffer, 0, read, StandardCharsets.UTF_8))
        remaining -= read
      }
      while (stream.read(buffer) != -1) {
        // Keep draining so the child cannot block on a full pipe after the cap is reached.
      }
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

private const val DEFAULT_DRAIN_BUFFER_BYTES = 8192
private const val INITIAL_OUTPUT_BUFFER_BYTES = DEFAULT_DRAIN_BUFFER_BYTES
private const val DRAIN_JOIN_TIMEOUT_MILLIS = 1_000L
private const val MIN_TIMEOUT_MILLIS = 1L
private const val DESTROY_WAIT_TIMEOUT_MILLIS = 1_000L
