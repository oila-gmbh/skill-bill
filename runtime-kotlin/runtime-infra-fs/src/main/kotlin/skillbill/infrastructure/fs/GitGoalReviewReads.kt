package skillbill.infrastructure.fs

import java.nio.file.Path

internal fun currentGoalReviewBranch(repoRoot: Path, expectedBranch: String): String? =
  goalReviewGitValue(repoRoot, "branch", "--show-current")?.trim()?.takeIf { it == expectedBranch }

internal fun goalReviewGitValue(repoRoot: Path, vararg args: String): String? =
  goalReviewGitValue(repoRoot, args.toList())

internal fun goalReviewGitValue(repoRoot: Path, args: List<String>): String? =
  runGitCommand(repoRoot, args).takeIf { it.ok }?.value
