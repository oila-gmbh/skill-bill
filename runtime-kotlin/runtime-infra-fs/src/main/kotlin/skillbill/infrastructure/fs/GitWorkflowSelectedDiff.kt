package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal fun appendSelectedDiffHunks(
  repoRoot: Path,
  request: WorkflowSelectedDiffHunksRequest,
  staged: Boolean,
  chunks: MutableList<GoalObservabilitySelectedDiffHunk>,
  budget: SelectedDiffBudget,
): WorkflowSelectedDiffHunksResult {
  val args = if (staged) {
    listOf("diff", "--cached", "--unified=3", "--") + request.paths
  } else {
    listOf("diff", "--unified=3", "--") + request.paths
  }
  val result = readSelectedDiffHunks(
    repoRoot = repoRoot,
    args = args,
    staged = staged,
    budget = budget,
  )
  if (!result.ok) {
    return WorkflowSelectedDiffHunksResult(status = "error", error = result.error)
  }
  chunks += result.hunks.hunks
  return WorkflowSelectedDiffHunksResult(status = "ok", selectedDiffHunks = result.hunks)
}

internal fun readSelectedDiffHunks(
  repoRoot: Path,
  args: List<String>,
  staged: Boolean,
  budget: SelectedDiffBudget,
): SelectedDiffReadResult {
  val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
    .redirectErrorStream(true)
    .start()
  val parser = SelectedDiffHunkParser(staged, budget)
  val errorOutput = StringBuilder()
  var readFailure: IOException? = null
  val outputThread = thread(start = true, name = "skill-bill-selected-diff-output") {
    try {
      process.inputStream.bufferedReader().use { reader ->
        var keepReading = true
        while (keepReading) {
          val line = reader.readBoundedDiffLine(budget.readLineMaxBytes)
          if (line == null) {
            keepReading = false
          } else {
            line.appendTo(errorOutput)
            parser.consume(line.text, line.truncated)
            if (parser.truncated) {
              process.destroyForcibly()
              keepReading = false
            }
          }
        }
      }
    } catch (error: IOException) {
      if (!parser.truncated) {
        readFailure = error
      }
    }
  }
  val timeoutSeconds = gitTimeoutSeconds(args)
  val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
  val result = if (!finished) {
    process.destroyForcibly()
    closeInputAndJoin(process, outputThread)
    SelectedDiffReadResult(
      status = "error",
      error = gitTimedOutError(args),
    )
  } else {
    outputThread.join()
    val failure = readFailure
    val parsed = parser.result()
    when {
      failure != null -> SelectedDiffReadResult(status = "error", error = failure.message.orEmpty())
      !parsed.truncated && process.exitValue() != 0 ->
        SelectedDiffReadResult(status = "error", error = errorOutput.toString().trim())
      else -> SelectedDiffReadResult(status = "ok", hunks = parsed)
    }
  }
  return result
}

internal data class SelectedDiffReadResult(
  val status: String,
  val hunks: GoalObservabilitySelectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"
}

internal data class BoundedDiffLine(
  val text: String,
  val truncated: Boolean,
) {
  fun appendTo(output: StringBuilder) {
    if (output.length >= GIT_ERROR_OUTPUT_LIMIT) {
      return
    }
    val remaining = GIT_ERROR_OUTPUT_LIMIT - output.length
    if (text.length + 1 <= remaining) {
      output.append(text).append('\n')
    } else {
      output.append(text.take(remaining))
    }
  }
}

internal fun BufferedReader.readBoundedDiffLine(maxBytes: Int): BoundedDiffLine? {
  val line = StringBuilder()
  var bytes = 0
  var sawContent = false
  var truncated = false
  var complete = false
  while (!complete) {
    val next = read()
    when {
      next == -1 -> complete = true
      next.toChar() == '\n' -> {
        sawContent = true
        complete = true
      }
      else -> {
        sawContent = true
        val char = next.toChar()
        val charBytes = char.toString().toByteArray().size
        if (bytes + charBytes > maxBytes) {
          truncated = true
          complete = true
        } else {
          line.append(char)
          bytes += charBytes
        }
      }
    }
  }
  return if (sawContent) BoundedDiffLine(line.toString(), truncated = truncated) else null
}

internal class SelectedDiffBudget(
  request: WorkflowSelectedDiffHunksRequest,
) {
  private val maxHunks = request.maxHunks
  private val maxLines = request.maxLines
  val maxBytes = request.maxBytes
  val readLineMaxBytes = maxOf(maxBytes, GIT_SELECTED_DIFF_MIN_READ_LINE_BYTES)
  var hunkCount: Int = 0
    private set
  private var lineCount: Int = 0
  private var byteCount: Int = 0

  fun canStartHunk(): Boolean = hunkCount < maxHunks

  fun recordHunk() {
    hunkCount += 1
  }

  fun tryRecordLine(line: String): SelectedDiffLineRecord {
    val nextBytes = line.toByteArray().size + 1
    if (lineCount >= maxLines || byteCount + nextBytes > maxBytes) {
      return tryRecordTruncatedLine(line)
    }
    lineCount += 1
    byteCount += nextBytes
    return SelectedDiffLineRecord(line = line, truncated = false)
  }

  private fun tryRecordTruncatedLine(line: String): SelectedDiffLineRecord {
    var recordedLine: String? = null
    val remainingLineBytes = maxBytes - byteCount - 1
    if (lineCount < maxLines && remainingLineBytes > 0) {
      val truncatedLine = utf8Prefix(line, remainingLineBytes)
      if (truncatedLine.isNotEmpty() || line.isEmpty()) {
        lineCount += 1
        byteCount += truncatedLine.toByteArray().size + 1
        recordedLine = truncatedLine
      }
    }
    return SelectedDiffLineRecord(line = recordedLine, truncated = true)
  }

  private fun utf8Prefix(line: String, maxBytes: Int): String {
    val prefix = StringBuilder()
    var bytes = 0
    for (char in line) {
      val charBytes = char.toString().toByteArray().size
      if (bytes + charBytes > maxBytes) {
        break
      }
      prefix.append(char)
      bytes += charBytes
    }
    return prefix.toString()
  }
}

internal data class SelectedDiffLineRecord(
  val line: String? = null,
  val truncated: Boolean,
)

internal class SelectedDiffHunkParser(
  private val staged: Boolean,
  private val budget: SelectedDiffBudget,
) {
  private val hunks = mutableListOf<GoalObservabilitySelectedDiffHunk>()
  private val currentLines = mutableListOf<String>()
  private var currentPath = ""
  private var currentHeader: String? = null
  var truncated: Boolean = false
    private set

  fun consume(line: String, lineTruncated: Boolean = false) {
    if (lineTruncated) {
      truncated = true
    }
    when {
      line.startsWith("diff --git ") -> startFile(line)
      line.startsWith("@@") -> startHunk(line)
      currentHeader != null && !line.startsWith("\\ No newline at end of file") -> appendLine(line, lineTruncated)
    }
  }

  fun result(): GoalObservabilitySelectedDiffHunks {
    flushCurrent()
    return GoalObservabilitySelectedDiffHunks(hunks = hunks, truncated = truncated)
  }

  private fun startFile(line: String) {
    flushCurrent()
    currentPath = line.substringAfter(" b/", missingDelimiterValue = "")
  }

  private fun startHunk(line: String) {
    flushCurrent()
    if (!budget.canStartHunk()) {
      truncated = true
    } else {
      currentHeader = line
    }
  }

  private fun appendLine(line: String, lineTruncated: Boolean) {
    val record = budget.tryRecordLine(line)
    val recordedLine = record.line
    if (recordedLine != null) {
      currentLines += recordedLine
    }
    if (lineTruncated || record.truncated) {
      truncated = true
    }
  }

  private fun flushCurrent() {
    val header = currentHeader
    if (header != null && currentPath.isNotBlank()) {
      budget.recordHunk()
      hunks += GoalObservabilitySelectedDiffHunk(
        path = currentPath,
        staged = staged,
        header = header,
        lines = currentLines.toList(),
        truncated = truncated,
      )
    }
    currentHeader = null
    currentLines.clear()
  }
}

internal const val UNTRACKED_NON_REGULAR_MARKER = "non-regular"
internal const val UNTRACKED_UNREADABLE_MARKER = "unreadable"
internal const val UNTRACKED_FINGERPRINT_CONTENT_MAX_BYTES = 1_048_576L
internal const val UNTRACKED_FINGERPRINT_BUFFER_BYTES = 8_192
internal const val GIT_STATUS_MIN_LENGTH = 4
internal const val GIT_STATUS_CODE_LENGTH = 2
internal const val GIT_STATUS_PATH_OFFSET = 3
internal const val GIT_CHANGED_FILE_SAMPLE_LIMIT = 10
internal const val GIT_NUMSTAT_PART_LIMIT = 3
internal const val GIT_RENAME_NAME_STATUS_MIN_FIELDS = 3
internal const val GIT_ERROR_OUTPUT_LIMIT = 4_000
internal const val GIT_SELECTED_DIFF_MIN_READ_LINE_BYTES = 4_096
