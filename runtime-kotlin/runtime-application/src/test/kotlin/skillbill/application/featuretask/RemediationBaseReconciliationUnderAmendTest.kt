package skillbill.application.featuretask

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.resolveCheckpointRef
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemediationBaseReconciliationUnderAmendTest {
  private val workflowId = "wftr-skill190-reconcile"
  private val issueKey = "SKILL-190"
  private val subtaskId = "4"
  private val goalBranch = "feat/skill-190"

  @Test
  fun `orphaned review_fix identity commit still resolves through its checkpoint ref`() {
    val fixture = amendRemediationFixture()
    val preFixSha = fixture.preRemediationSha
    val identity = reviewFixIdentity(
      sequenceNumber = 1,
      commitSha = fixture.postRemediationSha,
      parentSha = preFixSha,
      repoRoot = fixture.repoRoot,
      preFixSha = preFixSha,
    )
    val state = remediationState(remediationBaseSha = preFixSha)
    val recorder = recorderWith(state, fixture.repoRoot, listOf(identity))
    val git = realGitOps()

    val result = recorder.reconcileRemediationBaseCoherence(workflowId, git, fixture.repoRoot)
    val coherent = assertIs<RemediationBaseCoherenceResult.Coherent>(result)
    assertEquals(preFixSha, coherent.state?.remediationBaseSha)
    assertFalse(git.isCommitAncestor(fixture.repoRoot, preFixSha, fixture.postRemediationSha).let { it.ok && it.value == "true" })
  }

  @Test
  fun `unresolvable checkpoint ref blocks without rewriting remediation base to HEAD`() {
    val fixture = amendRemediationFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    val ref = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 1)
    git(fixture.repoRoot, "update-ref", "-d", ref)
    val identity = reviewFixIdentity(
      sequenceNumber = 1,
      commitSha = fixture.postRemediationSha,
      parentSha = fixture.preRemediationSha,
      repoRoot = fixture.repoRoot,
      preFixSha = null,
    )
    val state = remediationState(remediationBaseSha = fixture.preRemediationSha)
    val repository = InMemoryRuntimeWorkflowRepository()
    val recorder = recorderWith(state, fixture.repoRoot, listOf(identity), repository)

    val blocked = assertIs<RemediationBaseCoherenceResult.Blocked>(
      recorder.reconcileRemediationBaseCoherence(workflowId, realGitOps(), fixture.repoRoot),
    )
    assertContains(blocked.operatorGuidance, workflowId)
    assertContains(blocked.operatorGuidance, goalBranch)
    assertContains(blocked.operatorGuidance, ref)
    assertContains(blocked.operatorGuidance, "skill-bill goal repair")
    assertEquals(fixture.preRemediationSha, recorder.reviewState(workflowId)?.remediationBaseSha)
    assertNotEquals(head, recorder.reviewState(workflowId)?.remediationBaseSha)
    @Suppress("UNCHECKED_CAST")
    val evidence = repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY]
      as List<Map<String, Any?>>
    val entry = evidence.single()
    assertEquals("FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence", entry["seam"])
    assertEquals(ref, entry["value_used"])
    assertEquals("resolvable review_fix checkpoint ref commit", entry["value_expected"])
    assertNotNull(entry["cause"])
  }

  @Test
  fun `two review_fix passes across an amend boundary receive a non-empty diff base`() {
    val fixture = amendRemediationFixture()
    val preFixSha = fixture.preRemediationSha
    val identity = reviewFixIdentity(
      sequenceNumber = 1,
      commitSha = fixture.postRemediationSha,
      parentSha = preFixSha,
      repoRoot = fixture.repoRoot,
      preFixSha = preFixSha,
    )
    val state = remediationState(remediationBaseSha = preFixSha)
    val recorder = recorderWith(state, fixture.repoRoot, listOf(identity))
    val diff = git(fixture.repoRoot, "diff", preFixSha, fixture.postRemediationSha)
    assertTrue(diff.contains("pass-one-marker"), "diff must contain pass-one remediation changes")
    val coherent = assertIs<RemediationBaseCoherenceResult.Coherent>(
      recorder.reconcileRemediationBaseCoherence(workflowId, realGitOps(), fixture.repoRoot),
    )
    assertEquals(preFixSha, coherent.state?.remediationBaseSha)
  }

  @Test
  fun `no remediation checkpoint yet performs no HEAD read`() {
    val repoRoot = Files.createTempDirectory("skillbill-no-remediation-yet")
    initRepo(repoRoot)
    val state = remediationState(remediationBaseSha = null)
    val recorder = recorderWith(state, repoRoot, emptyList())
    val git = object : WorkflowGitOperations by realGitOps() {
      override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
        WorkflowGitOperationResult(status = "error", error = "HEAD read forbidden on cheap path")
    }
    val result = recorder.reconcileRemediationBaseCoherence(workflowId, git, repoRoot)
    assertIs<RemediationBaseCoherenceResult.Coherent>(result)
  }

  @Test
  fun `rollback after remediation amend restores prior checkpoint ref and is idempotent`() {
    val fixture = amendRemediationFixture()
    val preFixSha = fixture.preRemediationSha
    val ref0 = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 0)
    git(fixture.repoRoot, "update-ref", ref0, fixture.implementSha)
    val identity0 = reviewFixIdentity(
      sequenceNumber = 0,
      commitSha = fixture.implementSha,
      parentSha = fixture.parentSha,
      repoRoot = fixture.repoRoot,
      preFixSha = null,
      refName = ref0,
    )
    val identity1 = reviewFixIdentity(
      sequenceNumber = 1,
      commitSha = fixture.postRemediationSha,
      parentSha = preFixSha,
      repoRoot = fixture.repoRoot,
      preFixSha = preFixSha,
    )
    val rollbackHead = fixture.postRemediationSha
    rollbackRemediation(
      repoRoot = fixture.repoRoot,
      commitSha = rollbackHead,
      parentSha = preFixSha,
      identityRecorded = true,
      identities = listOf(identity0, identity1),
    )
    assertEquals(fixture.implementSha, git(fixture.repoRoot, "rev-parse", "HEAD"))
    rollbackRemediation(
      repoRoot = fixture.repoRoot,
      commitSha = rollbackHead,
      parentSha = preFixSha,
      identityRecorded = true,
      identities = listOf(identity0, identity1),
    )
    assertEquals(fixture.implementSha, git(fixture.repoRoot, "rev-parse", "HEAD"))
    val log = git(fixture.repoRoot, "rev-list", "--count", "HEAD")
    assertEquals("2", log.trim())
  }

  @Test
  fun `rollback of first checkpoint removes the subtask commit`() {
    val repoRoot = Files.createTempDirectory("skillbill-first-checkpoint-rollback")
    initRepo(repoRoot)
    val parentSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("owned.txt"), "subtask\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "-m", "subtask commit")
    val commitSha = git(repoRoot, "rev-parse", "HEAD")
    rollbackRemediation(
      repoRoot = repoRoot,
      commitSha = commitSha,
      parentSha = parentSha,
      identityRecorded = false,
      identities = emptyList(),
    )
    assertEquals(parentSha, git(repoRoot, "rev-parse", "HEAD"))
    assertEquals("1", git(repoRoot, "rev-list", "--count", "HEAD").trim())
  }

  private data class AmendRemediationFixture(
    val repoRoot: Path,
    val parentSha: String,
    val implementSha: String,
    val preRemediationSha: String,
    val postRemediationSha: String,
  )

  private fun amendRemediationFixture(): AmendRemediationFixture {
    val repoRoot = Files.createTempDirectory("skillbill-amend-remediation")
    initRepo(repoRoot)
    val parentSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("owned.txt"), "implement\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "-m", "subtask implement")
    val implementSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("owned.txt"), "pass-one-marker\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "--amend", "-m", "subtask remediation pre-fix")
    val preRemediationSha = git(repoRoot, "rev-parse", "HEAD")
    val ref = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 1)
    git(repoRoot, "update-ref", ref, preRemediationSha)
    Files.writeString(repoRoot.resolve("owned.txt"), "pass-two-marker\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "--amend", "-m", "subtask remediation post-fix")
    val postRemediationSha = git(repoRoot, "rev-parse", "HEAD")
    return AmendRemediationFixture(
      repoRoot = repoRoot,
      parentSha = parentSha,
      implementSha = implementSha,
      preRemediationSha = preRemediationSha,
      postRemediationSha = postRemediationSha,
    )
  }

  private fun remediationState(remediationBaseSha: String?): GoalSubtaskReviewState {
    var state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    state = state.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = emptyList(),
    )
    return state.reserveNextPass().copy(remediationBaseSha = remediationBaseSha)
  }

  private fun reviewFixIdentity(
    sequenceNumber: Int,
    commitSha: String,
    parentSha: String?,
    repoRoot: Path,
    preFixSha: String?,
    refName: String = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber),
  ): FeatureTaskRuntimeCheckpointIdentity {
    if (preFixSha != null) {
      git(repoRoot, "update-ref", refName, preFixSha)
    }
    return FeatureTaskRuntimeCheckpointIdentity(
      sequenceNumber = sequenceNumber,
      issueKey = issueKey,
      subtaskId = subtaskId,
      checkpointRef = refName,
      branch = goalBranch,
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      generation = 1,
      ownedPathDigest = "a".repeat(64),
      ownedPathCount = 1,
      commitSha = commitSha,
      recordedAt = "2026-08-19T00:00:00Z",
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
      parentSha = parentSha,
    )
  }

  private fun recorderWith(
    state: GoalSubtaskReviewState,
    repoRoot: Path,
    checkpointIdentities: List<FeatureTaskRuntimeCheckpointIdentity>,
    repository: InMemoryRuntimeWorkflowRepository = InMemoryRuntimeWorkflowRepository(),
  ): FeatureTaskRuntimeGoalContinuationRecorder {
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    val artifactsPatch = linkedMapOf<String, Any?>(
      FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = issueKey,
        subtaskId = 4,
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

  private fun rollbackRemediation(
    repoRoot: Path,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
  ) {
    val head = realGitOps().headCommitSha(repoRoot)
    if (!head.ok || head.value.trim() != commitSha.trim()) return
    val predecessor = when {
      identityRecorded -> {
        val current = identities.lastOrNull { it.commitSha == commitSha }
        if (current == null || current.sequenceNumber == 0) {
          null
        } else {
          identities.find { it.sequenceNumber == current.sequenceNumber - 1 }
        }
      }
      identities.isEmpty() -> null
      else -> identities.maxByOrNull { it.sequenceNumber }
    }
    val restoreSha = when {
      predecessor == null -> parentSha?.trim()?.takeIf(String::isNotBlank)
      else -> {
        val resolved = realGitOps().resolveCheckpointRef(
          repoRoot,
          FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
          predecessor.checkpointRef,
        )
        resolved.value.orEmpty().trim().takeIf { resolved.ok && it.isNotBlank() } ?: parentSha?.trim()
      }
    }
    requireNotNull(restoreSha) { "rollback target missing" }
    realGitOps().resetSoftToCommit(repoRoot, restoreSha)
  }

  private fun initRepo(repoRoot: Path) {
    git(repoRoot, "init", "-b", goalBranch)
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("root.txt"), "root\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "root")
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

  private fun realGitOps(): WorkflowGitOperations = skillbill.infrastructure.fs.GitWorkflowGitOperations()
}
