package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Inject
class GitWorkflowGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    val normalizedBranch = branch.trim()
    if (normalizedBranch.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch name is required.")
    }
    val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", normalizedBranch)
    return if (existing.ok) {
      runGitCommand(repoRoot, "checkout", normalizedBranch).withValue(normalizedBranch)
    } else {
      val base = baseBranch?.trim().orEmpty()
      if (base.isBlank()) {
        runGitCommand(repoRoot, "checkout", "-b", normalizedBranch).withValue(normalizedBranch)
      } else {
        runGitCommand(repoRoot, "checkout", "-b", normalizedBranch, base).withValue(normalizedBranch)
      }
    }
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    val normalizedBranch = branch.trim()
    if (normalizedBranch.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch name is required.")
    }
    val args = listOf("rev-parse", "--verify", "--quiet", "refs/heads/$normalizedBranch")
    val existing = runGitProcess(repoRoot, args)
    return when {
      existing.timedOut -> WorkflowGitOperationResult(
        status = "error",
        error = "git ${args.joinToString(" ")} timed out after ${GIT_TIMEOUT_SECONDS}s.",
      )
      existing.readFailure != null -> WorkflowGitOperationResult(
        status = "error",
        error = existing.readFailure.message.orEmpty(),
      )
      existing.exitCode == 0 -> WorkflowGitOperationResult(status = "ok", value = "true")
      existing.exitCode == 1 -> WorkflowGitOperationResult(status = "ok", value = "false")
      else -> WorkflowGitOperationResult(
        status = "error",
        error = "git ${args.joinToString(" ")} failed with exit code ${existing.exitCode}: ${existing.output}",
      )
    }
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "branch", "--show-current")

  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "add", "-A")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    val commit = runGitCommand(repoRoot, "commit", "-m", message)
    return if (commit.ok) runGitCommand(repoRoot, "rev-parse", "HEAD") else commit
  }

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "rev-parse", "HEAD")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult {
    val normalizedBranch = branch.trim()
    val normalizedBase = expectedBaseBranch.trim()
    if (normalizedBranch.isBlank() || normalizedBase.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch and expected base branch are required.")
    }
    val result = runGitCommand(repoRoot, "merge-base", "--is-ancestor", normalizedBase, normalizedBranch)
    return if (result.ok) {
      WorkflowGitOperationResult(status = "ok", value = normalizedBase)
    } else {
      WorkflowGitOperationResult(
        status = "error",
        error = "Branch '$normalizedBranch' is not based on '$normalizedBase'. ${result.error}".trim(),
      )
    }
  }

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "status", "--porcelain")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult {
    val status = worktreeStatus(repoRoot)
    if (!status.ok) {
      return WorkflowWorktreeActivityResult(status = "error", error = status.error)
    }
    val diff = combinedDiffStat(repoRoot)
    return WorkflowWorktreeActivityResult(
      status = "ok",
      changedFileSummary = parseChangedFileSummary(status.value),
      diffStat = diff,
    )
  }

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult {
    if (request.paths.isEmpty() || (!request.includeStaged && !request.includeUnstaged)) {
      return WorkflowSelectedDiffHunksResult(status = "ok")
    }
    val chunks = mutableListOf<GoalObservabilitySelectedDiffHunk>()
    val results = mutableListOf<WorkflowSelectedDiffHunksResult>()
    val budget = SelectedDiffBudget(request)
    if (request.includeUnstaged) {
      results += appendSelectedDiffHunks(repoRoot, request, staged = false, chunks = chunks, budget = budget)
    }
    if (
      request.includeStaged &&
      results.all(WorkflowSelectedDiffHunksResult::ok) &&
      results.none { result -> result.selectedDiffHunks.truncated }
    ) {
      results += appendSelectedDiffHunks(repoRoot, request, staged = true, chunks = chunks, budget = budget)
    }
    val errorResult = results.firstOrNull { result -> !result.ok }
    return errorResult ?: WorkflowSelectedDiffHunksResult(
      status = "ok",
      selectedDiffHunks = GoalObservabilitySelectedDiffHunks(
        hunks = chunks,
        truncated = results.any { result -> result.selectedDiffHunks.truncated },
      ),
    )
  }

  override fun captureGoalSubtaskReviewBaseline(
    repoRoot: Path,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult {
    val expected = expectedBranch.trim()
    if (expected.isBlank()) {
      return GoalSubtaskReviewBaselineResult(status = "error", error = "Goal-subtask durable child branch is required.")
    }
    val before = baselineSnapshot(repoRoot, expected)
    if (!before.ok) return GoalSubtaskReviewBaselineResult(status = "error", error = before.error)
    val after = baselineSnapshot(repoRoot, expected)
    if (!after.ok) return GoalSubtaskReviewBaselineResult(status = "error", error = after.error)
    if (before.snapshot != after.snapshot) {
      return GoalSubtaskReviewBaselineResult(
        status = "error",
        error =
        "Goal-subtask repository state changed while capturing its immutable review baseline; " +
          "refusing to persist it.",
      )
    }
    val snapshot = requireNotNull(before.snapshot)
    return GoalSubtaskReviewBaselineResult(
      status = "ok",
      baseline = GoalSubtaskReviewBaseline(
        reviewBaseSha = snapshot.headSha,
        baselineUntrackedPaths = snapshot.untrackedPaths,
      ),
    )
  }

  override fun buildGoalSubtaskReviewInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult {
    val expected = expectedBranch.trim()
    if (expected.isBlank()) {
      return GoalSubtaskReviewInputResult(status = "error", error = "Goal-subtask durable child branch is required.")
    }
    val before = reviewInputSnapshot(repoRoot, baseline, expected)
    if (!before.ok) return GoalSubtaskReviewInputResult(status = "error", error = before.error)
    val after = reviewInputSnapshot(repoRoot, baseline, expected)
    if (!after.ok) return GoalSubtaskReviewInputResult(status = "error", error = after.error)
    if (before.snapshot != after.snapshot) {
      return GoalSubtaskReviewInputResult(
        status = "error",
        error = "Goal-subtask repository state changed while building its review input; refusing a torn snapshot.",
      )
    }
    val snapshot = requireNotNull(before.snapshot)
    return GoalSubtaskReviewInputResult(
      status = "ok",
      input = GoalSubtaskReviewInput(
        reviewBaseSha = baseline.reviewBaseSha,
        currentHeadSha = snapshot.headSha,
        trackedDelta = snapshot.trackedDelta,
        ownedUntrackedPatches = snapshot.ownedUntrackedPatches,
      ),
    )
  }
}

private const val GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES: Int = 1_000_000

private data class GoalReviewBaselineSnapshot(
  val branch: String,
  val headSha: String,
  val indexTree: String,
  val trackedDelta: String,
  val untrackedPaths: List<String>,
)

private data class GoalReviewInputSnapshot(
  val branch: String,
  val headSha: String,
  val indexTree: String,
  val trackedDelta: String,
  val untrackedPaths: List<String>,
  val ownedUntrackedPatches: String,
)

private data class GoalReviewSnapshotResult<T>(
  val snapshot: T? = null,
  val error: String = "",
) {
  val ok: Boolean get() = snapshot != null && error.isBlank()
}

private fun baselineSnapshot(
  repoRoot: Path,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewBaselineSnapshot> {
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch) ?: return GoalReviewSnapshotResult(
    error = "Goal-subtask review baseline must be captured on durable child branch '$expectedBranch'.",
  )
  trackedWorktreeClean(repoRoot)?.let { error -> return GoalReviewSnapshotResult(error = error) }
  val head = gitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
    ?: return GoalReviewSnapshotResult(error = "Could not resolve HEAD.")
  val indexTree = gitValue(repoRoot, "write-tree")?.trim()?.takeIf(String::isNotBlank)
    ?: return GoalReviewSnapshotResult(
      error = "Could not resolve the git index while capturing the immutable review baseline.",
    )
  val tracked = gitValue(repoRoot, "diff", "--binary", "HEAD") ?: return GoalReviewSnapshotResult(
    error = "Could not read tracked worktree state while capturing the immutable review baseline.",
  )
  val untracked = untrackedPaths(repoRoot) ?: return GoalReviewSnapshotResult(
    error = "Could not read untracked inventory while capturing the immutable review baseline.",
  )
  return GoalReviewSnapshotResult(
    snapshot = GoalReviewBaselineSnapshot(branch, head, indexTree, tracked, untracked),
  )
}

private fun reviewInputSnapshot(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewInputSnapshot> {
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch) ?: return GoalReviewSnapshotResult(
    error = "Goal-subtask review must run on durable child branch '$expectedBranch'.",
  )
  val head = gitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
    ?: return GoalReviewSnapshotResult(error = "Could not resolve current HEAD.")
  val verified = runGitCommand(repoRoot, "cat-file", "-e", "${baseline.reviewBaseSha}^{commit}")
  if (!verified.ok) {
    return GoalReviewSnapshotResult(
      error = "Persisted review base '${baseline.reviewBaseSha}' is not an existing commit: ${verified.error}",
    )
  }
  val ancestor = runGitCommand(repoRoot, "merge-base", "--is-ancestor", baseline.reviewBaseSha, head)
  if (!ancestor.ok) {
    return GoalReviewSnapshotResult(
      error =
      "Persisted review base '${baseline.reviewBaseSha}' is not an ancestor of current HEAD; " +
        "refusing a broader review scope.",
    )
  }
  val indexTree = gitValue(repoRoot, "write-tree")?.trim()?.takeIf(String::isNotBlank)
    ?: return GoalReviewSnapshotResult(
      error = "Could not resolve the git index while materializing the exact review input.",
    )
  val trackedDelta = gitValue(repoRoot, "diff", "--binary", baseline.reviewBaseSha) ?: return GoalReviewSnapshotResult(
    error = "Could not materialize tracked changes from the immutable review base.",
  )
  val untracked = untrackedPaths(repoRoot) ?: return GoalReviewSnapshotResult(
    error = "Could not read untracked inventory while materializing the exact review input.",
  )
  val ownedPaths = untracked.filterNot { it in baseline.baselineUntrackedPaths }
  val ownedPatches = ownedUntrackedPatches(repoRoot, ownedPaths)
  if (!ownedPatches.ok) return GoalReviewSnapshotResult(error = ownedPatches.error)
  val patches = requireNotNull(ownedPatches.value)
  if (trackedDelta.toByteArray().size + patches.toByteArray().size > GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES) {
    return GoalReviewSnapshotResult(
      error = "Goal-subtask review input exceeds the ${GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES}-byte bound.",
    )
  }
  return GoalReviewSnapshotResult(
    snapshot = GoalReviewInputSnapshot(branch, head, indexTree, trackedDelta, untracked, patches),
  )
}

private data class GoalReviewStringResult(val value: String? = null, val error: String = "") {
  val ok: Boolean get() = value != null && error.isBlank()
}

private fun ownedUntrackedPatches(repoRoot: Path, paths: List<String>): GoalReviewStringResult {
  val patches = StringBuilder()
  paths.forEach { path ->
    val patch = runGitProcess(repoRoot, listOf("diff", "--binary", "--no-index", "/dev/null", path))
    if (patch.timedOut || patch.readFailure != null || patch.exitCode !in setOf(0, 1)) {
      val error = patch.readFailure?.message ?: "Could not diff owned untracked path '$path'."
      return GoalReviewStringResult(error = error)
    }
    patches.append(patch.output)
    if (!patches.endsWith("\n")) patches.append('\n')
    if (patches.toString().toByteArray().size > GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES) {
      return GoalReviewStringResult(
        error = "Goal-subtask review input exceeds the ${GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES}-byte bound.",
      )
    }
  }
  return GoalReviewStringResult(value = patches.toString())
}

private fun currentGoalReviewBranch(repoRoot: Path, expectedBranch: String): String? =
  gitValue(repoRoot, "branch", "--show-current")?.trim()?.takeIf { it == expectedBranch }

private fun gitValue(repoRoot: Path, vararg args: String): String? =
  runGitCommand(repoRoot, *args).takeIf { it.ok }?.value

private fun untrackedPaths(repoRoot: Path): List<String>? = runGitCommand(
  repoRoot,
  "ls-files",
  "--others",
  "--exclude-standard",
  "-z",
).takeIf { it.ok }
  ?.value
  ?.split('\u0000')
  ?.filter(String::isNotBlank)
  ?.distinct()
  ?.sorted()

private fun trackedWorktreeClean(repoRoot: Path): String? {
  val unstaged = runGitProcess(repoRoot, listOf("diff", "--quiet"))
  if (unstaged.timedOut || unstaged.readFailure != null || unstaged.exitCode !in setOf(0, 1)) {
    return unstaged.readFailure?.message ?: "Could not inspect unstaged tracked changes before review baseline capture."
  }
  if (unstaged.exitCode == 1) {
    return "Goal-subtask review baseline capture requires a clean tracked worktree; " +
      "unstaged tracked changes are present."
  }
  val staged = runGitProcess(repoRoot, listOf("diff", "--cached", "--quiet"))
  if (staged.timedOut || staged.readFailure != null || staged.exitCode !in setOf(0, 1)) {
    return staged.readFailure?.message ?: "Could not inspect staged tracked changes before review baseline capture."
  }
  return if (staged.exitCode == 1) {
    "Goal-subtask review baseline capture requires a clean tracked worktree; staged tracked changes are present."
  } else {
    null
  }
}

private fun combinedDiffStat(repoRoot: Path): GoalObservabilityDiffStat {
  val unstaged = runCatchingDiffStat(repoRoot, "diff", "--numstat")
  val staged = runCatchingDiffStat(repoRoot, "diff", "--cached", "--numstat")
  return GoalObservabilityDiffStat(
    filesChanged = unstaged.filesChanged + staged.filesChanged,
    insertions = unstaged.insertions + staged.insertions,
    deletions = unstaged.deletions + staged.deletions,
  )
}

private fun runCatchingDiffStat(repoRoot: Path, vararg args: String): GoalObservabilityDiffStat {
  val result = runGitForActivity(repoRoot, args.toList())
  return if (result.ok) parseDiffStat(result.value) else GoalObservabilityDiffStat(0, 0, 0)
}

private fun parseChangedFileSummary(statusOutput: String): GoalObservabilityChangedFileSummary {
  var added = 0
  var modified = 0
  var deleted = 0
  var renamed = 0
  var untracked = 0
  val paths = mutableListOf<String>()
  statusOutput.lineSequence()
    .map(String::trimEnd)
    .filter { line -> line.length >= GIT_STATUS_MIN_LENGTH }
    .forEach { line ->
      val status = line.take(GIT_STATUS_CODE_LENGTH)
      val path = line.drop(GIT_STATUS_PATH_OFFSET).substringAfterLast(" -> ").trim()
      if (path.isNotBlank()) paths += path
      when {
        status == "??" -> {
          added += 1
          untracked += 1
        }
        'R' in status -> renamed += 1
        'D' in status -> deleted += 1
        'A' in status -> added += 1
        'M' in status -> modified += 1
      }
    }
  return GoalObservabilityChangedFileSummary(
    total = paths.size,
    added = added,
    modified = modified,
    deleted = deleted,
    renamed = renamed,
    untracked = untracked,
    samplePaths = paths.take(GIT_CHANGED_FILE_SAMPLE_LIMIT),
  )
}

private fun parseDiffStat(numstatOutput: String): GoalObservabilityDiffStat {
  var filesChanged = 0
  var insertions = 0
  var deletions = 0
  numstatOutput.lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .forEach { line ->
      val parts = line.split(Regex("\\s+"), limit = GIT_NUMSTAT_PART_LIMIT)
      if (parts.size >= GIT_NUMSTAT_PART_LIMIT) {
        filesChanged += 1
        insertions += parts[0].toIntOrNull() ?: 0
        deletions += parts[1].toIntOrNull() ?: 0
      }
    }
  return GoalObservabilityDiffStat(filesChanged = filesChanged, insertions = insertions, deletions = deletions)
}

private fun appendSelectedDiffHunks(
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

private fun readSelectedDiffHunks(
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
  val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  val result = if (!finished) {
    process.destroyForcibly()
    closeInputAndJoin(process, outputThread)
    SelectedDiffReadResult(
      status = "error",
      error = "git ${args.joinToString(" ")} timed out after ${GIT_TIMEOUT_SECONDS}s.",
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

private data class SelectedDiffReadResult(
  val status: String,
  val hunks: GoalObservabilitySelectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"
}

private data class BoundedDiffLine(
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

private fun java.io.BufferedReader.readBoundedDiffLine(maxBytes: Int): BoundedDiffLine? {
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

private class SelectedDiffBudget(
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

private data class SelectedDiffLineRecord(
  val line: String? = null,
  val truncated: Boolean,
)

private class SelectedDiffHunkParser(
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

private const val GIT_STATUS_MIN_LENGTH = 4
private const val GIT_STATUS_CODE_LENGTH = 2
private const val GIT_STATUS_PATH_OFFSET = 3
private const val GIT_CHANGED_FILE_SAMPLE_LIMIT = 10
private const val GIT_NUMSTAT_PART_LIMIT = 3
private const val GIT_ERROR_OUTPUT_LIMIT = 4_000
private const val GIT_SELECTED_DIFF_MIN_READ_LINE_BYTES = 4_096
