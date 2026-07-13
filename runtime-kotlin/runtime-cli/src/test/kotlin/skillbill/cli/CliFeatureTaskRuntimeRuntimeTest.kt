package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.error.MalformedMachineConfigError
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliFeatureTaskRuntimeRuntimeTest {
  @Test
  fun `feature-task command registers run status and resume`() {
    val help = CliRuntime.run(listOf("feature-task", "--help"), CliRuntimeContext())

    assertEquals(0, help.exitCode, help.stdout)
    // AC5: canonical command help must contain no EXPERIMENTAL language.
    assertFalse(help.stdout.contains("EXPERIMENTAL"), help.stdout)
    assertContains(help.stdout, "status")
    assertContains(help.stdout, "resume")
    // The documented explicit `run` form is a real subcommand, not a misparsed positional.
    assertContains(help.stdout, "explicit form")
    assertContains(help.stdout, "--phase-agent")
    assertContains(help.stdout, "--phase-model")
    assertContains(help.stdout, "--agent-override")
    assertContains(help.stdout, "--monitor")
    assertContains(help.stdout, "--max-wall-clock-minutes")
  }

  @Test
  fun `feature-task-runtime is a hidden deprecated alias that works and emits a deprecation note`() {
    // AC2: the deprecated alias still runs run/status/resume with identical behavior while emitting
    // a deprecation note. The note goes to liveStderr so it never pollutes the structured stdout.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val stderr = StringBuilder()

    val run = CliRuntime.run(
      buildList {
        add("--db")
        add(fixture.dbPath.toString())
        add("feature-task-runtime")
        add("SKILL-650")
        add(fixture.specPath.toString())
        add("--repo-root")
        add(fixture.tempDir.toString())
        add("--agent")
        add("codex")
      },
      fixture.context(launcher, liveStderr = { stderr.append(it) }),
    )

    assertEquals(0, run.exitCode, run.stdout)
    assertContains(run.stdout, "status: complete")
    assertContains(
      run.stdout,
      "completed_phases: preplan, plan, implement, review, audit, validate, write_history, commit_push, pr",
    )
    assertEquals(ALL_PHASES.size, launcher.requests.size)
    assertContains(stderr.toString(), "feature-task-runtime is a deprecated alias for feature-task")

    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    val statusStderr = StringBuilder()
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task-runtime", "status", workflowId),
      fixture.context(RecordingPhaseLauncher(), liveStderr = { statusStderr.append(it) }),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: ok")
    assertContains(statusStderr.toString(), "feature-task-runtime is a deprecated alias for feature-task")
  }

  @Test
  fun `feature-task and feature-task-runtime produce byte-identical stdout for the same run`() {
    // AC2 "no behavioral difference": the deprecated alias must emit the SAME structured stdout as the
    // canonical command. Run identical args through both, normalize only the non-deterministic
    // workflow_id line, and assert the rest is byte-identical. The deprecation note lives on stderr and
    // must never leak into stdout.
    val canonicalFixture = runtimeFixture()
    val canonicalStderr = StringBuilder()
    val canonical = CliRuntime.run(
      featureTaskCommand(canonicalFixture, command = "feature-task"),
      canonicalFixture.context(RecordingPhaseLauncher(), liveStderr = { canonicalStderr.append(it) }),
    )
    assertEquals(0, canonical.exitCode, canonical.stdout)

    val aliasFixture = runtimeFixture()
    val aliasStderr = StringBuilder()
    val alias = CliRuntime.run(
      featureTaskCommand(aliasFixture, command = "feature-task-runtime"),
      aliasFixture.context(RecordingPhaseLauncher(), liveStderr = { aliasStderr.append(it) }),
    )
    assertEquals(0, alias.exitCode, alias.stdout)

    assertEquals(
      normalizeRuntimeStdout(canonical.stdout),
      normalizeRuntimeStdout(alias.stdout),
    )
    // The deprecation note is stderr-only on the alias; it must not appear in either stdout.
    assertContains(aliasStderr.toString(), "feature-task-runtime is a deprecated alias for feature-task")
    assertFalse(canonicalStderr.toString().contains("deprecated alias"), canonicalStderr.toString())
    assertFalse(canonical.stdout.contains("deprecated alias"), canonical.stdout)
    assertFalse(alias.stdout.contains("deprecated alias"), alias.stdout)
  }

  @Test
  fun `feature-task-runtime alias is hidden from the top-level help while feature-task is shown`() {
    val help = CliRuntime.run(listOf("--help"), CliRuntimeContext())

    assertEquals(0, help.exitCode, help.stdout)
    assertContains(help.stdout, "feature-task")
    assertFalse(help.stdout.contains("feature-task-runtime"), help.stdout)
  }

  @Test
  fun `feature-task-runtime run requires issue key and spec path`() {
    val missingArgs = CliRuntime.run(listOf("feature-task"), CliRuntimeContext())
    assertEquals(1, missingArgs.exitCode, missingArgs.stdout)
    assertContains(missingArgs.stdout, "issue_key is required")

    val fixture = runtimeFixture()
    val missingSpec = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "SKILL-650"),
      fixture.context(RecordingPhaseLauncher()),
    )
    assertEquals(1, missingSpec.exitCode, missingSpec.stdout)
    assertContains(missingSpec.stdout, "spec_path is required")
  }

  @Test
  fun `feature-task-runtime run reports the resolved feature branch in text and status without a new flag`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    // Start on the default branch so the runtime creates+switches to the convention feature branch.
    val git = FakeRuntimeGitOperations(currentBranchValue = "main")

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(launcher, workflowGitOperations = git),
    )

    assertEquals(0, run.exitCode, run.stdout)
    assertContains(run.stdout, "status: complete")
    assertContains(run.stdout, "resolved_branch: feat/SKILL-650-runtime")
    assertEquals(listOf("feat/SKILL-650-runtime"), git.checkoutBranches)
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task", "status", workflowId),
      fixture.context(launcher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "resolved_branch: feat/SKILL-650-runtime")
  }

  @Test
  fun `feature-task-runtime monitor streams the branch-resolution line`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val git = FakeRuntimeGitOperations(currentBranchValue = "main")
    val live = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--monitor")),
      fixture.context(launcher, liveStdout = { live.append(it) }, workflowGitOperations = git),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(live.toString(), "branch created feat/SKILL-650-runtime")
  }

  @Test
  fun `feature-task-runtime run completes every phase and delegates to the runner`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertContains(result.stdout, "feature_size: SMALL")
    assertContains(
      result.stdout,
      "completed_phases: preplan, plan, implement, review, audit, validate, write_history, commit_push, pr",
    )
    assertEquals(listOf("codex"), launcher.requests.map { it.agentId }.distinct())
    assertEquals(ALL_PHASES.size, launcher.requests.size)
  }

  @Test
  fun `feature-task-runtime run defaults invoked agent to detected invoking context`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher, environment = mapOf("CLAUDECODE" to "1")),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("claude"), launcher.requests.map { it.agentId }.distinct())
  }

  @Test
  fun `feature-task-runtime run refuses when the resolved agent is opencode via SKILL_BILL_AGENT`() {
    // SKILL-95 AC3/AC9: opencode is prose-only. A runtime run whose agent resolves to opencode
    // (here SKILL_BILL_AGENT over the detected invoking context) must fail fast with the actionable
    // refusal message, opening no workflow and spawning no phase.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher, environment = mapOf("CLAUDECODE" to "1", "SKILL_BILL_AGENT" to "opencode")),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Runtime mode is not supported on opencode")
    assertContains(result.stdout, "bill-feature-task-prose")
    assertContains(result.stdout, "bill-feature-goal mode:prose")
    // No phase is launched and no workflow is opened before the refusal.
    assertEquals(emptyList(), launcher.requests, result.stdout)
    assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
  }

  @Test
  fun `feature-task-runtime run refuses when the host invoking agent is detected as opencode`() {
    // SKILL-95 AC3/AC9: implicit resolution must also refuse — when opencode is detected as the
    // invoking host (no --agent / SKILL_BILL_AGENT), runtime still fails fast with the message.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher, environment = mapOf("OPENCODE" to "1")),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Runtime mode is not supported on opencode")
    assertEquals(emptyList(), launcher.requests, result.stdout)
    assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
  }

  @Test
  fun `feature-task-runtime run refuses opencode from agent-override phase-agent and parallel-review routes`() {
    // SKILL-95 AC5/AC9: opencode reaching a runtime phase by ANY route must refuse at the preflight,
    // and the predicate is case- and whitespace-insensitive (`OpenCode`, ` opencode `).
    listOf(
      listOf("--agent", "codex", "--agent-override", "opencode"),
      listOf("--agent", "codex", "--phase-agent", "plan=opencode"),
      listOf("--agent", "codex", "--parallel-review-agent", "opencode"),
      listOf("--agent", "OpenCode"),
      listOf("--agent", " opencode "),
    ).forEach { extra ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = extra),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, "expected refusal for $extra: ${result.stdout}")
      assertContains(result.stdout, "Runtime mode is not supported on opencode")
      assertEquals(emptyList(), launcher.requests, "no phase should spawn for $extra: ${result.stdout}")
    }
  }

  @Test
  fun `feature-task-runtime resume refuses when the resolved agent is opencode`() {
    // SKILL-95 AC9: the resume entry point — the cross-mode resume hazard — must refuse opencode too,
    // before touching the durable workflow. Driven directly with a placeholder workflow id since the
    // preflight fires before any DB read.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "resume",
        "wftr-nonexistent",
        "SKILL-650",
        fixture.specPath.toString(),
        "--agent",
        "opencode",
      ),
      fixture.context(launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Runtime mode is not supported on opencode")
    assertEquals(emptyList(), launcher.requests, result.stdout)
  }

  @Test
  fun `feature-task-runtime run falls back to the documented codex default when nothing resolves`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher, environment = emptyMap()),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("codex"), launcher.requests.map { it.agentId }.distinct())
  }

  @Test
  fun `feature-task-runtime run agent-override wins over invoking agent`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--agent-override", "claude")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("claude"), launcher.requests.map { it.agentId }.distinct())
  }

  @Test
  fun `feature-task-runtime run routes a per-phase agent for only that phase`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-agent", "plan=claude")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val orderedPhases = ALL_PHASES
    val agentByPhase = orderedPhases.mapIndexed { index, phaseId ->
      phaseId to launcher.requests[index].agentId
    }.toMap()
    assertEquals(ALL_PHASES.size, launcher.requests.size, result.stdout)
    assertEquals("claude", agentByPhase["plan"], result.stdout)
    assertEquals(
      orderedPhases.filter { it != "plan" }.map { "codex" },
      orderedPhases.filter { it != "plan" }.map { agentByPhase.getValue(it) },
      result.stdout,
    )
  }

  @Test
  fun `feature-task-runtime run rejects a malformed per-phase agent assignment`() {
    val fixture = runtimeFixture()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-agent", "plan")),
      fixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "--phase-agent must be phase=agent")
  }

  @Test
  fun `feature-task-runtime run rejects an unknown per-phase agent phase`() {
    val fixture = runtimeFixture()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-agent", "bogus=claude")),
      fixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "is not a runtime phase")
  }

  @Test
  fun `feature-task-runtime run rejects a non-positive max wall-clock minutes at the CLI boundary`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--max-wall-clock-minutes", "0")),
      fixture.context(launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    // Rejected before any phase launch or durable workflow row open.
    assertEquals(emptyList(), launcher.requests, result.stdout)
  }

  @Test
  fun `feature-task-runtime run forwards the wall-clock cap as the per-phase skill-run timeout`() {
    // F-005: --max-wall-clock-minutes flows through to each phase launch's skillRunRequest.timeout.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--max-wall-clock-minutes", "5")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "feature_size: SMALL")
    assertTrue(launcher.requests.isNotEmpty(), result.stdout)
    launcher.requests.forEach { request ->
      assertEquals(5.minutes, request.skillRunRequest.timeout, result.stdout)
    }
  }

  @Test
  fun `feature-task-runtime monitor emits per-phase progress lines including a fix-loop retry line`() {
    // F-017: with --monitor, per-phase progress lines (started/completed and a fix-loop iteration
    // line on a retry) are streamed to live stdout. Drive a retry via invalid-then-valid review.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(invalidReviewUntilLaunchIndex = 4)
    val live = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--monitor")),
      fixture.context(launcher, liveStdout = { live.append(it) }),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val streamed = live.toString()
    assertContains(streamed, "phase plan started")
    assertContains(streamed, "run started feature_size=SMALL")
    assertContains(streamed, "phase plan completed")
    assertContains(streamed, "phase review fix_loop")
    assertContains(streamed, "phase review completed")
  }

  @Test
  fun `feature-task-runtime run and monitor surface decomposed planning stop`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(decomposePlan = true)
    val live = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--monitor")),
      fixture.context(launcher, liveStdout = { live.append(it) }),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: decomposed")
    assertContains(result.stdout, "subtask_count: 2")
    assertContains(result.stdout, "decomposition_manifest_path:")
    assertContains(result.stdout, "Work the first subtask first")
    assertContains(live.toString(), "decomposed at planning into 2 subtasks")
    val launchedPhases = launcher.requests.map { request ->
      phaseIdFromPrompt(request.skillRunRequest.promptOverride.orEmpty())
    }
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
    val launcher = RecordingPhaseLauncher(decomposePlan = true)
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
      ALL_PHASES.filterNot { it == "pr" },
      launcher.requests.map { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) },
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

    assertEquals(0, resume.exitCode, resume.stdout)
    assertContains(resume.stdout, "workflow_id: $workflowId")
    assertContains(
      resume.stdout,
      "completed_phases: preplan, plan, implement, review, audit, validate, write_history, commit_push",
    )
    assertEquals(emptyList(), resumeLauncher.requests, resume.stdout)
  }

  @Test
  fun `feature-task-runtime goal continuation reuses branch while direct run creates branch and opens pr phase`() {
    val goalFixture = runtimeFixture(specFileName = "spec_subtask_5_runtime.md")
    val goalLauncher = RecordingPhaseLauncher(decomposePlan = true)
    val goalGit = FakeRuntimeGitOperations(currentBranchValue = "feat/pre-created-runtime-branch")

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
          "wfl-parent",
          "--suppress-pr",
        ),
      ),
      goalFixture.context(goalLauncher, workflowGitOperations = goalGit),
    )

    assertEquals(0, goalRun.exitCode, goalRun.stdout)
    assertContains(goalRun.stdout, "status: complete")
    assertContains(goalRun.stdout, "resolved_branch: feat/pre-created-runtime-branch")
    assertContains(
      goalRun.stdout,
      "completed_phases: preplan, plan, implement, review, audit, validate, write_history, commit_push",
    )
    assertContains(goalRun.stdout, "subtask_outcome:")
    assertContains(goalRun.stdout, "  last_resumable_step: commit_push")
    assertEquals(emptyList(), goalGit.checkoutBranches, goalRun.stdout)
    assertEquals(
      ALL_PHASES.filterNot { it == "pr" },
      goalLauncher.requests.map { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) },
      goalRun.stdout,
    )

    val directFixture = runtimeFixture()
    val directLauncher = RecordingPhaseLauncher()
    val directGit = FakeRuntimeGitOperations(currentBranchValue = "main")

    val directRun = CliRuntime.run(
      directFixture.runCommand(extra = listOf("--agent", "codex")),
      directFixture.context(directLauncher, workflowGitOperations = directGit),
    )

    assertEquals(0, directRun.exitCode, directRun.stdout)
    assertContains(directRun.stdout, "status: complete")
    assertContains(directRun.stdout, "resolved_branch: feat/SKILL-650-runtime")
    assertContains(
      directRun.stdout,
      "completed_phases: preplan, plan, implement, review, audit, validate, write_history, commit_push, pr",
    )
    assertEquals(listOf("feat/SKILL-650-runtime"), directGit.checkoutBranches, directRun.stdout)
    assertEquals(
      ALL_PHASES,
      directLauncher.requests.map { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) },
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
    // A clean run launches the nine forward phases; the loop-only implement_fix is never launched, so
    // it stays pending in the durable projection even on a fully forward-completed run (SKILL-85 M1).
    assertContains(status.stdout, "complete: ${ALL_PHASES.size}")
    assertContains(status.stdout, "pending: 1")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "phase: id=plan status=completed")
    assertContains(status.stdout, "phase: id=implement_fix status=pending")
    // SKILL-85 Subtask 4 (F-005): a fully forward-completed run reports no current phase — the
    // loop-only implement_fix (still pending) must NOT be projected as the current phase to operators.
    assertContains(status.stdout, "current_phase: none")
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
    // Preplan and plan complete; implement never validates and blocks after the bounded fix loop.
    val launcher = RecordingPhaseLauncher(invalidFromLaunchIndex = 2)
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(1, run.exitCode, run.stdout)
    assertContains(run.stdout, "status: blocked")
    // F-006: the rendered run output names the specific blocked phase and reason. `implement` is a
    // fix-loop phase under the mutating-phase idempotency contract, so it exhausts the bounded fix
    // loop on repeated invalid output before blocking, with the matching wording.
    assertContains(run.stdout, "last_incomplete_phase: implement")
    assertContains(run.stdout, "blocked_reason:")
    assertContains(run.stdout, "exhausted the bounded fix loop")
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
      "completed_phases: preplan, plan, implement, review, audit, validate, write_history, commit_push, pr",
    )
    assertEquals(ALL_PHASES.size, launcher.requests.size)
  }
}

/**
 * SKILL-103 (AC4, AC5, AC6): zcode is prose-only for feature-task runtime, mirroring opencode. The
 * shared RUNTIME_REFUSED_AGENTS gate refuses every resolution route. Kept in its own class to stay
 * under the detekt LargeClass threshold on the main runtime test class.
 */
class CliFeatureTaskRuntimeZcodeRefusalTest {
  @Test
  fun `feature-task-runtime run refuses when the resolved agent is zcode via SKILL_BILL_AGENT`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher, environment = mapOf("CLAUDECODE" to "1", "SKILL_BILL_AGENT" to "zcode")),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Runtime mode is not supported on opencode or zcode")
    assertContains(result.stdout, "bill-feature-task-prose")
    assertContains(result.stdout, "bill-feature-goal mode:prose")
    assertEquals(emptyList(), launcher.requests, result.stdout)
    assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
  }

  @Test
  fun `feature-task-runtime run refuses when the host invoking agent is detected as zcode`() {
    // SKILL-103 AC4: a flag-less invocation from a zcode-marked env resolves the invoking agent to
    // zcode through the detection chain; the shared refusal gate then refuses before any spawn.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(
        launcher,
        environment = mapOf("ZCODE_APP_VERSION" to "1.0.0", "ZCODE_BASE_URL" to "https://zcode.example"),
      ),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Runtime mode is not supported on opencode or zcode")
    assertEquals(emptyList(), launcher.requests, result.stdout)
    assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
  }

  @Test
  fun `feature-task-runtime run refuses zcode from agent-override phase-agent and parallel-review routes`() {
    // SKILL-103 AC5/AC6: zcode reaching a runtime phase by ANY route must refuse at the preflight,
    // and the predicate is case- and whitespace-insensitive (`Zcode`, ` zcode `).
    listOf(
      listOf("--agent", "codex", "--agent-override", "zcode"),
      listOf("--agent", "codex", "--phase-agent", "plan=zcode"),
      listOf("--agent", "codex", "--parallel-review-agent", "zcode"),
      listOf("--agent", "Zcode"),
      listOf("--agent", " zcode "),
    ).forEach { extra ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = extra),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, "expected refusal for $extra: ${result.stdout}")
      assertContains(result.stdout, "Runtime mode is not supported on opencode or zcode")
      assertEquals(emptyList(), launcher.requests, "no phase should spawn for $extra: ${result.stdout}")
    }
  }

  @Test
  fun `feature-task-runtime resume refuses when the resolved agent is zcode`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "resume",
        "wftr-nonexistent",
        "SKILL-650",
        fixture.specPath.toString(),
        "--agent",
        "zcode",
      ),
      fixture.context(launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Runtime mode is not supported on opencode or zcode")
    assertEquals(emptyList(), launcher.requests, result.stdout)
  }
}

class CliFeatureTaskRuntimeModelDirectiveTest {
  @Test
  fun `feature-task runtime applies a cli phase model directive`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val monitor = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-model", "plan=gpt-sol@high", "--monitor")),
      fixture.context(launcher, liveStdout = { monitor.append(it) }),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("gpt-sol", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
    assertTrue(
      launcher.requests.filterIndexed { index, _ -> ALL_PHASES[index] != "plan" }.all { request ->
        request.skillRunRequest.modelOverride == null && request.skillRunRequest.effortOverride == null
      },
    )
    assertContains(monitor.toString(), "phase plan started agent=codex attempt=1 model=gpt-sol effort=high")
  }

  @Test
  fun `feature-task runtime uses the final repeated phase model assignment`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(
        extra = listOf("--agent", "codex", "--phase-model", "plan=first", "--phase-model", "plan=second@high"),
      ),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("second", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
  }

  @Test
  fun `feature-task runtime applies a model only cli phase directive`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-model", "plan=gpt-sol")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("gpt-sol", plan.modelOverride)
    assertNull(plan.effortOverride)
  }

  @Test
  fun `feature-task runtime reads phase directives from the machine execution matrix`() {
    val fixture = runtimeFixture()
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "agents": {
            "codex": {
              "reasoning": {
                "model": "gpt-sol",
                "effort": "high"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("gpt-sol", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
  }

  @Test
  fun `feature-task runtime rejects a malformed machine execution matrix`() {
    val fixture = runtimeFixture()
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "agents": {
            "codex": {
              "reasoning": {
                "effort": "high"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val launcher = RecordingPhaseLauncher()

    val error = assertFailsWith<MalformedMachineConfigError> {
      CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex")),
        fixture.context(launcher),
      )
    }

    assertContains(error.message.orEmpty(), "Machine config")
    assertContains(error.message.orEmpty(), "execution_matrix.agents.codex.reasoning.model")
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal continuation re-resolves execution matrix directives for child phase launches`() {
    val fixture = runtimeFixture(specFileName = "spec_subtask_5_runtime.md")
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "agents": {
            "codex": {
              "reasoning": {
                "model": "gpt-sol",
                "effort": "high"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val directLauncher = RecordingPhaseLauncher()
    val direct = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(directLauncher),
    )
    val childLauncher = RecordingPhaseLauncher()
    val child = CliRuntime.run(
      fixture.runCommand(
        extra = listOf(
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
        ),
      ),
      fixture.context(childLauncher),
    )

    assertEquals(0, direct.exitCode, direct.stdout)
    assertEquals(0, child.exitCode, child.stdout)
    val directPlan = directLauncher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    val childPlan = childLauncher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals(directPlan.modelOverride, childPlan.modelOverride)
    assertEquals(directPlan.effortOverride, childPlan.effortOverride)
    assertEquals("gpt-sol", childPlan.modelOverride)
    assertEquals("high", childPlan.effortOverride)
  }

  @Test
  fun `feature-task runtime rejects malformed phase model directives before workflow opening`() {
    listOf("plan", "=model", "plan=", "unknown=model", "plan=model@high@xhigh", "plan=@high", "plan=model@").forEach {
        assignment ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex", "--phase-model", assignment)),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, "expected rejection for $assignment: ${result.stdout}")
      assertContains(result.stdout, "--phase-model")
      assertEquals(emptyList(), launcher.requests, result.stdout)
      assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
    }
  }

  @Test
  fun `feature-task runtime rejects malformed and unknown review modes before workflow opening`() {
    listOf(
      listOf("--code-review-mode", "unknown"),
      listOf("--code-review-mode="),
    ).forEach { modeArgs ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex") + modeArgs),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, result.stdout)
      assertContains(result.stdout, "Unknown code-review execution mode")
      assertEquals(emptyList(), launcher.requests)
      assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
    }
  }

  @Test
  fun `feature-task runtime forwards omitted and explicit review modes unchanged to review`() {
    listOf(
      "omitted" to emptyList(),
      "auto" to listOf("--code-review-mode", "auto"),
      "inline" to listOf("--code-review-mode", "inline"),
      "delegated" to listOf("--code-review-mode", "delegated"),
    ).forEach { (expectedMode, modeArgs) ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex") + modeArgs),
        fixture.context(launcher),
      )

      assertEquals(0, result.exitCode, "$expectedMode: ${result.stdout}")
      val reviewPrompt = launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .single { it.contains("Phase: review") }
      val forwardedMode = if (expectedMode == "omitted") "auto" else expectedMode
      assertContains(reviewPrompt, "bill-code-review execution-mode:$forwardedMode")
    }
  }

  @Test
  fun `feature-task runtime rejects missing duplicate and conflicting review modes before workflow opening`() {
    listOf(
      "missing" to listOf("--code-review-mode"),
      "duplicate" to listOf("--code-review-mode", "auto", "--code-review-mode", "auto"),
      "conflicting" to listOf("--code-review-mode", "inline", "--code-review-mode", "delegated"),
    ).forEach { (caseName, modeArgs) ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex") + modeArgs),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, "$caseName: ${result.stdout}")
      assertEquals(emptyList(), launcher.requests, "$caseName: ${result.stdout}")
      assertFalse(result.stdout.contains("workflow_id:"), "$caseName: ${result.stdout}")
    }
  }

  @Test
  fun `feature-task run and resume refuse a directive that resolves to junie before launching`() {
    val runFixture = runtimeFixture()
    val runLauncher = RecordingPhaseLauncher()
    val run = CliRuntime.run(
      runFixture.runCommand(
        extra = listOf("--agent", "codex", "--phase-agent", "plan=junie", "--phase-model", "plan=model"),
      ),
      runFixture.context(runLauncher),
    )

    assertEquals(1, run.exitCode, run.stdout)
    assertContains(run.stdout, "phase 'plan'")
    assertContains(run.stdout, "agent 'junie'")
    assertEquals(emptyList(), runLauncher.requests)
    assertFalse(run.stdout.contains("workflow_id:"), run.stdout)

    val resumeFixture = runtimeFixture()
    val resumeLauncher = RecordingPhaseLauncher()
    val resume = CliRuntime.run(
      listOf(
        "--db",
        resumeFixture.dbPath.toString(),
        "feature-task",
        "resume",
        "wftr-model-directive",
        "SKILL-650",
        resumeFixture.specPath.toString(),
        "--repo-root",
        resumeFixture.tempDir.toString(),
        "--agent",
        "codex",
        "--phase-agent",
        "plan=junie",
        "--phase-model",
        "plan=model",
      ),
      resumeFixture.context(resumeLauncher),
    )

    assertEquals(1, resume.exitCode, resume.stdout)
    assertContains(resume.stdout, "agent 'junie'")
    assertEquals(emptyList(), resumeLauncher.requests)
  }
}

class CliFeatureTaskRuntimeSpecLookupTest {
  @Test
  fun `feature-task resolves single feature spec match when only issue key is provided`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "SKILL-650",
        "--repo-root",
        fixture.tempDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertEquals(ALL_PHASES.size, launcher.requests.size)
  }

  @Test
  fun `feature-task explicit run resolves single feature spec match when spec path is omitted`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "run",
        "SKILL-650",
        "--repo-root",
        fixture.tempDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertEquals(ALL_PHASES.size, launcher.requests.size)
  }

  @Test
  fun `feature-task key-only lookup reports missing and ambiguous specs`() {
    val missingFixture = runtimeFixture()
    val missing = CliRuntime.run(
      listOf(
        "--db",
        missingFixture.dbPath.toString(),
        "feature-task",
        "SKILL-999",
        "--repo-root",
        missingFixture.tempDir.toString(),
      ),
      missingFixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, missing.exitCode)
    assertContains(missing.stdout, "no .feature-specs match found")

    val ambiguousFixture = runtimeFixture()
    val secondSpec = ambiguousFixture.tempDir.resolve(".feature-specs/SKILL-650-other/spec.md")
    Files.createDirectories(secondSpec.parent)
    Files.writeString(secondSpec, "# second spec\n")
    val ambiguous = CliRuntime.run(
      listOf(
        "--db",
        ambiguousFixture.dbPath.toString(),
        "feature-task",
        "SKILL-650",
        "--repo-root",
        ambiguousFixture.tempDir.toString(),
      ),
      ambiguousFixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, ambiguous.exitCode)
    assertContains(ambiguous.stdout, "multiple .feature-specs matches found")
    assertContains(ambiguous.stdout, "SKILL-650-runtime")
    assertContains(ambiguous.stdout, "SKILL-650-other")
  }

  @Test
  fun `feature-task-runtime resume re-runs against an existing workflow id without re-launching complete phases`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(0, run.exitCode, run.stdout)
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    // F-018: drive the resume through a SECOND launcher and assert it received zero launches, so
    // the no-relaunch claim is proven against a fresh capture rather than an unchanged shared count.
    val resumeLauncher = RecordingPhaseLauncher()
    val resume = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "resume",
        workflowId,
        "SKILL-650",
        fixture.specPath.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(resumeLauncher),
    )

    assertEquals(0, resume.exitCode, resume.stdout)
    assertContains(resume.stdout, "status: complete")
    assertContains(resume.stdout, "feature_size: SMALL")
    // Every phase was already complete after the first run, so the resume launches nothing.
    assertEquals(emptyList(), resumeLauncher.requests, resume.stdout)
  }
}

private data class FeatureTaskRuntimeCliFixture(
  val tempDir: Path,
  val dbPath: Path,
  val specPath: Path,
) {
  fun context(
    launcher: AgentRunLauncher,
    environment: Map<String, String> = emptyMap(),
    liveStdout: (String) -> Unit = {},
    liveStderr: (String) -> Unit = {},
    // Defaults to an already-checked-out feature branch so the runtime reuses it (no checkout) and
    // existing completion/blocked expectations stand; branch-setup tests override this to start on
    // the default branch. The real git adapter is bypassed because the tempDir is not a git repo.
    workflowGitOperations: WorkflowGitOperations = FakeRuntimeGitOperations(),
  ): CliRuntimeContext = CliRuntimeContext(
    userHome = tempDir,
    agentRunLauncher = launcher,
    environment = environment,
    liveStdout = liveStdout,
    liveStderr = liveStderr,
    workflowGitOperations = workflowGitOperations,
  )

  fun runCommand(extra: List<String> = emptyList()): List<String> = buildList {
    add("--db")
    add(dbPath.toString())
    add("feature-task")
    add("SKILL-650")
    add(specPath.toString())
    add("--repo-root")
    add(tempDir.toString())
    addAll(extra)
  }
}

private fun featureTaskCommand(fixture: FeatureTaskRuntimeCliFixture, command: String): List<String> = listOf(
  "--db",
  fixture.dbPath.toString(),
  command,
  "SKILL-650",
  fixture.specPath.toString(),
  "--repo-root",
  fixture.tempDir.toString(),
  "--agent",
  "codex",
)

// Replaces the only non-deterministic stdout line (the generated workflow_id) with a stable token so
// canonical and alias stdout can be compared byte-for-byte.
private fun normalizeRuntimeStdout(stdout: String): String = stdout
  .lines()
  .joinToString("\n") { line ->
    if (line.startsWith("workflow_id:")) "workflow_id: <normalized>" else line
  }

private fun runtimeFixture(specFileName: String = "spec.md"): FeatureTaskRuntimeCliFixture {
  val tempDir = Files.createTempDirectory("skillbill-cli-feature-task-runtime")
  val specPath = tempDir.resolve(".feature-specs/SKILL-650-runtime/$specFileName")
  Files.createDirectories(specPath.parent)
  Files.writeString(
    specPath,
    """
    # SKILL-650 runtime spec

    Feature size: SMALL

    ## Acceptance Criteria

    1. The runtime drives every ordered phase to a validated output.
    2. The CLI delegates to the application runner without owning orchestration.

    ## Mandates and Overrides

    - Stay on the experimental path only when explicitly requested.
    """.trimIndent(),
  )
  return FeatureTaskRuntimeCliFixture(
    tempDir = tempDir,
    dbPath = tempDir.resolve("metrics.db"),
    specPath = specPath,
  )
}

private val PHASE_LINE = Regex("^Phase: ([a-z_-]+) ", setOf(RegexOption.MULTILINE))

private fun phaseIdFromPrompt(prompt: String): String =
  PHASE_LINE.find(prompt)?.groupValues?.get(1) ?: error("Prompt did not contain a phase header: $prompt")

// Returns one schema-valid phase output per launch. The delivered prompt pins the runtime phase,
// so the test double reads that phase id and echoes it back in the validated output.
private class RecordingPhaseLauncher(
  private val invalidFromLaunchIndex: Int? = null,
  // When set, review launches before this global launch index emit invalid output and later review
  // launches emit valid output, driving an invalid-then-valid review fix-loop retry.
  private val invalidReviewUntilLaunchIndex: Int? = null,
  private val decomposePlan: Boolean = false,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val launchIndex = requests.size
    requests += request
    val invalid = (invalidFromLaunchIndex?.let { launchIndex >= it } ?: false) ||
      isInvalidReviewRetry(launchIndex)
    val phaseId = phaseIdFromPrompt(request.skillRunRequest.promptOverride.orEmpty())
    val stdout = when {
      invalid -> INVALID_PHASE_OUTPUT
      decomposePlan && phaseId == "plan" -> DECOMPOSE_PLAN_OUTPUT
      else -> validPhaseOutput(phaseId)
    }
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = stdout,
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  private fun isInvalidReviewRetry(launchIndex: Int): Boolean {
    val limit = invalidReviewUntilLaunchIndex ?: return false
    val phaseId = phaseIdFromPrompt(requests[launchIndex].skillRunRequest.promptOverride.orEmpty())
    return launchIndex < limit && phaseId == "review"
  }

  private companion object {
    // Missing the required status/summary/produced_outputs fields, so the per-phase
    // output validator rejects it and the runner never marks the phase complete.
    val INVALID_PHASE_OUTPUT =
      """
      contract_version: "0.1"
      phase_id: "implement"
      """.trimIndent()

    fun validPhaseOutput(phaseId: String): String {
      // A clean review/audit must emit a verification signal (an empty findings/unmet_criteria array
      // affirms no blocking findings / every criterion met) or the runtime gate blocks it (SKILL-85
      // Subtask 4 F-003 for review, Subtask 5 AC1 for audit).
      val producedOutputs = when (phaseId) {
        "review" -> "findings: []"
        "audit" -> "unmet_criteria: []"
        else -> """tasks: ["task-1"]"""
      }
      val base =
        """
        contract_version: "0.1"
        phase_id: "$phaseId"
        status: "completed"
        summary: "Phase produced a validated output."
        produced_outputs:
          $producedOutputs
        """.trimIndent()
      if (phaseId != "implement") {
        return base
      }
      val reconciliationReport =
        """
          reconciled_state:
            reconciled: true
            evidence: "All planned changes are present at their intended state."
        """.trimIndent().prependIndent("  ")
      return "$base\n$reconciliationReport"
    }

    val DECOMPOSE_PLAN_OUTPUT: String = """
      {
        "contract_version": "0.1",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Plan needs ordered subtasks.",
        "produced_outputs": {
          "mode": "decompose",
          "reason": "Plan needs ordered subtasks.",
          "feature_name": "runtime cli decomposition",
          "parent_spec_overview": "Split the CLI runtime work into ordered subtasks.",
          "validation_strategy": "bill-code-check",
          "base_branch": "main",
          "feature_branch": "feat/SKILL-650-runtime-cli-decomposition",
          "subtasks": [
            {
              "id": 1,
              "name": "first",
              "scope": "First subtask.",
              "acceptance_criteria": ["First criterion."],
              "non_goals": [],
              "dependency_notes": "First.",
              "validation_strategy": "unit tests",
              "next_path": "Work subtask 2.",
              "depends_on": []
            },
            {
              "id": 2,
              "name": "second",
              "scope": "Second subtask.",
              "acceptance_criteria": ["Second criterion."],
              "non_goals": [],
              "dependency_notes": "Depends on first.",
              "validation_strategy": "unit tests",
              "next_path": "Finish.",
              "depends_on": [1]
            }
          ]
        }
      }
    """.trimIndent()
  }
}

private val ALL_PHASES =
  listOf("preplan", "plan", "implement", "review", "audit", "validate", "write_history", "commit_push", "pr")

// Records checkouts and reports a configurable current branch so branch-setup is exercised through
// the CLI without a real git repo. The default reports an existing feature branch (reuse path).
private class FakeRuntimeGitOperations(
  private var currentBranchValue: String = "feat/pre-created-runtime-branch",
  private val checkoutResult: WorkflowGitOperationResult? = null,
) : WorkflowGitOperations {
  val checkoutBranches: MutableList<String> = mutableListOf()

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    checkoutBranches += branch
    val result = checkoutResult ?: WorkflowGitOperationResult(status = "ok", value = branch)
    if (result.ok) {
      currentBranchValue = branch
    }
    return result
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = currentBranchValue)

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "recorded")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult = WorkflowWorktreeActivityResult(
    status = "ok",
    changedFileSummary = GoalObservabilityChangedFileSummary(
      total = 0,
      added = 0,
      modified = 0,
      deleted = 0,
      renamed = 0,
      untracked = 0,
    ),
    diffStat = GoalObservabilityDiffStat(filesChanged = 0, insertions = 0, deletions = 0),
  )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(
    status = "ok",
    selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  )

  override fun buildGoalSubtaskReviewInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult = GoalSubtaskReviewInputResult(
    status = "ok",
    input = GoalSubtaskReviewInput(
      reviewBaseSha = baseline.reviewBaseSha,
      currentHeadSha = baseline.reviewBaseSha,
      trackedDelta = "",
      ownedUntrackedPatches = "",
    ),
  )
}
