package skillbill.application.featuretask

import org.junit.jupiter.api.io.TempDir
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeSubtaskCommitPreservationTest {
  @TempDir
  lateinit var repo: Path

  @Test
  fun `create and amend exclude unrelated staged content and preserve its unstaged version`() {
    initialize()
    val operations = GitWorkflowGitOperations()
    val created = operations.writeSubtaskCommitPreservingHistory(request(FeatureTaskRuntimeSubtaskCommitCreate))
    assertTrue(created.ok, created.error)
    val original = git("rev-parse", "HEAD")
    assertForeignContent()
    Files.delete(repo.resolve("owned.txt"))
    git("add", "owned.txt")
    val amended = operations.writeSubtaskCommitPreservingHistory(
      request(FeatureTaskRuntimeSubtaskCommitAmend(original, 1, false, false)),
    )
    assertTrue(amended.ok, amended.error)
    assertEquals("2", git("rev-list", "--count", "HEAD"))
    assertEquals("foreign.txt", git("ls-tree", "--name-only", "HEAD"))
    assertForeignContent()
    assertEquals(original, git("rev-parse", identity.checkpointRefName(1)))
  }

  @Test
  fun `commit exception restores unrelated staged content without losing owned edits`() {
    initialize()
    val original = git("rev-parse", "HEAD")
    val operations = object :
      WorkflowGitOperations by GitWorkflowGitOperations(),
      ScopedStagingGitOperationsProvider by GitWorkflowGitOperations() {
      override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
        error("injected commit failure")
      }
    }
    val result = operations.writeSubtaskCommitPreservingHistory(request(FeatureTaskRuntimeSubtaskCommitCreate))
    assertFalse(result.ok)
    assertEquals(original, git("rev-parse", "HEAD"))
    assertForeignContent()
    assertEquals("implementation", git("show", ":owned.txt"))
  }

  @Test
  fun `amend interrupted before identity persistence recovers the sibling without another commit`() {
    initialize()
    val operations = GitWorkflowGitOperations()
    assertTrue(operations.writeSubtaskCommitPreservingHistory(request(FeatureTaskRuntimeSubtaskCommitCreate)).ok)
    val original = git("rev-parse", "HEAD")
    val prior = checkpoint(original, 0)
    Files.writeString(repo.resolve("owned.txt"), "repaired")
    git("add", "owned.txt")
    assertTrue(
      operations.writeSubtaskCommitPreservingHistory(
        request(FeatureTaskRuntimeSubtaskCommitAmend(original, 1, false, false)),
      ).ok,
    )
    val amended = git("rev-parse", "HEAD")
    repeat(2) {
      val recovered = operations.recoveredSubtaskParent(
        RecoveredSubtaskParentRequest(repo, amended, "feature", identity, 1, prior),
      )
      assertTrue(recovered.ok, recovered.error)
      assertEquals(prior.parentSha, recovered.value)
      assertEquals(amended, git("rev-parse", "HEAD"))
      assertEquals("2", git("rev-list", "--count", "HEAD"))
    }
    assertFalse(
      operations.recoveredSubtaskParent(
        RecoveredSubtaskParentRequest(repo, amended, "feature", identity, 1, prior.copy(branch = "foreign")),
      ).ok,
    )
  }

  @Test
  fun `migration refuses an omitted active commit or a foreign branch identity`() {
    initialize()
    val base = git("rev-parse", "HEAD")
    git("commit", "-m", identity.trailer)
    val first = checkpoint(git("rev-parse", "HEAD"), 0)
    Files.writeString(repo.resolve("owned.txt"), "second")
    git("add", "owned.txt")
    git("commit", "-m", identity.trailer)
    val second = checkpoint(git("rev-parse", "HEAD"), 1)
    val operations = GitWorkflowGitOperations()
    assertContains(
      operations.subtaskCommitSpanFailure(
        SubtaskCommitSpanFailureRequest(repo, base, second.commitSha, "feature", identity, listOf(second)),
      ).orEmpty(),
      "every active",
    )
    assertContains(
      operations.subtaskCommitSpanFailure(
        SubtaskCommitSpanFailureRequest(
          repo,
          base,
          second.commitSha,
          "feature",
          identity,
          listOf(first, second.copy(branch = "foreign")),
        ),
      ).orEmpty(),
      "another branch",
    )
    assertNull(
      operations.subtaskCommitSpanFailure(
        SubtaskCommitSpanFailureRequest(repo, base, second.commitSha, "feature", identity, listOf(first, second)),
      ),
    )
    assertEquals(second.commitSha, git("rev-parse", "HEAD"))
  }

  @Test
  fun `migration retains earlier subtask history outside the proven active span`() {
    initialize()
    val base = git("rev-parse", "HEAD")
    git("commit", "-m", "Skill-Bill-Subtask: SKILL-234/1")
    val earlier = git("rev-parse", "HEAD")
    Files.writeString(repo.resolve("owned.txt"), "current")
    git("add", "owned.txt")
    git("commit", "-m", identity.trailer)
    val current = checkpoint(git("rev-parse", "HEAD"), 0)
    assertNull(
      GitWorkflowGitOperations().subtaskCommitSpanFailure(
        SubtaskCommitSpanFailureRequest(repo, base, current.commitSha, "feature", identity, listOf(current)),
      ),
    )
    assertEquals(earlier, current.parentSha)
    assertEquals("3", git("rev-list", "--count", "HEAD"))
  }

  private fun checkpoint(sha: String, sequence: Int) = FeatureTaskRuntimeCheckpointIdentity(
    sequenceNumber = sequence,
    issueKey = identity.issueKey,
    subtaskId = identity.subtaskId,
    checkpointRef = identity.checkpointRefName(sequence),
    branch = "feature",
    phaseId = "audit",
    generation = 0,
    ownedPathDigest = "0".repeat(64),
    ownedPathCount = 1,
    commitSha = sha,
    parentSha = git("rev-parse", "$sha^"),
    recordedAt = "2026-09-08T00:00:00Z",
  )

  @Test
  fun `partial foreign unstage failure restores the original index before returning`() {
    initialize()
    val original = git("rev-parse", "HEAD")
    val real = GitWorkflowGitOperations()
    val operations = object : WorkflowGitOperations by real, ScopedStagingGitOperationsProvider {
      override val scopedStagingOperations = object : ScopedStagingGitOperations by real.scopedStagingOperations {
        override fun unstagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
          real.scopedStagingOperations.unstagePaths(repoRoot, paths)
          return WorkflowGitOperationResult(status = "error", error = "injected partial unstage failure")
        }
      }
    }
    val result = operations.writeSubtaskCommitPreservingHistory(request(FeatureTaskRuntimeSubtaskCommitCreate))
    assertFalse(result.ok)
    assertEquals(original, git("rev-parse", "HEAD"))
    assertForeignContent()
  }

  @Test
  fun `approval follows only a preserved same-tree amendment on the owned branch`() {
    initialize()
    val operations = GitWorkflowGitOperations()
    assertTrue(operations.writeSubtaskCommitPreservingHistory(request(FeatureTaskRuntimeSubtaskCommitCreate)).ok)
    val reviewed = git("rev-parse", "HEAD")
    val reviewedTree = git("rev-parse", "HEAD^{tree}")
    val amended = operations.writeSubtaskCommitPreservingHistory(
      request(FeatureTaskRuntimeSubtaskCommitAmend(reviewed, 1, false, false))
        .copy(allowUnchangedIndex = true, message = "reworded\n\n${identity.trailer}"),
    )
    assertTrue(amended.ok, amended.error)
    val current = git("rev-parse", "HEAD")
    val record = checkpoint(current, 1)
    assertTrue(
      operations.reviewIdentityStillAuthoritative(
        repo,
        reviewed,
        current,
        reviewedTree,
        reviewedTree,
        record,
        identity,
      ),
    )
    assertFalse(
      operations.reviewIdentityStillAuthoritative(
        repo,
        reviewed,
        current,
        reviewedTree,
        reviewedTree,
        record.copy(branch = "foreign"),
        identity,
      ),
    )
    git("update-ref", record.checkpointRef, record.parentSha!!)
    assertFalse(
      operations.reviewIdentityStillAuthoritative(
        repo,
        reviewed,
        current,
        reviewedTree,
        reviewedTree,
        record,
        identity,
      ),
    )
  }

  private fun assertForeignContent() {
    assertEquals("base", git("show", "HEAD:foreign.txt"))
    assertEquals("staged", git("show", ":foreign.txt"))
    assertEquals("unstaged", Files.readString(repo.resolve("foreign.txt")))
  }

  private fun initialize() {
    git("init", "-b", "feature")
    git("config", "user.name", "Test")
    git("config", "user.email", "test@example.test")
    Files.writeString(repo.resolve("owned.txt"), "base")
    Files.writeString(repo.resolve("foreign.txt"), "base")
    git("add", ".")
    git("commit", "-m", "base")
    Files.writeString(repo.resolve("foreign.txt"), "staged")
    git("add", "foreign.txt")
    Files.writeString(repo.resolve("foreign.txt"), "unstaged")
    Files.writeString(repo.resolve("owned.txt"), "implementation")
    git("add", "owned.txt")
  }

  private val identity = FeatureTaskRuntimeSubtaskCommitIdentity("SKILL-235", "1")

  private fun request(decision: FeatureTaskRuntimeSubtaskCommitDecision) = SubtaskCommitPreservationRequest(
    repoRoot = repo,
    decision = decision,
    identity = identity,
    message = "implementation\n\n${identity.trailer}",
    allowUnchangedIndex = false,
    ownedPaths = listOf("owned.txt"),
    record = {},
  )

  private fun git(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + arguments)
      .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    assertEquals(0, process.waitFor(), output)
    return output
  }
}
