package skillbill.cli

import skillbill.application.review.simulateGovernedEvidenceReads
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.InvalidAgentAddonSelectionError
import skillbill.error.MalformedMachineConfigError
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliFeatureTaskRuntimeContinuationTest {
  @Test
  fun `feature-task-runtime monitor emits per-phase progress lines including a fix-loop retry line`() {
    // F-017: with --monitor, per-phase progress lines (started/completed and a fix-loop iteration
    // line on a retry) are streamed to live stdout. Drive a retry via invalid-then-valid review.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(invalidReviewUntilLaunchIndex = 5)
    val live = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--monitor")),
      fixture.context(launcher) { liveStdout = { live.append(it) } },
    )

    assertEquals(0, result.exitCode, result.stdout)
    val streamed = live.toString()
    assertContains(streamed, "phase plan started")
    assertContains(streamed, "run started feature_size=SMALL")
    assertContains(streamed, "phase plan completed")
    // SKILL-150 (AC-009): the re-entry line names WHY the phase is running again. An invalid-then-valid
    // review is a schema correction, so the generic `fix_loop` label is no longer what is streamed.
    assertContains(streamed, "phase review started")
    assertContains(streamed, "phase review completed")
  }

  @Test
  fun `feature-task-runtime run and monitor surface decomposed planning stop`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(decomposePlan = true)
    val live = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--monitor")),
      fixture.context(launcher) { liveStdout = { live.append(it) } },
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: decomposed")
    assertContains(result.stdout, "subtask_count: 2")
    assertContains(result.stdout, "decomposition_manifest_path:")
    assertContains(result.stdout, "Work the first subtask first")
    assertContains(live.toString(), "decomposed at planning into 2 subtasks")
    val launchedPhases = launcher.phaseOrder()
    assertEquals(
      listOf("preplan", "plan"),
      launchedPhases,
    )
  }

  @Test
  fun `feature-task-runtime status reconstructs decomposed summary from durable state`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(decomposePlan = true)
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(0, run.exitCode, run.stdout)
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", workflowId),
      fixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "decomposition_reason: Plan needs ordered subtasks.")
    assertContains(status.stdout, "subtask_count: 2")
    assertContains(status.stdout, "decomposition_manifest_path:")
    assertContains(status.stdout, "Work the first subtask first")
  }

  @Test
  fun `feature-task-runtime explicit goal-continuation skips decomposition and pr`() {
    val fixture = runtimeFixture(specFileName = "spec_subtask_5_runtime.md")
    // A goal child plans in direct mode: decomposition is not a valid terminal outcome for it, and
    // AC-015 keeps decomposition data out of the implementation projection entirely.
    val launcher = RecordingPhaseLauncher()
    val goalContinuationArgs = listOf(
      "--agent",
      "codex",
      "--goal-parent-issue-key",
      "SKILL-650",
      "--goal-subtask-id",
      "5",
      "--goal-branch",
      "feat/existing-runtime-branch",
      "--goal-review-base-sha",
      "0000000000000000000000000000000000000000",
      "--suppress-pr",
    )

    val result = CliRuntime.run(
      fixture.runCommand(extra = goalContinuationArgs),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertContains(result.stdout, "subtask_outcome:")
    assertEquals(
      AGENT_LAUNCHED_PHASES.filterNot { it == "pr" },
      launcher.phaseOrder().filterNot { it == "pr" },
    )

    val workflowId = result.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", workflowId),
      fixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "feature-task-runtime: $workflowId")
    assertContains(status.stdout, "status: ok")

    val resumeLauncher = RecordingPhaseLauncher(decomposePlan = true)
    val resume = CliRuntime.run(
      buildList {
        add("--db")
        add(fixture.dbPath.toString())
        add("feature-task")
        add("resume")
        add(workflowId)
        add("SKILL-650")
        add(fixture.specPath.toString())
        add("--repo-root")
        add(fixture.tempDir.toString())
        addAll(goalContinuationArgs)
      },
      fixture.context(resumeLauncher),
    )

    assertEquals(1, resume.exitCode, resume.stdout)
    assertContains(resume.stdout, "is terminal and cannot be resumed")
    assertEquals(emptyList(), resumeLauncher.requests, resume.stdout)
  }

  @Test
  fun `feature-task-runtime stamps full validation depth on goal continuation when flag omitted`() {
    val fixture = runtimeFixture(specFileName = "spec_subtask_5_runtime.md")
    val launcher = RecordingPhaseLauncher()
    val goalContinuationArgs = listOf(
      "--agent",
      "codex",
      "--goal-parent-issue-key",
      "SKILL-650",
      "--goal-subtask-id",
      "5",
      "--goal-branch",
      "feat/existing-runtime-branch",
      "--goal-review-base-sha",
      "0000000000000000000000000000000000000000",
      "--suppress-pr",
    )

    val result = CliRuntime.run(
      fixture.runCommand(extra = goalContinuationArgs),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val workflowId = result.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    assertEquals("full", goalContinuationValidationDepth(fixture.dbPath, workflowId))
  }

  @Test
  fun `feature-task-runtime non-goal run leaves goal continuation absent`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val workflowId = result.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    assertNull(goalContinuationArtifact(fixture.dbPath, workflowId))
  }

  @Test
  fun `feature-task-runtime goal continuation reuses branch while direct run creates branch and opens pr phase`() {
    val goalFixture = runtimeFixture(specFileName = "spec_subtask_5_runtime.md")
    // A goal child plans in direct mode (see AC-015); only the standalone run may decompose.
    val goalLauncher = RecordingPhaseLauncher()
    val goalGit = FakeRuntimeGitOperations(currentBranchValue = "feat/pre-created-runtime-branch")
    assertGoalContinuationUsesExistingBranch(goalFixture, goalLauncher, goalGit)
    assertDirectRunCreatesFeatureBranch()
  }

  private fun assertGoalContinuationUsesExistingBranch(
    goalFixture: FeatureTaskRuntimeCliFixture,
    goalLauncher: RecordingPhaseLauncher,
    goalGit: FakeRuntimeGitOperations,
  ) {
    val goalRun = CliRuntime.run(
      goalFixture.runCommand(
        extra = listOf(
          "--agent",
          "codex",
          "--goal-parent-issue-key",
          "SKILL-650",
          "--goal-subtask-id",
          "5",
          "--goal-branch",
          "feat/pre-created-runtime-branch",
          "--goal-review-base-sha",
          "0000000000000000000000000000000000000000",
          "--goal-parent-workflow-id",
          "wftr-parent",
          "--suppress-pr",
        ),
      ),
      goalFixture.context(goalLauncher) { workflowGitOperations = goalGit },
    )

    assertEquals(0, goalRun.exitCode, goalRun.stdout)
    assertContains(goalRun.stdout, "status: complete")
    assertContains(goalRun.stdout, "resolved_branch: feat/pre-created-runtime-branch")
    assertContains(
      goalRun.stdout,
      "completed_phases: preplan, plan, implement, audit, review, verify_findings, validate, " +
        "write_history, commit_push",
    )
    assertContains(goalRun.stdout, "subtask_outcome:")
    assertContains(goalRun.stdout, "  last_resumable_step: commit_push")
    assertEquals(emptyList(), goalGit.checkoutBranches, goalRun.stdout)
    assertEquals(
      AGENT_LAUNCHED_PHASES.filterNot { it == "pr" },
      goalLauncher.phaseOrder(),
      goalRun.stdout,
    )
  }

  private fun assertDirectRunCreatesFeatureBranch() {
    val directFixture = runtimeFixture()
    val directLauncher = RecordingPhaseLauncher()
    val directGit = FakeRuntimeGitOperations(currentBranchValue = "main")

    val directRun = CliRuntime.run(
      directFixture.runCommand(extra = listOf("--agent", "codex")),
      directFixture.context(directLauncher) { workflowGitOperations = directGit },
    )

    assertEquals(0, directRun.exitCode, directRun.stdout)
    assertContains(directRun.stdout, "status: complete")
    assertContains(directRun.stdout, "resolved_branch: feat/SKILL-650-runtime")
    assertContains(
      directRun.stdout,
      "completed_phases: $COMPLETED_PHASES_CLEAN_RUN",
    )
    assertEquals(listOf("feat/SKILL-650-runtime"), directGit.checkoutBranches, directRun.stdout)
    assertEquals(
      AGENT_LAUNCHED_PHASES,
      directLauncher.phaseOrder(),
      directRun.stdout,
    )
  }

  @Test
  fun `feature-task-runtime status reports per-phase projection after a completed run`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(0, run.exitCode, run.stdout)
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", workflowId),
      fixture.context(launcher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: ok")
    assertContains(status.stdout, "feature_size: SMALL")
    assertContains(status.stdout, "complete: 10")
    assertContains(status.stdout, "pending: 2")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "phase: id=plan status=completed")
    assertContains(status.stdout, "origin=agent-executed")
    assertContains(status.stdout, "phase: id=implement_fix status=pending")
    assertContains(status.stdout, "phase: id=build status=pending")
    // SKILL-85 Subtask 4 (F-005): a fully forward-completed run reports no current phase — the
    // loop-only implement_fix (still pending) must NOT be projected as the current phase to operators.
    assertContains(status.stdout, "current_phase: none")
    val planPhase = (requireNotNull(status.payload)["phases"] as List<*>)
      .mapNotNull { it as? Map<*, *> }
      .single { it["phase_id"] == "plan" }
    assertEquals("agent-executed", planPhase["execution_origin"])
  }

  @Test
  fun `feature-task-runtime status reports not_found for an unknown workflow id`() {
    val fixture = runtimeFixture()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", "wftr-missing"),
      fixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: not_found")
    assertContains(status.stdout, "feature_size: unknown")
  }

  @Test
  fun `feature-task-runtime status reports a blocked phase derived from the ledger`() {
    val fixture = runtimeFixture()
    // Preplan and plan complete; implement emits unparseable output and blocks on the format budget.
    val launcher = RecordingPhaseLauncher(invalidFromLaunchIndex = 2)
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(1, run.exitCode, run.stdout)
    assertContains(run.stdout, "status: blocked")
    assertContains(run.stdout, "last_incomplete_phase: implement")
    assertContains(run.stdout, "blocked_reason:")
    assertContains(run.stdout, "output-gate correction budget")
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", workflowId),
      fixture.context(launcher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: ok")
    assertContains(status.stdout, "feature_size: SMALL")
    assertContains(status.stdout, "complete: 2")
    assertContains(status.stdout, "blocked: 1")
    assertContains(status.stdout, "current_phase: implement")
    assertContains(status.stdout, "phase: id=plan status=completed")
    assertContains(status.stdout, "phase: id=implement status=blocked")
  }

  @Test
  fun `feature-task retry-blocked reopens a blocked runtime phase for resume`() {
    val fixture = runtimeFixture()
    val blockedLauncher = RecordingPhaseLauncher(invalidFromLaunchIndex = 2)
    val blocked = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(blockedLauncher),
    )
    assertEquals(1, blocked.exitCode, blocked.stdout)
    val workflowId = blocked.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val operatorReason = "Operator applied the external fix; retry the blocked phase."
    val retry = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "retry-blocked",
        workflowId,
        "--phase",
        "implement",
        "--reason",
        operatorReason,
      ),
      fixture.context(RecordingPhaseLauncher()),
    )
    assertEquals(0, retry.exitCode, retry.stdout)
    assertContains(retry.stdout, "workflow_status: running")
    assertContains(retry.stdout, "current_step_id: implement")

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", workflowId),
      fixture.context(RecordingPhaseLauncher()),
    )
    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "current_phase: implement")
    assertContains(status.stdout, "phase: id=implement status=pending")

    val resumedLauncher = RecordingPhaseLauncher()
    val resumed = CliRuntime.run(
      fixture.resumeCommand(workflowId),
      fixture.context(resumedLauncher),
    )
    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertEquals(
      AGENT_LAUNCHED_PHASES.dropWhile { it != "implement" },
      resumedLauncher.phaseOrder(),
    )
    val completedStatus = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", workflowId),
      fixture.context(RecordingPhaseLauncher()),
    )
    assertEquals(0, completedStatus.exitCode, completedStatus.stdout)
    assertContains(completedStatus.stdout, "phase: id=implement status=completed attempt=2")
    val resumedPrompts = resumedLauncher.requests.map { it.skillRunRequest.promptOverride.orEmpty() }
    assertContains(resumedPrompts.first(), operatorReason)
    assertTrue(resumedPrompts.drop(1).none { it.contains(operatorReason) })
  }

  @Test
  fun `feature-task abandon terminalizes a blocked workflow with a durable reason`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(invalidFromLaunchIndex = 2)
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val abandoned = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "abandon",
        workflowId,
        "--reason",
        "Replacing a deterministically blocked run.",
      ),
      fixture.context(launcher),
    )

    assertEquals(0, abandoned.exitCode, abandoned.stdout)
    assertContains(abandoned.stdout, "workflow_status: abandoned")
    assertContains(abandoned.stdout, "operator_abandonment")
    val repeated = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "abandon",
        workflowId,
        "--reason",
        "Repeated abandonment.",
      ),
      fixture.context(launcher),
    )
    assertEquals(1, repeated.exitCode, repeated.stdout)
    assertContains(repeated.stdout, "already terminal")
  }

  @Test
  fun `feature-task lookup names an identity-less workflow and points at repair-identity`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(invalidFromLaunchIndex = 2)
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.prepareStatement("DELETE FROM feature_task_execution_identities WHERE workflow_id = ?").use {
        it.setString(1, workflowId)
        assertEquals(1, it.executeUpdate())
      }
    }

    val lookup = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "lookup",
        "SKILL-650",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher),
    )

    assertEquals(0, lookup.exitCode, lookup.stdout)
    assertContains(lookup.stdout, "needs_identity_repair")
    assertContains(lookup.stdout, workflowId)
    assertContains(lookup.stdout, "repair-identity")
  }

  @Test
  fun `feature-task repair-identity restores an explicitly identified legacy workflow`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(invalidFromLaunchIndex = 2)
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.prepareStatement("DELETE FROM feature_task_execution_identities WHERE workflow_id = ?").use {
        it.setString(1, workflowId)
        assertEquals(1, it.executeUpdate())
      }
    }

    val repaired = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "repair-identity",
        workflowId,
        "SKILL-650",
        fixture.specPath.toString(),
        "--repo-root",
        fixture.tempDir.toString(),
        "--reason",
        "Repair a pre-identity runtime workflow.",
      ),
      fixture.context(launcher),
    )

    assertEquals(0, repaired.exitCode, repaired.stdout)
    assertContains(repaired.stdout, "operator_identity_repair")
    val lookup = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "lookup",
        "SKILL-650",
        "--repo-root",
        fixture.tempDir.toString(),
        "--workflow-id",
        workflowId,
      ),
      fixture.context(launcher),
    )
    assertFalse(lookup.stdout.contains("missing immutable execution identity"), lookup.stdout)
  }

  @Test
  fun `feature-task-runtime explicit run subcommand completes every phase like the default run`() {
    // The documented `feature-task-runtime run <issue_key> <spec_path>` form: without a real
    // `run` subcommand, clikt silently consumes `run` as the optional issue-key positional and
    // misparses the spec path as a subcommand.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "run",
        "SKILL-650",
        fixture.specPath.toString(),
        "--repo-root",
        fixture.tempDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertContains(
      result.stdout,
      "completed_phases: $COMPLETED_PHASES_CLEAN_RUN",
    )
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
  }
}
}
