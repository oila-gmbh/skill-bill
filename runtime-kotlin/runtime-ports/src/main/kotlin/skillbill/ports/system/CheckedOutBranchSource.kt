package skillbill.ports.system

import java.nio.file.Path

/**
 * Resolves the checked-out git branch for a repository root. Returns null when HEAD is
 * detached, unreadable, or the `.git` layout is unrecognized — callers treat that as
 * "no branch context" rather than an error.
 */
fun interface CheckedOutBranchSource {
  fun checkedOutBranch(repoRoot: Path): String?
}
