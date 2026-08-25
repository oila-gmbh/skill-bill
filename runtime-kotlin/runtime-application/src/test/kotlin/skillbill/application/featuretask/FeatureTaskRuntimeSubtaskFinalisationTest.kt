package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecordSidecar
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
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
private const val GIT_TIMEOUT_SECONDS = 60L

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

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(result)
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

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
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

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
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

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
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
    val other = tempRoot("skillbill-finalisation-other")
    git(other, "clone", repo.remote.toString(), other.resolve("clone").toString())
    val clone = other.resolve("clone")
    configureIdentity(clone)
    git(clone, "checkout", "-b", branch)
    Files.writeString(clone.resolve("remote-only.txt"), "someone else\n")
    git(clone, "add", "remote-only.txt")
    git(clone, "commit", "-m", "remote moved")
    git(clone, "push", "-u", "origin", branch)
    val remoteTip = git(repo.remote, "rev-parse", branch)

    val blocked = assertIs<FeatureTaskRuntimeSubtaskFinalisationBlocked>(
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
    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
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
    val blocked = assertIs<FeatureTaskRuntimeSubtaskFinalisationBlocked>(
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

  // The bug: recording the durable pointer after the push leaves a failed push blocked with HEAD at
  // the finalisation commit while the pointer still names the pre-amend sha, so the re-entry resolves
  // Create against a clean index and the subtask can never finish.
  @Test
  fun `the durable pointer is recorded before the push and a failed recording blocks without pushing`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    val remoteTipsSeenWhileRecording = mutableListOf<String>()
    atRecordTime = { remoteTipsSeenWhileRecording += remoteBranchTip(repo.remote) }

    Files.writeString(repo.root.resolve("owned.txt"), "final\n")
    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
      finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt")),
    )

    assertEquals(listOf(finalised.commitSha), recordedCommits)
    assertEquals(listOf(""), remoteTipsSeenWhileRecording, "the pointer must be durable before the push")

    val second = repoWithRemote()
    Files.writeString(second.root.resolve("owned.txt"), "work\n")
    recordedCommits.clear()
    atRecordTime = {}
    recordFailure = "needs_human: the workflow row was absent"

    val blocked = assertIs<FeatureTaskRuntimeSubtaskFinalisationBlocked>(
      finalise(second, durableCommitSha = null, paths = listOf("owned.txt")),
    )

    assertEquals("needs_human: the workflow row was absent", blocked.reason)
    assertEquals(recordedCommits.single(), git(second.root, "rev-parse", "HEAD"), "the commit must stand")
    assertEquals("", remoteBranchTip(second.remote), "an unrecorded commit must not be published")
  }

  // The bug: a reopened subtask whose first checkpoint amends its published commit leaves the remote
  // tip off the new lineage; without a lease the finalisation push is rejected, and treating that as a
  // plain push failure strands a subtask that legitimately owns the commit it rewrote.
  @Test
  fun `a reopened subtask that checkpointed over its published commit still leases its push`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "published\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    git(repo.root, "push", "-u", "origin", branch)
    val publishedSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve("owned.txt"), "reopened checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "--amend", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    val commitsBefore = commitCount(repo.root)

    Files.writeString(repo.root.resolve("owned.txt"), "reopened final\n")
    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
      finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt"), sequenceNumber = 1),
    )

    assertTrue(finalised.forcedWithLease, "the remote still carries the pre-checkpoint commit")
    assertEquals(commitsBefore, commitCount(repo.root), "the subtask must still end with one commit")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
    assertTrue(finalised.commitSha != publishedSha)
  }

  // Clean implementation worktree (only governed spec dirt): finalisation must refuse rather than
  // amend/publish the unchanged checkpoint tree as if deliverable work landed.
  @Test
  fun `a finalisation with nothing stageable is refused instead of publishing the checkpoint tree`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec only\n")

    val blocked = assertIs<FeatureTaskRuntimeSubtaskFinalisationBlocked>(
      finalise(repo, durableCommitSha = checkpointSha, paths = listOf(".feature-specs/$issueKey/spec.md")),
    )

    assertContains(blocked.reason, "nothing to stage")
    assertEquals(checkpointSha, git(repo.root, "rev-parse", "HEAD"), "HEAD must be untouched")
    assertEquals("", git(repo.root, "diff", "--cached", "--name-only"), "nothing may be left staged")
    assertEquals("", remoteBranchTip(repo.remote), "a refused finalisation must not publish")
  }

  // Incomplete agent changed_paths must not strand validate repairs (or operator edits): finalisation
  // sweeps every dirty non-ignored implementation path, while still leaving governed specs local.
  @Test
  fun `a partial changed_paths list still commits every dirty implementation path`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec\n")
    git(repo.root, "add", ".feature-specs")
    git(repo.root, "commit", "-m", "operator committed the spec")
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec dirt ok\n")
    Files.writeString(repo.root.resolve("owned.txt"), "enumerated\n")
    Files.writeString(repo.root.resolve("leftover.txt"), "validate repair\n")

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
      finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
    )

    val committed = git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
      .lines().filter { it.isNotBlank() }.sorted()
    assertEquals(listOf("leftover.txt", "owned.txt"), committed)
    assertEquals(
      ".feature-specs/$issueKey/spec.md",
      git(repo.root, "diff", "--name-only"),
      "governed specs stay dirty locally",
    )
    assertEquals(finalised.commitSha, remoteBranchTip(repo.remote))
  }

  @Test
  fun `an untracked nested directory is committed file-by-file rather than left behind`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve("owned/nested"))
    Files.writeString(repo.root.resolve("owned/nested/One.kt"), "one\n")
    Files.writeString(repo.root.resolve("owned/nested/Two.kt"), "two\n")

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(
      finalise(repo, durableCommitSha = null, paths = emptyList()),
    )

    val committed = git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
      .lines().filter { it.isNotBlank() }.sorted()
    assertEquals(listOf("owned/nested/One.kt", "owned/nested/Two.kt"), committed)
    assertEquals(finalised.commitSha, remoteBranchTip(repo.remote))
    assertEquals("", git(repo.root, "status", "--porcelain"), "no deliverable dirt may remain")
  }

  @Test
  fun `prose delimited commit subject is accepted and sha sidecar is consistent`() {
    val subject = "SKILL-208: delimited commit subject"
    val prose = """
      Work complete.
      <<<COMMIT_SUBJECT>>>
      $subject
      <<<END_COMMIT_SUBJECT>>>
    """.trimIndent()
    val read = FeatureTaskRuntimeSubtaskFinalisation.readHandoffFromProse(prose)
    val valid = assertIs<FeatureTaskRuntimeCommitPushHandoffValid>(read)
    assertEquals(subject, valid.handoff.outcomeMessage)
    val sidecar = FeatureTaskRuntimeSubtaskFinalisation.withCommitShaSidecar(subject, "c".repeat(40))
    assertEquals(subject, sidecar.commitSubject)
    assertEquals("c".repeat(40), sidecar.commitSha)
  }

  @Test
  fun `commitShaFromPhaseRecords prefers runtime owned sidecar over legacy envelope`() {
    val legacyEnvelope = """{"produced_outputs":{"commit_push_result":{"commit_sha":"legacy-sha"}}}"""
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      status = "completed",
      attemptCount = 1,
      startedAt = "2026-01-01T00:00:00Z",
      resolvedAgentId = "committer",
      outputArtifact = legacyEnvelope,
      runtimeOwnedSidecar = FeatureTaskRuntimePhaseRecordSidecar(
        commitSubject = "SKILL-208: subject",
        commitSha = "sidecar-sha",
      ),
    )
    val resolved = record.runtimeOwnedSidecar?.commitSha
      ?: legacyEnvelope.let(JsonSupport::parseObjectOrNull)
        ?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)
        ?.commitShaFromPhasePayload()
    assertEquals("sidecar-sha", resolved)
  }

  @Test
  fun `blank delimited commit subject is rejected before any git write`() {
    val blank = FeatureTaskRuntimeSubtaskFinalisation.readHandoffFromProse(
      "<<<COMMIT_SUBJECT>>>   <<<END_COMMIT_SUBJECT>>>",
    )
    assertContains(
      assertIs<FeatureTaskRuntimeCommitPushHandoffInvalid>(blank).reason,
      "<<<COMMIT_SUBJECT>>>",
    )
  }

  @Test
  fun `a blank outcome message is rejected before any git write`() {
    val blank = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(
      envelope(message = "   ", paths = listOf("owned.txt")),
    )
    val absentPaths = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(
      mapOf(
        "produced_outputs" to mapOf(
          "commit_push_result" to mapOf("message" to agentSubject),
        ),
      ),
    )

    assertContains(
      assertIs<FeatureTaskRuntimeCommitPushHandoffInvalid>(blank).reason,
      "`commit_push_result.message` is missing or blank",
    )
    assertIs<FeatureTaskRuntimeCommitPushHandoffValid>(absentPaths)
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
  private val recordedCommits = mutableListOf<String>()
  private var recordFailure: String? = null
  private var atRecordTime: (String) -> Unit = {}

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
    recordCommit = { sha, _ ->
      recordedCommits += sha
      atRecordTime(sha)
      recordFailure
    },
  ).finalise(
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
    val clone = tempRoot("skillbill-finalisation-stale").resolve("clone")
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
    val base = tempRoot("skillbill-finalisation")
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

  /**
   * Repository-scoped, because the assertions run against real git objects written by both this helper
   * and the production adapter, and the adapter inherits whatever global configuration the developer or
   * CI image carries. A global `commit.gpgsign`, `core.hooksPath`, or unexpected `push.default` would
   * otherwise fail every test here for reasons that have nothing to do with finalisation.
   */
  private fun configureIdentity(repoRoot: Path) {
    val hooks = Files.createDirectories(repoRoot.resolve(".git/skillbill-empty-hooks"))
    git(repoRoot, "config", "user.email", "runtime@skill-bill.test")
    git(repoRoot, "config", "user.name", "Skill Bill Runtime")
    git(repoRoot, "config", "commit.gpgsign", "false")
    git(repoRoot, "config", "tag.gpgsign", "false")
    git(repoRoot, "config", "core.hooksPath", hooks.toString())
    git(repoRoot, "config", "push.default", "simple")
  }

  private val tempRoots = mutableListOf<Path>()

  private fun tempRoot(prefix: String): Path = Files.createTempDirectory(prefix).also(tempRoots::add)

  @AfterTest
  fun deleteFixtureRepositories() {
    tempRoots.reversed().forEach { root ->
      root.toFile().walkBottomUp().forEach { it.delete() }
    }
    tempRoots.clear()
  }

  private fun commitCount(repoRoot: Path): Int = git(repoRoot, "rev-list", "--count", "HEAD").toInt()

  /** The remote's tip for the subtask branch, blank while the remote carries no such branch yet. */
  private fun remoteBranchTip(remote: Path): String =
    git(remote, "for-each-ref", "--format=%(objectname)", "refs/heads/$branch")

  private fun git(repoRoot: Path, vararg args: String): String {
    val builder = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args.toList())
      .redirectErrorStream(true)
    builder.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
    builder.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText().trim()
    // Bounded so a git invocation waiting on a credential, editor, or signing prompt fails the test
    // instead of hanging the suite until the build times out.
    if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor()
      error("git ${args.joinToString(" ")} did not finish within ${GIT_TIMEOUT_SECONDS}s: $output")
    }
    val exitCode = process.exitValue()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private fun envelope(message: String, paths: List<String>): Map<String, Any?> = mapOf(
    "produced_outputs" to mapOf(
      "commit_push_result" to mapOf("message" to message, "changed_paths" to paths),
    ),
  )

  private fun realGitOps(): WorkflowGitOperations = skillbill.infrastructure.fs.GitWorkflowGitOperations()
}
