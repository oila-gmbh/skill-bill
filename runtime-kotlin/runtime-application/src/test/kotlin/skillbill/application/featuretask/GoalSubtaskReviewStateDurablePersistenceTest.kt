package skillbill.application.featuretask

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskPauseRelease
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-014 and AC-016: the pause data is only useful if it survives the process that wrote it. These
 * drive the real recorder against a workflow store and read the state back, rather than asserting on
 * objects the test constructs itself.
 */
class GoalSubtaskReviewStateDurablePersistenceTest {
  private val workflowId = "wftr-skill142-1"

  @Test
  fun `an operator decision and its consumption survive a reload`() {
    val recorder = recorderWith(pausedState())

    val decided = recorder.updateReviewState(workflowId) {
      it.applyOperatorDecision(GoalSubtaskOperatorDecision.RETRY_FIX)
    }
    assertNotNull(decided)
    assertEquals(
      GoalSubtaskPauseRelease.RETRY_FIX,
      recorder.reviewState(workflowId)?.pauseRelease,
      "A resumed run must read the grant back off durable state.",
    )

    recorder.updateReviewState(workflowId) { it.consumeOperatorDecision() }
    val afterConsume = assertNotNull(recorder.reviewState(workflowId))
    assertNull(
      afterConsume.operatorDecision,
      "A consumed grant must be cleared durably; otherwise every resume re-grants an unbudgeted iteration.",
    )
    assertTrue(afterConsume.retryReviewPending, "The granted round is in flight until it is re-reviewed.")
  }

  @Test
  fun `accept_and_advance and abandon_subtask are readable releases, not write-only records`() {
    listOf(
      GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE to GoalSubtaskPauseRelease.ADVANCE,
      GoalSubtaskOperatorDecision.ABANDON_SUBTASK to GoalSubtaskPauseRelease.ABANDON,
    ).forEach { (decision, expected) ->
      val recorder = recorderWith(pausedState())
      recorder.updateReviewState(workflowId) { it.applyOperatorDecision(decision) }
      assertEquals(
        expected,
        recorder.reviewState(workflowId)?.pauseRelease,
        "${decision.wireValue} must release the pause it answers.",
      )
    }
  }

  @Test
  fun `the resolved tier, deciding rule, and remediation base sha all survive a reload`() {
    val recorder = recorderWith(pausedState())

    recorder.updateReviewState(workflowId) {
      it.copy(
        resolvedTier = CodeReviewExecutionMode.INLINE,
        decidingRule = "auto_mode_by_pass_number:pass_n_inline",
      )
    }
    recorder.updateReviewState(workflowId) { it.copy(remediationBaseSha = "b".repeat(40)) }

    val reloaded = assertNotNull(recorder.reviewState(workflowId))
    assertEquals(CodeReviewExecutionMode.INLINE, reloaded.resolvedTier)
    assertEquals("auto_mode_by_pass_number:pass_n_inline", reloaded.decidingRule)
    assertEquals("b".repeat(40), reloaded.remediationBaseSha)
  }

  @Test
  fun `resume reuses the baseline and consumed pass count exactly`() {
    val recorder = recorderWith(pausedState())

    val reloaded = assertNotNull(recorder.reviewState(workflowId))
    assertEquals("a".repeat(40), reloaded.reviewBaseSha, "review_base_sha is immutable across the pause.")
    assertEquals(listOf("scratch/untracked.txt"), reloaded.baselineUntrackedPaths)
    assertEquals(2, reloaded.completedPassCount)
    assertNull(reloaded.reservedPassNumber, "A consumed pass must never be re-reserved by a plain resume.")
  }

  private fun recorderWith(
    state: GoalSubtaskReviewState,
    repository: InMemoryRuntimeWorkflowRepository = InMemoryRuntimeWorkflowRepository(),
    goalBranch: String = "feat/SKILL-142",
    checkpointIdentities: List<FeatureTaskRuntimeCheckpointIdentity> = emptyList(),
  ): FeatureTaskRuntimeGoalContinuationRecorder {
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    val artifactsPatch = linkedMapOf<String, Any?>(
      FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-142",
        subtaskId = 5,
        suppressPr = true,
        goalBranch = goalBranch,
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).toArtifactMap(),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap(),
      GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to state.passResults.associate { result ->
        result.passNumber.toString() to """{"phase_id":"review","status":"completed"}"""
      },
    )
    if (checkpointIdentities.isNotEmpty()) {
      artifactsPatch[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY] =
        featureTaskRuntimeCheckpointIdentitiesToArtifact(checkpointIdentities)
    }
    val seeded = engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "review",
        stepUpdates = null,
        artifactsPatch = artifactsPatch,
        sessionId = "fis-001",
      ),
    ).toRecord()
    repository.saveFeatureTaskRuntimeWorkflow(seeded)
    return FeatureTaskRuntimeGoalContinuationRecorder(
      RuntimeFakeDatabaseSessionFactory(repository),
      testWorkflowSnapshotValidator,
    )
  }

  private data class Skill15GitFixture(
    val repoRoot: Path,
    val parent: String,
    val orphanedBase: String,
  )

  private data class CommittedUnrecordedFixture(
    val repoRoot: Path,
    val parent: String,
    val checkpointSha: String,
  )

  private data class SkipRecordedDescendantFixture(
    val repoRoot: Path,
    val parent: String,
    val checkpointSha: String,
    val skipRecordedTip: String,
  )

  private data class UnreachableGitFixture(
    val repoRoot: Path,
    val unreachable: String,
  )

  private fun skill15GitFixture(): Skill15GitFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-skill15")
    val remoteRoot = Files.createTempDirectory("skillbill-durable-skill15-remote")
    git(remoteRoot, "init", "--bare")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    git(repoRoot, "remote", "add", "origin", remoteRoot.toString())
    Files.writeString(repoRoot.resolve("tracked.txt"), "root\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "root")
    git(repoRoot, "push", "-u", "origin", "main")
    git(repoRoot, "checkout", "-b", "feat/skill-15")
    Files.writeString(repoRoot.resolve("tracked.txt"), "parent\n")
    git(repoRoot, "commit", "-am", "parent")
    val parent = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "sibling-a\n")
    git(repoRoot, "commit", "-am", "sibling-a")
    val orphanedBase = git(repoRoot, "rev-parse", "HEAD")
    git(repoRoot, "reset", "--hard", parent)
    Files.writeString(repoRoot.resolve("tracked.txt"), "sibling-b\n")
    git(repoRoot, "commit", "-am", "sibling-b")
    return Skill15GitFixture(repoRoot, parent, orphanedBase)
  }

  private fun committedUnrecordedFixture(): CommittedUnrecordedFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-unrecorded")
    git(repoRoot, "init", "-b", "feat/skill-15")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("tracked.txt"), "parent\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "parent")
    val parent = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "checkpoint\n")
    git(repoRoot, "commit", "-am", "chore(SKILL-176): remediation checkpoint")
    val checkpointSha = git(repoRoot, "rev-parse", "HEAD")
    return CommittedUnrecordedFixture(repoRoot, parent, checkpointSha)
  }

  private fun skipRecordedDescendantFixture(): SkipRecordedDescendantFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-skip-descendant")
    git(repoRoot, "init", "-b", "feat/skill-15")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("tracked.txt"), "parent\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "parent")
    val parent = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "checkpoint\n")
    git(repoRoot, "commit", "-am", "chore(SKILL-176): remediation checkpoint")
    val checkpointSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "skip-tip\n")
    git(repoRoot, "commit", "-am", "skip-recorded tip ahead of identity")
    val skipRecordedTip = git(repoRoot, "rev-parse", "HEAD")
    return SkipRecordedDescendantFixture(repoRoot, parent, checkpointSha, skipRecordedTip)
  }

  private fun unreachableOnlyGitFixture(): UnreachableGitFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-unreachable")
    git(repoRoot, "init", "-b", "feat/orphan-goal")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("tracked.txt"), "goal\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "goal tip")
    git(repoRoot, "checkout", "--orphan", "unrelated-root")
    val prior = git(repoRoot, "ls-files").lines().filter { it.isNotBlank() }
    if (prior.isNotEmpty()) {
      git(repoRoot, *(listOf("rm", "-f", "--") + prior).toTypedArray())
    }
    Files.writeString(repoRoot.resolve("other.txt"), "unrelated\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "unrelated root")
    val unreachable = git(repoRoot, "rev-parse", "HEAD")
    git(repoRoot, "checkout", "feat/orphan-goal")
    git(repoRoot, "branch", "-D", "unrelated-root")
    return UnreachableGitFixture(repoRoot, unreachable)
  }

  private fun git(repoRoot: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args.toList())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private fun pausedState(): GoalSubtaskReviewState {
    val initial = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = listOf("scratch/untracked.txt"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    val passOne = initial.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(
        GoalSubtaskReviewCompactFinding(
          severity = "blocker",
          label = "GoalRunnerPolicy",
          text = "a Blocker the remediation pass must disposition",
          findingId = "F-001",
        ),
      ),
    )
    return passOne.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = emptyList(),
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition(
          findingId = "F-001",
          verdict = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED,
          evidence = listOf("still reproduces at the same seam"),
        ),
      ),
      // Since SKILL-157 no pass count mints the pause; the operator control is applied directly.
    ).copy(disposition = GoalSubtaskReviewDisposition.PAUSED)
  }

  @Test
  fun `a crash mid-remediation resumes the same reserved high pass without allocating another`() {
    val reservedNinth = deepRemediationState(completedPasses = 8).reserveNextPass()
    val recorder = recorderWith(reservedNinth)

    val reloaded = assertNotNull(recorder.reviewState(workflowId))
    assertEquals(9, reloaded.reservedPassNumber, "Resume must reuse the pass the crashed attempt reserved.")
    assertEquals(8, reloaded.completedPassCount)
    assertEquals(
      (1..8).toList(),
      reloaded.passResults.map { it.passNumber },
      "Watermarks must stay contiguous across the crash.",
    )

    val reReserved = recorder.reserveGoalReviewPass(workflowId)
    val inFlight = assertIs<GoalSubtaskReviewPassInFlight>(reReserved)
    assertEquals(9, inFlight.state.reservedPassNumber, "A resumed reservation is reused, never re-allocated.")
  }

  @Test
  fun `unreachable remediation base recovers and persists the ancestor with evidence`() {
    val fixture = skill15GitFixture()
    val orphaned = fixture.orphanedBase
    val parent = fixture.parent
    val state = deepRemediationState(completedPasses = 2)
      .reserveNextPass()
      .copy(remediationBaseSha = orphaned)
    val repository = InMemoryRuntimeWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")

    val prepared = recorder.buildGoalReviewInput(
      workflowId,
      GitWorkflowGitOperations(),
      fixture.repoRoot,
    )

    assertIs<GoalSubtaskReviewInputReady>(prepared, prepared.toString())
    val reloaded = assertNotNull(recorder.reviewState(workflowId))
    assertEquals(parent, reloaded.remediationBaseSha, "durable remediation_base_sha must repoint to the ancestor")
    assertEquals("a".repeat(40), reloaded.reviewBaseSha, "immutable review base must stay untouched")
    val artifacts = repository.taskRuntimeArtifacts(workflowId)
    @Suppress("UNCHECKED_CAST")
    val evidence = artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as List<Map<String, Any?>>
    val entry = evidence.single()
    assertEquals(orphaned, entry["original_sha"])
    assertEquals(parent, entry["replacement_sha"])
    assertEquals("remediation_base_sha", entry["repointed_field"])
    assertEquals("base_not_ancestor", entry["failure_reason"])
    assertContains(entry["failure_message"].toString(), orphaned)
    assertEquals("feat/skill-15", entry["goal_branch"])
  }

  @Test
  fun `recovery gate allows completed passes and reserved remediation while refusing terminal dispositions`() {
    val fixture = skill15GitFixture()
    val remediationState = deepRemediationState(completedPasses = 2)
      .reserveNextPass()
      .copy(remediationBaseSha = fixture.orphanedBase)
    assertIs<GoalSubtaskReviewInputReady>(
      recorderWith(remediationState, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        GitWorkflowGitOperations(),
        fixture.repoRoot,
      ),
    )

    val zeroPassState = GoalSubtaskReviewState.initial(
      reviewBaseSha = fixture.orphanedBase,
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass()
    assertIs<GoalSubtaskReviewInputReady>(
      recorderWith(zeroPassState, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        GitWorkflowGitOperations(),
        fixture.repoRoot,
      ),
    )

    val paused = remediationState.copy(disposition = GoalSubtaskReviewDisposition.PAUSED)
    assertIs<GoalSubtaskReviewInputBlocked>(
      recorderWith(paused, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        GitWorkflowGitOperations(),
        fixture.repoRoot,
      ),
    )

    val capped = deepRemediationState(completedPasses = 1).copy(
      disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED,
      remediationBaseSha = fixture.orphanedBase,
    )
    assertIs<GoalSubtaskReviewInputBlocked>(
      recorderWith(capped, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        GitWorkflowGitOperations(),
        fixture.repoRoot,
      ),
    )
  }

  @Test
  fun `reachable bases take no recovery path and keep byte-identical review input`() {
    val fixture = skill15GitFixture()
    val state = deepRemediationState(completedPasses = 2)
      .reserveNextPass()
      .copy(remediationBaseSha = fixture.parent)
    val repository = InMemoryRuntimeWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")
    val git = GitWorkflowGitOperations()

    val first = assertIs<GoalSubtaskReviewInputReady>(
      recorder.buildGoalReviewInput(workflowId, git, fixture.repoRoot),
    )
    val second = assertIs<GoalSubtaskReviewInputReady>(
      recorder.buildGoalReviewInput(workflowId, git, fixture.repoRoot),
    )

    assertEquals(first.input.reviewText, second.input.reviewText)
    assertEquals(first.input.deltaDigest, second.input.deltaDigest)
    assertNull(
      repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
      "reachable resume must write no recovery evidence",
    )
    assertEquals(fixture.parent, recorder.reviewState(workflowId)?.remediationBaseSha)
  }

  @Test
  fun `resume coherence heals a recorded-but-superseded remediation base to HEAD with evidence`() {
    // SKILL-15 topology: stored base is the orphaned sibling; branch tip is the later sibling.
    // Pre-fix, these disagree. Post-fix resume heal makes the durable row agree with the ref.
    val fixture = skill15GitFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    assertTrue(head != fixture.orphanedBase, "fixture must leave the orphan unreachable from HEAD")
    val state = deepRemediationState(completedPasses = 2)
      .reserveNextPass()
      .copy(remediationBaseSha = fixture.orphanedBase)
    val repository = InMemoryRuntimeWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")
    val gitOps = GitWorkflowGitOperations()

    val healed = assertNotNull(
      recorder.reconcileRemediationBaseCoherence(workflowId, gitOps, fixture.repoRoot),
    )

    assertEquals(head, healed.remediationBaseSha, "durable remediation_base_sha must match the branch tip")
    assertEquals(head, recorder.reviewState(workflowId)?.remediationBaseSha)
    assertEquals(
      "true",
      gitOps.isCommitAncestor(fixture.repoRoot, healed.remediationBaseSha!!, head).value,
      "healed base must be reachable from HEAD",
    )
    @Suppress("UNCHECKED_CAST")
    val evidence = repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY]
      as List<Map<String, Any?>>
    val entry = evidence.single()
    assertEquals(fixture.orphanedBase, entry["original_sha"])
    assertEquals(head, entry["replacement_sha"])
    assertEquals("recorded_but_superseded", entry["failure_reason"])
    assertEquals("remediation_base_sha", entry["repointed_field"])
  }

  @Test
  fun `resume coherence heals committed-but-unrecorded remediation checkpoint to the identity sha`() {
    // Crash window: remediation checkpoint committed and identity recorded, base still the parent.
    val fixture = committedUnrecordedFixture()
    val state = deepRemediationState(completedPasses = 1)
      .reserveNextPass()
      .copy(remediationBaseSha = fixture.parent)
    val repository = InMemoryRuntimeWorkflowRepository()
    val identity = FeatureTaskRuntimeCheckpointIdentity(
      sequenceNumber = 0,
      issueKey = "SKILL-176",
      branch = "feat/skill-15",
      phaseId = "review",
      generation = 1,
      ownedPathDigest = "a".repeat(64),
      ownedPathCount = 1,
      commitSha = fixture.checkpointSha,
      recordedAt = "2026-08-10T00:00:00Z",
      loopId = "review_fix",
      parentSha = fixture.parent,
    )
    val recorder = recorderWith(
      state,
      repository,
      goalBranch = "feat/skill-15",
      checkpointIdentities = listOf(identity),
    )
    val gitOps = GitWorkflowGitOperations()

    val healed = assertNotNull(
      recorder.reconcileRemediationBaseCoherence(workflowId, gitOps, fixture.repoRoot),
    )

    assertEquals(fixture.checkpointSha, healed.remediationBaseSha)
    assertEquals(fixture.checkpointSha, git(fixture.repoRoot, "rev-parse", "HEAD"))
    @Suppress("UNCHECKED_CAST")
    val evidence = repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY]
      as List<Map<String, Any?>>
    assertEquals("committed_but_unrecorded", evidence.single()["failure_reason"])
  }

  @Test
  fun `resume coherence is a no-op when the recorded base already agrees with HEAD`() {
    val fixture = skill15GitFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    val state = deepRemediationState(completedPasses = 2)
      .reserveNextPass()
      .copy(remediationBaseSha = head)
    val repository = InMemoryRuntimeWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")

    recorder.reconcileRemediationBaseCoherence(workflowId, GitWorkflowGitOperations(), fixture.repoRoot)

    assertEquals(head, recorder.reviewState(workflowId)?.remediationBaseSha)
    assertNull(repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY])
  }

  @Test
  fun `resume coherence keeps a Skip-recorded descendant tip ahead of the review_fix identity`() {
    // AC-006: identity R is still on the branch; stored H is a later Skip-recorded tip (descendant of
    // R). Pre-fix resume replaced H with R because identity != stored; post-fix must keep H.
    val fixture = skipRecordedDescendantFixture()
    val state = deepRemediationState(completedPasses = 1)
      .reserveNextPass()
      .copy(remediationBaseSha = fixture.skipRecordedTip)
    val repository = InMemoryRuntimeWorkflowRepository()
    val identity = FeatureTaskRuntimeCheckpointIdentity(
      sequenceNumber = 0,
      issueKey = "SKILL-176",
      branch = "feat/skill-15",
      phaseId = "review",
      generation = 1,
      ownedPathDigest = "a".repeat(64),
      ownedPathCount = 1,
      commitSha = fixture.checkpointSha,
      recordedAt = "2026-08-10T00:00:00Z",
      loopId = "review_fix",
      parentSha = fixture.parent,
    )
    val recorder = recorderWith(
      state,
      repository,
      goalBranch = "feat/skill-15",
      checkpointIdentities = listOf(identity),
    )

    val after = assertNotNull(
      recorder.reconcileRemediationBaseCoherence(workflowId, GitWorkflowGitOperations(), fixture.repoRoot),
    )

    assertEquals(fixture.skipRecordedTip, after.remediationBaseSha)
    assertEquals(fixture.skipRecordedTip, recorder.reviewState(workflowId)?.remediationBaseSha)
    assertEquals(fixture.skipRecordedTip, git(fixture.repoRoot, "rev-parse", "HEAD"))
    assertNull(
      repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
      "coherent Skip-recorded descendant must not emit recovery evidence",
    )
  }

  @Test
  fun `recovery that cannot find a reachable base blocks naming the sha and branch`() {
    val fixture = unreachableOnlyGitFixture()
    val state = deepRemediationState(completedPasses = 2)
      .reserveNextPass()
      .copy(remediationBaseSha = fixture.unreachable)
    val prepared = recorderWith(state, goalBranch = "feat/orphan-goal").buildGoalReviewInput(
      workflowId,
      GitWorkflowGitOperations(),
      fixture.repoRoot,
    )
    val blocked = assertIs<GoalSubtaskReviewInputBlocked>(prepared)
    assertContains(blocked.reason, fixture.unreachable)
    assertContains(blocked.reason, "feat/orphan-goal")
  }

  private fun deepRemediationState(completedPasses: Int): GoalSubtaskReviewState {
    var state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = listOf("scratch/untracked.txt"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    repeat(completedPasses) {
      state = state.reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = emptyList(),
      )
    }
    return state
  }
}
