package skillbill.infrastructure.fs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSystemCheckedOutBranchSourceTest {
  private val source = FileSystemCheckedOutBranchSource()

  @Test
  fun `resolves the branch from a symbolic HEAD ref`() {
    val root = repo("branch-source-ref")
    Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/feat/SKILL-167-plugin-ci\n")

    assertEquals("feat/SKILL-167-plugin-ci", source.checkedOutBranch(root))
  }

  @Test
  fun `detached HEAD resolves to null`() {
    val root = repo("branch-source-detached")
    Files.writeString(root.resolve(".git/HEAD"), "225674aa5f3e0d5f9a1c7b2d8e4f6a0b1c2d3e4f\n")

    assertNull(source.checkedOutBranch(root))
  }

  @Test
  fun `missing HEAD and missing git marker resolve to null`() {
    assertNull(source.checkedOutBranch(repo("branch-source-empty")))
    assertNull(source.checkedOutBranch(Files.createTempDirectory("branch-source-no-git")))
  }

  @Test
  fun `worktree gitdir pointer file is followed to its HEAD`() {
    val shared = Files.createTempDirectory("branch-source-shared")
    val worktreeGitDir = shared.resolve("worktrees/wt-1")
    Files.createDirectories(worktreeGitDir)
    Files.writeString(worktreeGitDir.resolve("HEAD"), "ref: refs/heads/feat/SKILL-168-flicker\n")

    val root = Files.createTempDirectory("branch-source-worktree")
    Files.writeString(root.resolve(".git"), "gitdir: $worktreeGitDir\n")

    assertEquals("feat/SKILL-168-flicker", source.checkedOutBranch(root))
  }

  private fun repo(prefix: String): Path {
    val root = Files.createTempDirectory(prefix)
    Files.createDirectory(root.resolve(".git"))
    return root
  }
}
