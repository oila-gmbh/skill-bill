package skillbill.application.featuretask

import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every assertion reads the git objects and the remote, never the message builder's return string:
 * what this subtask promises is a property of the repository after finalisation, and an in-memory
 * assertion would pass for a message that never reached a commit.
 */
class FeatureTaskRuntimeSubtaskFinalisationTest {
  private val issueKey = "SKILL-190"
  private val subtaskId = "5"
  private val branch = "feat/skill-190-finalisation"
  private val identity = FeatureTaskRuntimeSubtaskCommitIdentity(issueKey, subtaskId)
  private val agentSubject = "SKILL-190: runtime-owned subtask finalisation"

  @Test
  fun `a checkpointed subtask ends with one amended commit carrying the agent subject and trailer`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    val commitsBefore = commitCount(repo.root)

    Files.writeString(repo.root.resolve("owned.txt"), "final\n")
    val result = finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt"))

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalisationResult.Finalised>(result)
    assertEquals(commitsBefore, commitCount(repo.root), "the amend must not add a second subtask commit")
    assertEquals(git(repo.root, "rev-parse", "HEAD"), finalised.commitSha)
    assertEquals(agentSubject, git(repo.root, "log", "-1", "--format=%s"))
    assertEquals(
      "$issueKey/$subtaskId",
      git(repo.root, "log", "-1", "--format=%(trailers:key=Skill-Bill-Subtask,valueonly)").trim(),
    )
    assertContains(git(repo.root, "log", "-1", "--format=%b"), "phase=commit_push")
    assertEquals("final\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `a subtask that never checkpointed ends with one created commit carrying the enumerated work`() {
    val repo = repoWithRemote()
    val baseSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve("owned.txt"), "only-work\n")

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalisationResult.Finalised>(
      finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
    )

    assertEquals(baseSha, git(repo.root, "rev-parse", "HEAD~1"))
    assertEquals(agentSubject, git(repo.root, "log", "-1", "--format=%s"))
    assertEquals("only-work\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `an enumerated feature-spec path is excluded from the commit and left dirty`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec\n")
    git(repo.root, "add", ".feature-specs")
    git(repo.root, "commit", "-m", "operator committed the spec")
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec edited by the run\n")
    Files.writeString(repo.root.resolve("owned.txt"), "work\n")

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalisationResult.Finalised>(
      finalise(repo, durableCommitSha = null, paths = listOf("owned.txt", ".feature-specs/$issueKey/spec.md")),
    )

    assertEquals(listOf("owned.txt"), finalised.stagedPaths)
    assertEquals(listOf(".feature-specs/$issueKey/spec.md"), finalised.excludedSpecPaths)
    assertEquals("owned.txt", git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"))
    assertEquals(
      ".feature-specs/$issueKey/spec.md",
      git(repo.root, "diff", "--name-only"),
      "the governed spec must stay modified in the working tree",
    )
    assertEquals("", git(repo.root, "diff", "--cached", "--name-only"), "and must never reach the index")
    assertTrue(records.any { it.contains("cause=governed feature specs are workflow input") })
  }

  @Test
  fun `a foreign HEAD commit is never amended`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("human.txt"), "hand written\n")
    git(repo.root, "add", "human.txt")
    git(repo.root, "commit", "-m", "human: unrelated fix")
    val humanSha = git(repo.root, "rev-parse", "HEAD")
    val humanTree = git(repo.root, "cat-file", "-p", humanSha)
    Files.writeString(repo.root.resolve("owned.txt"), "work\n")

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalisationResult.Finalised>(
      finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
    )

    assertEquals(humanSha, git(repo.root, "rev-parse", "HEAD~1"))
    assertEquals(humanTree, git(repo.root, "cat-file", "-p", humanSha), "the human commit must be byte-identical")
    assertEquals("hand written\n", git(repo.root, "show", "$humanSha:human.txt") + "\n")
    assertTrue(finalised.commitSha != humanSha)
  }

  @Test
  fun `the normal path never force-pushes over a remote that moved`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "work\n")
    val other = Files.createTempDirectory("skillbill-finalisation-other")
    git(other, "clone", repo.remote.toString(), other.resolve("clone").toString())
    val clone = other.resolve("clone")
    configureIdentity(clone)
    git(clone, "checkout", "-b", branch)
    Files.writeString(clone.resolve("remote-only.txt"), "someone else\n")
    git(clone, "add", "remote-only.txt")
    git(clone, "commit", "-m", "remote moved")
    git(clone, "push", "-u", "origin", branch)
    val remoteTip = git(repo.remote, "rev-parse", branch)

    val blocked = assertIs<FeatureTaskRuntimeSubtaskFinalisationResult.Blocked>(
      finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
    )

    assertContains(blocked.reason, "could not be pushed")
    assertEquals(remoteTip, git(repo.remote, "rev-parse", branch), "a rejected normal push must not move the remote")
  }

  @Test
  fun `a reopened published subtask force-pushes under a matching lease and aborts under a stale one`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "published\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    git(repo.root, "push", "-u", "origin", branch)
    val publishedSha = git(repo.root, "rev-parse", "HEAD")
    val commitsBefore = commitCount(repo.root)

    Files.writeString(repo.root.resolve("owned.txt"), "reopened\n")
    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalisationResult.Finalised>(
      finalise(repo, durableCommitSha = publishedSha, paths = listOf("owned.txt")),
    )

    assertTrue(finalised.forcedWithLease)
    assertEquals(commitsBefore, commitCount(repo.root), "a reopened published subtask still ends with one commit")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
    assertTrue(records.any { it.contains("git push --force-with-lease") })

    val staleRepo = staleLease(repo)
    records.clear()
    Files.writeString(staleRepo.root.resolve("owned.txt"), "reopened again\n")
    val remoteTip = git(staleRepo.remote, "rev-parse", branch)
    val blocked = assertIs<FeatureTaskRuntimeSubtaskFinalisationResult.Blocked>(
      finalise(
        staleRepo,
        durableCommitSha = git(staleRepo.root, "rev-parse", "HEAD"),
        paths = listOf("owned.txt"),
        sequenceNumber = 1,
      ),
    )

    assertContains(blocked.reason, "the remote moved under this run")
    assertEquals(remoteTip, git(staleRepo.remote, "rev-parse", branch), "a stale lease must leave the remote alone")
    assertTrue(records.any { it.contains("cause=the lease was rejected") })
  }

  @Test
  fun `a blank outcome message is rejected before any git write`() {
    val blank = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(
      envelope(message = "   ", paths = listOf("owned.txt")),
    )
    val absentPaths = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(mapOf("produced_outputs" to mapOf(
      "commit_push_result" to mapOf("message" to agentSubject),
    )))

    assertContains(
      assertIs<FeatureTaskRuntimeCommitPushHandoffResult.Invalid>(blank).reason,
      "`commit_push_result.message` is missing or blank",
    )
    assertContains(
      assertIs<FeatureTaskRuntimeCommitPushHandoffResult.Invalid>(absentPaths).reason,
      "`commit_push_result.changed_paths` is absent",
    )
  }

  @Test
  fun `the captured sha is the value every commit_sha reader resolves`() {
    val withSha = FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(
      envelope(message = agentSubject, paths = listOf("owned.txt")),
      commitSha = "b".repeat(40),
    )

    assertEquals("b".repeat(40), withSha.commitShaFromPhasePayload())
  }

  private val records = mutableListOf<String>()

  private data class Fixture(val root: Path, val remote: Path)

  private fun finalise(
    fixture: Fixture,
    durableCommitSha: String?,
    paths: List<String>,
    sequenceNumber: Int = 0,
  ): FeatureTaskRuntimeSubtaskFinalisationResult = FeatureTaskRuntimeSubtaskFinalisation(
    gitOperations = realGitOps(),
    repoRoot = fixture.root,
    record = { records += it },
  ).finalise(
    branch = branch,
    identity = identity,
    durableCommitSha = durableCommitSha,
    sequenceNumber = sequenceNumber,
    handoff = FeatureTaskRuntimeCommitPushHandoff(outcomeMessage = agentSubject, changedPaths = paths),
    metadata = FeatureTaskRuntimeCheckpointMetadata(
      phaseId = "commit_push",
      loopId = null,
      generation = 0,
      branch = branch,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
    ),
  )

  /** Moves the remote out from under the working repository without the repository observing it. */
  private fun staleLease(fixture: Fixture): Fixture {
    val clone = Files.createTempDirectory("skillbill-finalisation-stale").resolve("clone")
    git(clone.parent, "clone", fixture.remote.toString(), clone.toString())
    configureIdentity(clone)
    git(clone, "checkout", branch)
    Files.writeString(clone.resolve("remote-only.txt"), "someone else\n")
    git(clone, "add", "remote-only.txt")
    git(clone, "commit", "-m", "remote moved after the lease was taken")
    git(clone, "push", "origin", branch)
    return fixture
  }

  private fun repoWithRemote(): Fixture {
    val base = Files.createTempDirectory("skillbill-finalisation")
    val remote = base.resolve("remote.git")
    git(base, "init", "--bare", "--initial-branch=main", remote.toString())
    val root = base.resolve("work")
    Files.createDirectories(root)
    git(root, "init", "--initial-branch=main")
    configureIdentity(root)
    git(root, "remote", "add", "origin", remote.toString())
    Files.writeString(root.resolve("README.md"), "base\n")
    git(root, "add", "README.md")
    git(root, "commit", "-m", "base")
    git(root, "push", "-u", "origin", "main")
    git(root, "checkout", "-b", branch)
    return Fixture(root = root, remote = remote)
  }

  private fun configureIdentity(repoRoot: Path) {
    git(repoRoot, "config", "user.email", "runtime@skill-bill.test")
    git(repoRoot, "config", "user.name", "Skill Bill Runtime")
  }

  private fun commitCount(repoRoot: Path): Int = git(repoRoot, "rev-list", "--count", "HEAD").toInt()

  private fun git(repoRoot: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args.toList())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private fun envelope(message: String, paths: List<String>): Map<String, Any?> = mapOf(
    "produced_outputs" to mapOf(
      "commit_push_result" to mapOf("message" to message, "changed_paths" to paths),
    ),
  )

  private fun realGitOps(): WorkflowGitOperations = GitWorkflowGitOperations()
}
