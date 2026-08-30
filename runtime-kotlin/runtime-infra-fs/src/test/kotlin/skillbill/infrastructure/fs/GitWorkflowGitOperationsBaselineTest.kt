package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorkflowGitOperationsBaselineTest {
  @Test
  fun `runtime phase commit range reports committed paths`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-runtime-phase")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    val operations = GitWorkflowGitOperations()
    val before = operations.runtimePhaseHeadCommit(repoRoot)
    val specPath = repoRoot.resolve(".feature-specs/SKILL-124-demo/spec.md")
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "# Spec\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "unauthorized spec")
    val after = operations.runtimePhaseHeadCommit(repoRoot)

    val result = operations.runtimePhaseChangedPathsBetweenCommits(
      repoRoot,
      requireNotNull(before.value),
      requireNotNull(after.value),
    )

    assertTrue(result.ok, result.error)
    assertContains(result.value.orEmpty(), ".feature-specs/SKILL-124-demo/spec.md")
  }

  @Test
  fun `create commit includes staged decomposition manifest projection`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-workflow")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("runtime.txt"), "runtime change\n")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52-demo/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(manifestPath, "contract_version: \"0.1\"\n")
    git(repoRoot, "add", ".")

    val result = GitWorkflowGitOperations().createCommit(repoRoot, "SKILL-52 subtask 1: demo")

    assertTrue(result.ok, result.error)
    val committedFiles = git(repoRoot, "show", "--name-only", "--format=", "HEAD")
    assertContains(committedFiles, "runtime.txt")
    assertContains(committedFiles, ".feature-specs/SKILL-52-demo/decomposition-manifest.yaml")
    assertEquals("", git(repoRoot, "status", "--short"))
  }

  @Test
  fun `create commit with nothing staged is a no-op`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-empty-commit")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    val before = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "two\n")

    val result = GitWorkflowGitOperations().createCommit(repoRoot, "chore: nothing staged")

    assertTrue(result.ok, result.error)
    assertEquals("", result.value)
    assertEquals(before, git(repoRoot, "rev-parse", "HEAD"))
    assertContains(git(repoRoot, "status", "--porcelain"), "tracked.txt")
  }

  @Test
  fun `branch exists reports presence without creating the branch`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-branch-exists")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "branch", "feat/present")
    val ops = GitWorkflowGitOperations()

    val present = ops.branchExists(repoRoot, "feat/present")
    val absent = ops.branchExists(repoRoot, "feat/absent")

    assertTrue(present.ok, present.error)
    assertEquals("true", present.value)
    assertTrue(absent.ok, absent.error)
    assertEquals("false", absent.value)
    assertFalse(git(repoRoot, "branch", "--list", "feat/absent").contains("feat/absent"))
  }

  @Test
  fun `branch exists reports fatal git failures as errors instead of absent branches`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-branch-exists-not-repo")

    val result = GitWorkflowGitOperations().branchExists(repoRoot, "feat/persisted")

    assertFalse(result.ok)
    assertContains(result.error, "git rev-parse")
  }

  @Test
  fun `checkout preserves local tracked and untracked changes when switching branches`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-checkout-preserves-changes")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\ntarget\nlocal\n")
    Files.writeString(repoRoot.resolve("staged.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "checkout", "-b", "feat/takeover")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\ntarget-change\nlocal\n")
    Files.writeString(repoRoot.resolve("target-only.txt"), "target-only change\n")
    git(repoRoot, "add", "target-only.txt")
    git(repoRoot, "commit", "-am", "target change")
    git(repoRoot, "checkout", "main")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\ntarget\nlocal-change\n")
    Files.writeString(repoRoot.resolve("staged.txt"), "staged change\n")
    git(repoRoot, "add", "staged.txt")
    Files.writeString(repoRoot.resolve("untracked.txt"), "untracked change\n")

    val result = GitWorkflowGitOperations().checkoutBranch(repoRoot, "feat/takeover")

    assertTrue(result.ok, result.error)
    assertEquals("feat/takeover", git(repoRoot, "branch", "--show-current"))
    assertEquals(
      "base\ntarget\nlocal-change\n",
      Files.readString(repoRoot.resolve("tracked.txt")),
    )
    assertEquals("target-only change\n", Files.readString(repoRoot.resolve("target-only.txt")))
    assertEquals("staged change\n", Files.readString(repoRoot.resolve("staged.txt")))
    assertEquals("untracked change\n", Files.readString(repoRoot.resolve("untracked.txt")))
    assertContains(git(repoRoot, "status", "--porcelain"), "M  tracked.txt")
    assertContains(git(repoRoot, "status", "--porcelain"), "M  staged.txt")
    assertContains(git(repoRoot, "status", "--porcelain"), "?? untracked.txt")
  }

  @Test
  fun `checkout keeps the staged and unstaged split when no local change conflicts`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-checkout-clean-replay")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("unstaged.txt"), "base\n")
    Files.writeString(repoRoot.resolve("staged.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "checkout", "-b", "feat/takeover")
    Files.writeString(repoRoot.resolve("branch-only.txt"), "branch only\n")
    git(repoRoot, "add", "branch-only.txt")
    git(repoRoot, "commit", "-m", "branch change")
    git(repoRoot, "checkout", "main")
    Files.writeString(repoRoot.resolve("unstaged.txt"), "unstaged change\n")
    Files.writeString(repoRoot.resolve("staged.txt"), "staged change\n")
    git(repoRoot, "add", "staged.txt")

    val result = GitWorkflowGitOperations().checkoutBranch(repoRoot, "feat/takeover")

    assertTrue(result.ok, result.error)
    assertEquals("feat/takeover", git(repoRoot, "branch", "--show-current"))
    val status = git(repoRoot, "status", "--porcelain")
    assertContains(status, "M  staged.txt")
    assertContains(status, " M unstaged.txt")
  }

  @Test
  fun `worktree activity summarizes changed files and diff stat`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-activity")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\ntwo\n")
    Files.writeString(repoRoot.resolve("new.txt"), "new\n")

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(2, result.changedFileSummary?.total)
    assertEquals(1, result.changedFileSummary?.modified)
    assertEquals(1, result.changedFileSummary?.untracked)
    assertEquals(1, result.diffStat?.filesChanged)
    assertEquals(1, result.diffStat?.insertions)
  }

  @Test
  fun `worktree activity reports clean repository with zero activity`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-clean")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(0, result.changedFileSummary?.total)
    assertEquals(0, result.changedFileSummary?.modified)
    assertEquals(0, result.changedFileSummary?.renamed)
    assertEquals(0, result.changedFileSummary?.deleted)
    assertEquals(0, result.changedFileSummary?.untracked)
    assertEquals(emptyList(), result.changedFileSummary?.samplePaths)
    assertEquals(0, result.diffStat?.filesChanged)
    assertEquals(0, result.diffStat?.insertions)
    assertEquals(0, result.diffStat?.deletions)
  }

  @Test
  fun `worktree activity summarizes modified renamed deleted and untracked files`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-status-kinds")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("modified.txt"), "one\n")
    Files.writeString(repoRoot.resolve("rename-before.txt"), "rename me\n")
    Files.writeString(repoRoot.resolve("deleted.txt"), "delete me\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("modified.txt"), "one\ntwo\n")
    git(repoRoot, "mv", "rename-before.txt", "rename-after.txt")
    Files.delete(repoRoot.resolve("deleted.txt"))
    Files.writeString(repoRoot.resolve("untracked.txt"), "new\n")

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(4, result.changedFileSummary?.total)
    assertEquals(1, result.changedFileSummary?.modified)
    assertEquals(1, result.changedFileSummary?.renamed)
    assertEquals(1, result.changedFileSummary?.deleted)
    assertEquals(1, result.changedFileSummary?.untracked)
    assertContains(result.changedFileSummary?.samplePaths.orEmpty(), "modified.txt")
    assertContains(result.changedFileSummary?.samplePaths.orEmpty(), "rename-after.txt")
    assertContains(result.changedFileSummary?.samplePaths.orEmpty(), "untracked.txt")
    assertEquals(3, result.diffStat?.filesChanged)
    assertEquals(1, result.diffStat?.insertions)
    assertEquals(1, result.diffStat?.deletions)
  }

  @Test
  fun `worktree activity drains large status and numstat output before waiting`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-large-drain")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    val changedFiles = 5_000
    (1..changedFiles).forEach { index ->
      Files.writeString(repoRoot.resolve("tracked-$index.txt"), "before\n")
    }
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    (1..changedFiles).forEach { index ->
      Files.writeString(repoRoot.resolve("tracked-$index.txt"), "before\nafter\n")
    }

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(changedFiles, result.changedFileSummary?.total)
    assertEquals(changedFiles, result.changedFileSummary?.modified)
    assertEquals(changedFiles, result.diffStat?.filesChanged)
    assertEquals(changedFiles, result.diffStat?.insertions)
  }

}
