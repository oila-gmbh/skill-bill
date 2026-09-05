package skillbill.architecture

import skillbill.application.goalrunner.NoopGoalRunnerChildRepairStore
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.persistence.NoopPortableReviewBaselinePersistence
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeRepairRequest
import skillbill.ports.goalrunner.runner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.idestatus.EmptyAgentActivityStampRepository
import skillbill.ports.idestatus.NoopIdeStatusValidator
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.goal.model.PortableReviewBaseline
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeQuarantineValidator
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecordingNullObjectDiagnosticsTest {
  private val warnings = mutableListOf<String>()

  @AfterTest
  fun resetBinding() {
    RecordingNullObjectDiagnostics.resetBindingForTests()
    warnings.clear()
  }

  @Test
  fun `every recording null object emits diagnostics when bound`() {
    RecordingNullObjectDiagnostics.bind { message, _ -> warnings += message }
    val repoRoot = Path.of("/tmp/skill-bill-null-object-test")
    exerciseGoalRunnerControlNullObjects()
    exerciseAgentActivityNullObjects()
    exerciseChildRepairNullObjects(repoRoot)
    exercisePortableReviewBaselineNullObjects(repoRoot)
    exerciseValidatorNullObjects()
    exerciseWorkflowGitNullObjects(repoRoot)
    exerciseRuntimeTimingNullObjects()
    exerciseWorkerSupervisorNullObjects()
    assertEveryRecordingObjectEmitted()
  }

  private fun exerciseGoalRunnerControlNullObjects() {
    EmptyGoalRunnerControlRepository.controlState("parent")
    EmptyGoalRunnerControlRepository.persistControlState("parent", GoalRunnerControlState())
    EmptyGoalRunnerControlRepository.clearControlState("parent")
    EmptyGoalRunnerControlRepository.reviewPolicy("parent")
    EmptyGoalRunnerControlRepository.persistReviewPolicy(
      "parent",
      GoalRunnerReviewPolicy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )
    EmptyGoalRunnerControlRepository.outOfBandAcceptances("parent")
    EmptyGoalRunnerControlRepository.persistOutOfBandAcceptance(
      "parent",
      GoalRunnerOutOfBandAcceptance(
        subtaskId = 1,
        commitSha = "abc123",
        reason = "accepted",
        acceptedAt = "2026-01-01T00:00:00Z",
      ),
    )
    EmptyGoalRunnerControlRepository.clearOutOfBandAcceptances("parent")
    NoopGoalRunnerAttemptLedgerStore.readAttemptLedgerSummary("SKILL-1")
  }

  private fun exerciseAgentActivityNullObjects() {
    EmptyAgentActivityStampRepository.record(
      "wf-1",
      AgentActivityStamp(Instant.parse("2026-01-01T00:00:00Z"), AgentActivityLabel.STDOUT),
    )
    EmptyAgentActivityStampRepository.read("wf-1")
  }

  private fun exerciseChildRepairNullObjects(repoRoot: Path) {
    NoopGoalRunnerChildRepairStore.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = "wf-1",
        issueKey = "SKILL-1",
        subtaskId = 1,
        subtasks = emptyList(),
        repoRoot = repoRoot,
      ),
    )
    NoopGoalRunnerChildRepairStore.applyChildWedgeRepairs(
      GoalRunnerChildWedgeRepairRequest(
        workflowId = "wf-1",
        issueKey = "SKILL-1",
        subtaskId = 1,
        wedgeClasses = emptyList(),
        repoRoot = repoRoot,
      ),
    )
  }

  private fun exercisePortableReviewBaselineNullObjects(repoRoot: Path) {
    NoopPortableReviewBaselinePersistence.read(repoRoot.resolve("missing.yaml"))
    NoopPortableReviewBaselinePersistence.writeAtomically(
      repoRoot.resolve("missing.yaml"),
      PortableReviewBaseline(
        workflowId = "wf-1",
        repositoryIdentity = "repo",
        goalBranch = "feat/demo",
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        integrityDigest = "0".repeat(64),
      ),
    )
  }

  private fun exerciseValidatorNullObjects() {
    NoopIdeStatusValidator.validate(emptyMap(), "test")
    NoopGoalProgressEventValidator.validate(emptyMap(), "test")
    NoopGoalObservabilityEventValidator.validate(emptyMap(), "test")
    NoopFeatureTaskRuntimeQuarantineValidator.validateQuarantineRecord(emptyMap(), "test")
    NoopFeatureTaskRuntimePlanningProjectionValidator.validatePlanningProjection(emptyMap(), "test")
    NoopFeatureTaskRuntimeImplementationAttemptValidator.validateImplementationAttemptRecord(emptyMap(), "test")
    NoopFeatureTaskRuntimeBuildReceiptValidator.validateBuildReceipt(emptyMap(), "test")
  }

  private fun exerciseWorkflowGitNullObjects(repoRoot: Path) {
    val git = NoopWorkflowGitOperations
    git.checkoutBranch(repoRoot, "feat/demo")
    git.branchExists(repoRoot, "feat/demo")
    git.currentBranch(repoRoot)
    git.validateBranchBase(repoRoot, "feat/demo", "main")
    git.pushBranch(repoRoot, "feat/demo")
    git.localBranchHasUnpushedCommits(repoRoot, "feat/demo")
    git.createCommit(repoRoot, "message")
    git.headCommitSha(repoRoot)
    git.resetSoftToCommit(repoRoot, "abc")
    git.isCommitAncestor(repoRoot, "abc", "def")
    git.worktreeStatus(repoRoot)
    git.worktreeActivity(repoRoot)
    git.selectedDiffHunks(repoRoot, WorkflowSelectedDiffHunksRequest(paths = listOf("README.md")))
    git.repositoryFingerprintOperations.repositoryFingerprint(repoRoot)
    val reviewBaseline = git.captureGoalSubtaskReviewBaseline(repoRoot, "feat/demo").baseline!!
    git.buildGoalSubtaskReviewInput(repoRoot, reviewBaseline, "feat/demo")
    git.recoverGoalSubtaskReviewBaseline(
      repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = reviewBaseline.reviewBaseSha,
        failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
        baselineUntrackedPaths = emptyList(),
      ),
      "feat/demo",
    )
    git.runtimePhaseHeadCommit(repoRoot)
    git.runtimePhaseChangedPathsBetweenCommits(repoRoot, "before", "after")
  }

  private fun exerciseRuntimeTimingNullObjects() {
    NoopRuntimeTimingPort.wait(1.seconds)
  }

  private fun exerciseWorkerSupervisorNullObjects() {
    val ownership = FeatureTaskRuntimeWorkerOwnership(
      workflowId = "wf-1",
      generation = 1,
      ownerToken = "owner",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 1,
      processBirthToken = "birth",
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = "2026-01-01T00:00:00Z",
      expiresAt = "2026-01-01T00:05:00Z",
      phaseId = "implement",
      phaseAttempt = 1,
    )
    val heartbeatPlan = FeatureTaskRuntimeHeartbeatPlan("test", intervalSeconds = 1, leaseSeconds = 5)
    val heartbeat = NoopFeatureTaskRuntimeWorkerSupervisor.startHeartbeat(heartbeatPlan) {
      FeatureTaskRuntimeHeartbeatTick.Renewed
    }
    NoopFeatureTaskRuntimeWorkerSupervisor.currentProcess()
    NoopFeatureTaskRuntimeWorkerSupervisor.inspect(ownership)
    NoopFeatureTaskRuntimeWorkerSupervisor.awaitExit(ownership, Duration.ofSeconds(1))
    NoopFeatureTaskRuntimeWorkerSupervisor.terminateGracefully(ownership)
    NoopFeatureTaskRuntimeWorkerSupervisor.terminateForcibly(ownership)
    NoopFeatureTaskRuntimeWorkerSupervisor.pause(1)
    heartbeat.stop()
    heartbeat.fencingLostReason()
  }

  private fun assertEveryRecordingObjectEmitted() {
    val recordingObjects = PortNullObjectClassification.classifiedObjects
      .filterValues { it == PortNullObjectKind.RECORDING_NULL_OBJECT }
      .keys
    recordingObjects.forEach { objectName ->
      assertTrue(
        warnings.any { it.contains("'$objectName'") },
        "Expected a diagnostic for recording null object '$objectName', got: $warnings",
      )
    }
    assertEquals(
      recordingObjects.size,
      recordingObjects.count { objectName ->
        warnings.count { it.contains("'$objectName'") } >= 1
      },
    )
  }
}
