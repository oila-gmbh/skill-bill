package skillbill.infrastructure.fs

import skillbill.ports.workflow.model.WorkflowGitOperationResult
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal const val GIT_TIMEOUT_SECONDS = 30L

internal fun runGitCommand(repoRoot: Path, vararg args: String): WorkflowGitOperationResult {
  val argList = args.toList()
  val result = runGitProcess(repoRoot, argList)
  return when {
    result.timedOut -> WorkflowGitOperationResult(
      status = "error",
      error = "git ${argList.joinToString(" ")} timed out after ${GIT_TIMEOUT_SECONDS}s.",
    )
    result.readFailure != null -> WorkflowGitOperationResult(
      status = "error",
      error = result.readFailure.message.orEmpty(),
    )
    result.exitCode == 0 -> WorkflowGitOperationResult(status = "ok", value = result.output)
    else -> WorkflowGitOperationResult(
      status = "error",
      error = "git ${argList.joinToString(" ")} failed with exit code ${result.exitCode}: ${result.output}",
    )
  }
}

internal fun runGitForActivity(repoRoot: Path, args: List<String>): WorkflowGitOperationResult {
  val result = runGitProcess(repoRoot, args)
  return when {
    result.timedOut -> WorkflowGitOperationResult(
      status = "error",
      error = "git ${args.joinToString(" ")} timed out after ${GIT_TIMEOUT_SECONDS}s.",
    )
    result.readFailure != null -> WorkflowGitOperationResult(
      status = "error",
      error = result.readFailure.message.orEmpty(),
    )
    result.exitCode == 0 -> WorkflowGitOperationResult(status = "ok", value = result.output)
    else -> WorkflowGitOperationResult(status = "error", error = result.output)
  }
}

internal fun runGitProcess(repoRoot: Path, args: List<String>): GitProcessResult {
  val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
    .redirectErrorStream(true)
    .start()
  val output = StringBuilder()
  var readFailure: IOException? = null
  val outputThread = thread(start = true, name = "skill-bill-git-output") {
    try {
      process.inputStream.bufferedReader().use { reader ->
        output.append(reader.readText())
      }
    } catch (error: IOException) {
      readFailure = error
    }
  }
  val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  return if (!finished) {
    process.destroyForcibly()
    closeInputAndJoin(process, outputThread)
    GitProcessResult(output = output.toString().trim(), readFailure = readFailure, timedOut = true)
  } else {
    outputThread.join()
    GitProcessResult(output = output.toString().trim(), readFailure = readFailure, exitCode = process.exitValue())
  }
}

internal data class GitProcessResult(
  val output: String,
  val readFailure: IOException?,
  val timedOut: Boolean = false,
  val exitCode: Int = -1,
)

internal fun closeInputAndJoin(process: Process, outputThread: Thread) {
  outputThread.join(GIT_OUTPUT_THREAD_JOIN_MILLIS)
  if (outputThread.isAlive) {
    process.inputStream.close()
    outputThread.join(GIT_OUTPUT_THREAD_JOIN_MILLIS)
  }
}

internal fun WorkflowGitOperationResult.withValue(value: String): WorkflowGitOperationResult =
  if (ok) copy(value = value) else this

private const val GIT_OUTPUT_THREAD_JOIN_MILLIS = 1_000L
