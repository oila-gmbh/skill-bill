package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun gitCheckoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
  val normalizedBranch = branch.trim()
  if (normalizedBranch.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "Branch name is required.")
  }
  val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", normalizedBranch)
  return if (existing.ok) {
    gitCheckoutPreservingLocalChanges(repoRoot, listOf("checkout", "--merge", normalizedBranch))
      .withValue(normalizedBranch)
  } else {
    val base = baseBranch?.trim().orEmpty()
    if (base.isBlank()) {
      gitCheckoutPreservingLocalChanges(repoRoot, listOf("checkout", "--merge", "-b", normalizedBranch))
        .withValue(normalizedBranch)
    } else {
      gitCheckoutPreservingLocalChanges(repoRoot, listOf("checkout", "--merge", "-b", normalizedBranch, base))
        .withValue(normalizedBranch)
    }
  }
}

internal fun gitCheckoutPreservingLocalChanges(repoRoot: Path, args: List<String>): WorkflowGitOperationResult {
  val existingConflictMarkers = gitConflictMarkerPaths(repoRoot)
  val previouslyStaged = gitStagedPaths(repoRoot)
  if (previouslyStaged.isNotEmpty()) {
    val cleared = runGitCommand(repoRoot, "reset", "--quiet")
    if (!cleared.ok) return cleared
  }
  val outcome = gitMergeCheckout(repoRoot, args, existingConflictMarkers)
  if (previouslyStaged.isEmpty()) return outcome
  val restaged = runGitCommand(repoRoot, listOf("add", "--all", "--") + previouslyStaged)
  return if (restaged.ok) outcome else restaged
}

private fun gitMergeCheckout(
  repoRoot: Path,
  args: List<String>,
  existingConflictMarkers: List<String>,
): WorkflowGitOperationResult {
  val checkout = runGitCommand(repoRoot, args)
  val paths = gitConflictMarkerPaths(repoRoot).filterNot(existingConflictMarkers::contains)
  if (paths.isEmpty()) return checkout
  val resolved = runGitCommand(repoRoot, listOf("checkout", "--theirs", "--") + paths)
  if (!resolved.ok) return checkout
  val staged = runGitCommand(repoRoot, listOf("add", "--all", "--") + paths)
  return if (staged.ok) {
    WorkflowGitOperationResult(status = "ok", value = checkout.value)
  } else {
    staged
  }
}

internal fun gitStagedPaths(repoRoot: Path): List<String> {
  val staged = runGitCommand(repoRoot, "diff", "--cached", "--name-only", "-z", "HEAD")
  if (!staged.ok) return emptyList()
  return staged.value.split('\u0000').filter(String::isNotBlank)
}

internal fun gitConflictMarkerPaths(repoRoot: Path): List<String> {
  val check = runGitForActivity(repoRoot, listOf("diff", "--check"))
  if (check.ok) return emptyList()
  val markerPattern = Regex("""^(.*):\d+: leftover conflict marker$""")
  return check.error.lineSequence()
    .mapNotNull { line -> markerPattern.matchEntire(line)?.groupValues?.get(1) }
    .distinct()
    .toList()
}
