package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
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
    if (!commit.ok) return commit
    return runGit(repoRoot, "rev-parse", "HEAD")
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

  private fun runGit(repoRoot: Path, vararg args: String): WorkflowGitOperationResult {
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
