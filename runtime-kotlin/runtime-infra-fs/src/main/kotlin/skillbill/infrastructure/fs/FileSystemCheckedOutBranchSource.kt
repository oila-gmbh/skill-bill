package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.system.CheckedOutBranchSource
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemCheckedOutBranchSource : CheckedOutBranchSource {

  override fun checkedOutBranch(repoRoot: Path): String? {
    val gitDir = resolveGitDir(repoRoot) ?: return null
    val head = runCatching { Files.readString(gitDir.resolve("HEAD")) }.getOrNull()?.trim()
    return head
      ?.takeIf { it.startsWith(HEAD_REF_PREFIX) }
      ?.removePrefix(HEAD_REF_PREFIX)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  private fun resolveGitDir(repoRoot: Path): Path? {
    val marker = repoRoot.resolve(".git")
    return when {
      Files.isDirectory(marker) -> marker
      // Worktree/submodule checkouts store a `gitdir: <path>` pointer file.
      Files.isRegularFile(marker) -> gitDirPointerTarget(repoRoot, marker)
      else -> null
    }
  }

  private fun gitDirPointerTarget(repoRoot: Path, marker: Path): Path? {
    val target = runCatching { Files.readString(marker) }.getOrNull()
      ?.lineSequence()
      ?.firstOrNull { it.startsWith(GITDIR_PREFIX) }
      ?.removePrefix(GITDIR_PREFIX)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: return null
    val path = Path.of(target)
    return if (path.isAbsolute) path else repoRoot.resolve(path).normalize()
  }

  private companion object {
    const val GITDIR_PREFIX = "gitdir:"
    const val HEAD_REF_PREFIX = "ref: refs/heads/"
  }
}
