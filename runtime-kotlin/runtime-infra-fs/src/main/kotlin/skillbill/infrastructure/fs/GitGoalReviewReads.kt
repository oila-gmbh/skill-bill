package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun currentGoalReviewBranch(repoRoot: Path, expectedBranch: String): String? =
  goalReviewGitValue(repoRoot, "branch", "--show-current")?.trim()?.takeIf { it == expectedBranch }

internal fun goalReviewGitValue(repoRoot: Path, vararg args: String): String? =
  goalReviewGitValue(repoRoot, args.toList())

internal fun goalReviewGitValue(repoRoot: Path, args: List<String>): String? =
  runGitCommand(repoRoot, args).takeIf { it is WorkflowGitOperationResult.Ok }?.value

internal fun goalReviewUntrackedPaths(repoRoot: Path): List<String>? = runGitCommand(
  repoRoot,
  "ls-files",
  "--others",
  "--exclude-standard",
  "-z",
).takeIf { it is WorkflowGitOperationResult.Ok }
  ?.value
  ?.split('\u0000')
  ?.filter(String::isNotBlank)
  ?.distinct()
  ?.sorted()
