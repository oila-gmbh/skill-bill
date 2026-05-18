@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package skillbill.desktop.feature.skillbill.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * JVM `actual` for [runValidateAgentConfigs]. SKILL-46 AC8.
 *
 * Hardening:
 * - `redirectErrorStream(true)` interleaves stderr with stdout.
 * - We drain the merged stream from a daemon thread to a [ByteArrayOutputStream] capped at
 *   [MAX_OUTPUT_BYTES] (8 MiB) so a runaway script cannot exhaust the heap.
 * - UTF-8 decoding.
 * - Env scrub (F-S02): removes `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_EXTERNAL_DIFF`,
 *   `GIT_PAGER`, `GIT_EDITOR`, `GIT_SSH_COMMAND`, `GIT_ASKPASS`, plus EVERY key prefixed with
 *   `GIT_CONFIG` (catches `GIT_CONFIG_COUNT`, `GIT_CONFIG_KEY_*`, `GIT_CONFIG_VALUE_*`,
 *   `GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_NOSYSTEM`, etc.) and `GIT_TRACE*` (catches the entire
 *   trace family). `GIT_FILTER` is removed from the list — it was never a real git env var.
 *   Sets `GIT_TERMINAL_PROMPT=0` to refuse interactive prompts.
 * - F-003-RELIABILITY-CANCEL: the blocking `process.waitFor` is wrapped in [runInterruptible] so
 *   coroutine cancellation interrupts the wait. A `finally` block calls `destroyForcibly` when
 *   the process is still alive, so cancellation does not leak the child.
 * - 5-minute hard timeout to keep the UI from blocking on an unresponsive script.
 */
private const val MAX_OUTPUT_BYTES: Int = 8 * 1024 * 1024
private const val PROCESS_TIMEOUT_MINUTES: Long = 5L

actual suspend fun runValidateAgentConfigs(repoRootAbsolutePath: String): ValidateAgentConfigsRunResult {
  val scriptPath = "$repoRootAbsolutePath/scripts/validate_agent_configs"
  val scriptFile = File(scriptPath)
  if (!scriptFile.exists()) {
    return ValidateAgentConfigsRunResult(
      outputLines = listOf("[validate_agent_configs] script not found at $scriptPath"),
      exitCode = -1,
    )
  }
  val builder = ProcessBuilder(scriptPath)
    .directory(File(repoRootAbsolutePath))
    .redirectErrorStream(true)
  scrubGitEnvironment(builder.environment())

  val output = ByteArrayOutputStream()
  val process = try {
    builder.start()
  } catch (error: Throwable) {
    return ValidateAgentConfigsRunResult(
      outputLines = listOf("[validate_agent_configs] failed to start: ${error.message.orEmpty()}"),
      exitCode = -1,
    )
  }
  return try {
    val drainThread = Thread({
      val buffer = ByteArray(4096)
      process.inputStream.use { stream ->
        while (output.size() < MAX_OUTPUT_BYTES) {
          val read = stream.read(buffer)
          if (read <= 0) break
          val remaining = MAX_OUTPUT_BYTES - output.size()
          output.write(buffer, 0, minOf(read, remaining))
        }
      }
    }, "validate-agent-configs-drain").apply { isDaemon = true }
    drainThread.start()
    // F-003-RELIABILITY-CANCEL: runInterruptible on Dispatchers.IO lets coroutine cancellation
    // interrupt the blocking wait. The finally clause below will destroyForcibly the child if
    // we are cancelled mid-wait so we never leak the process.
    val completed = runInterruptible(Dispatchers.IO) {
      process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    }
    if (!completed) {
      process.destroyForcibly()
    }
    drainThread.join(TimeUnit.SECONDS.toMillis(5))
    val exitCode = if (completed) process.exitValue() else -1
    val text = output.toString(StandardCharsets.UTF_8.name())
    val lines = if (text.isEmpty()) emptyList() else text.split('\n').dropLastWhile { it.isEmpty() }
    ValidateAgentConfigsRunResult(outputLines = lines, exitCode = exitCode)
  } finally {
    if (process.isAlive) {
      process.destroyForcibly()
    }
  }
}

/**
 * F-S02: scrub every git env var that could redirect the child process to a non-default config
 * location. Uses prefix matches for `GIT_CONFIG*` and `GIT_TRACE*` so future git additions are
 * also stripped without code changes.
 */
private fun scrubGitEnvironment(environment: MutableMap<String, String>) {
  // Removing while iterating is not safe; snapshot keys first.
  val toRemove = environment.keys.toList().filter { key ->
    key in DENY_LIST ||
      key.startsWith("GIT_CONFIG") ||
      key.startsWith("GIT_TRACE")
  }
  toRemove.forEach(environment::remove)
  environment["GIT_TERMINAL_PROMPT"] = "0"
}

private val DENY_LIST: Set<String> = setOf(
  "GIT_DIR",
  "GIT_WORK_TREE",
  "GIT_INDEX_FILE",
  "GIT_EXTERNAL_DIFF",
  "GIT_PAGER",
  "GIT_EDITOR",
  "GIT_SSH_COMMAND",
  "GIT_ASKPASS",
)
