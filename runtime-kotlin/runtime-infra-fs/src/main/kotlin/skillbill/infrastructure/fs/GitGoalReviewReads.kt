package skillbill.infrastructure.fs

import java.nio.file.Path

internal fun currentGoalReviewBranch(repoRoot: Path, expectedBranch: String): String? =
  goalReviewGitValue(repoRoot, "branch", "--show-current")?.trim()?.takeIf { it == expectedBranch }

internal fun goalReviewGitValue(repoRoot: Path, vararg args: String): String? =
  runGitCommand(repoRoot, *args).takeIf { it.ok }?.value

internal fun goalReviewUntrackedPaths(repoRoot: Path): List<String>? = runGitCommand(
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
