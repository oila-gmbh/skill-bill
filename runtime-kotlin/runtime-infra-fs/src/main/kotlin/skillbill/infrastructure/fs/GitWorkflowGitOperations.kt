package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val GIT_TIMEOUT_SECONDS = 30L

@Inject
class GitWorkflowGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    val normalizedBranch = branch.trim()
    if (normalizedBranch.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch name is required.")
    }
    val existing = runGit(repoRoot, "rev-parse", "--verify", "--quiet", normalizedBranch)
    return if (existing.ok) {
      runGit(repoRoot, "checkout", normalizedBranch).withValue(normalizedBranch)
    } else {
      val base = baseBranch?.trim().orEmpty()
      if (base.isBlank()) {
        runGit(repoRoot, "checkout", "-b", normalizedBranch).withValue(normalizedBranch)
      } else {
        runGit(repoRoot, "checkout", "-b", normalizedBranch, base).withValue(normalizedBranch)
      }
    }
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult = runGit(repoRoot, "branch", "--show-current")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    val commit = runGit(repoRoot, "commit", "-m", message)
    return if (commit.ok) runGit(repoRoot, "rev-parse", "HEAD") else commit
  }

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
    val result = runGit(repoRoot, "merge-base", "--is-ancestor", normalizedBase, normalizedBranch)
    return if (result.ok) {
      WorkflowGitOperationResult(status = "ok", value = normalizedBase)
    } else {
      WorkflowGitOperationResult(
        status = "error",
        error = "Branch '$normalizedBranch' is not based on '$normalizedBase'. ${result.error}".trim(),
      )
    }
  }

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = runGit(repoRoot, "status", "--porcelain")

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

  private fun runGit(repoRoot: Path, vararg args: String): WorkflowGitOperationResult = runGit(repoRoot, args.toList())

  private fun runGit(repoRoot: Path, args: List<String>): WorkflowGitOperationResult {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
      .redirectErrorStream(true)
      .start()
    val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    val output = process.inputStream.bufferedReader().readText().trim()
    if (!finished) {
      process.destroyForcibly()
      return WorkflowGitOperationResult(
        status = "error",
        error = "git ${args.joinToString(" ")} timed out after ${GIT_TIMEOUT_SECONDS}s.",
      )
    }
    return if (process.exitValue() == 0) {
      WorkflowGitOperationResult(status = "ok", value = output)
    } else {
      WorkflowGitOperationResult(
        status = "error",
        error = "git ${args.joinToString(" ")} failed with exit code ${process.exitValue()}: $output",
      )
    }
  }

  private fun WorkflowGitOperationResult.withValue(value: String): WorkflowGitOperationResult =
    if (ok) copy(value = value) else this
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

private fun runGitForActivity(repoRoot: Path, args: List<String>): WorkflowGitOperationResult {
  val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
    .redirectErrorStream(true)
    .start()
  val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  val output = process.inputStream.bufferedReader().readText().trim()
  if (!finished) {
    process.destroyForcibly()
    return WorkflowGitOperationResult(
      status = "error",
      error = "git ${args.joinToString(" ")} timed out after ${GIT_TIMEOUT_SECONDS}s.",
    )
  }
  return if (process.exitValue() == 0) {
    WorkflowGitOperationResult(status = "ok", value = output)
  } else {
    WorkflowGitOperationResult(status = "error", error = output)
  }
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

private const val GIT_STATUS_MIN_LENGTH = 4
private const val GIT_STATUS_CODE_LENGTH = 2
private const val GIT_STATUS_PATH_OFFSET = 3
private const val GIT_CHANGED_FILE_SAMPLE_LIMIT = 10
private const val GIT_NUMSTAT_PART_LIMIT = 3
