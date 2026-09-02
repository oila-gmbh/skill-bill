package skillbill.cli

import skillbill.cli.core.CliRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliFeatureTaskRuntimeWorkflowTest {
  @Test
  fun `feature-task-runtime run reports the resolved feature branch in text and status without a new flag`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    // Start on the default branch so the runtime creates+switches to the convention feature branch.
    val git = FakeRuntimeGitOperations(currentBranchValue = "main")

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(launcher) { workflowGitOperations = git },
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
      fixture.context(launcher) {
        liveStdout = { live.append(it) }
        workflowGitOperations = git
      },
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
      "completed_phases: $COMPLETED_PHASES_CLEAN_RUN",
    )
    assertEquals(listOf("codex"), launcher.requests.map { it.agentId }.distinct())
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
  }

  @Test
  fun `feature-task-runtime run defaults invoked agent to detected invoking context`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher) { environment = mapOf("CLAUDECODE" to "1") },
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("claude"), launcher.requests.map { it.agentId }.distinct())
  }

  @Test
  fun `feature-task-runtime run refuses to launch when no invoking agent resolves`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher) { environment = emptyMap() },
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Cannot determine the invoking agent")
    assertEquals(emptyList(), launcher.requests.map { it.agentId })
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
    val phaseRequests = launcher.requests.filter {
      PHASE_LINE.containsMatchIn(it.skillRunRequest.promptOverride.orEmpty())
    }
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder(), result.stdout)
    val agentByPhase = AGENT_LAUNCHED_PHASES.zip(phaseRequests).associate { (phaseId, request) ->
      phaseId to request.agentId
    }
    assertEquals("claude", agentByPhase["plan"], result.stdout)
    assertEquals(
      AGENT_LAUNCHED_PHASES.filter { it != "plan" }.map { "codex" },
      AGENT_LAUNCHED_PHASES.filter { it != "plan" }.map { agentByPhase.getValue(it) },
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
  fun `feature-task-runtime run defaults wall-clock cap and treats zero as disabled`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val defaulted = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(launcher),
    )
    assertEquals(0, defaulted.exitCode, defaulted.stdout)
    assertTrue(launcher.requests.isNotEmpty(), defaulted.stdout)
    launcher.requests.forEach { request ->
      if (PHASE_LINE.containsMatchIn(request.skillRunRequest.promptOverride.orEmpty())) {
        assertEquals(180.minutes, request.skillRunRequest.timeout, defaulted.stdout)
      }
    }

    launcher.requests.clear()
    val disabled = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--max-wall-clock-minutes", "0")),
      fixture.context(launcher),
    )
    assertEquals(0, disabled.exitCode, disabled.stdout)
    assertTrue(launcher.requests.isNotEmpty(), disabled.stdout)
    launcher.requests.forEach { request ->
      if (PHASE_LINE.containsMatchIn(request.skillRunRequest.promptOverride.orEmpty())) {
        assertEquals(null, request.skillRunRequest.timeout, disabled.stdout)
      }
    }
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
      if (PHASE_LINE.containsMatchIn(request.skillRunRequest.promptOverride.orEmpty())) {
        assertEquals(5.minutes, request.skillRunRequest.timeout, result.stdout)
      }
    }
  }
}
