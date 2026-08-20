package skillbill.cli

import skillbill.application.review.simulateGovernedEvidenceReads
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
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
import skillbill.ports.workflow.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.ScopedStagingGitOperations
import skillbill.ports.workflow.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
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

@Suppress("LargeClass")
class CliFeatureTaskRuntimeRuntimeTest {
  @Test
  fun `real feature invocation preserves ordered selected agent add-ons through retry`() {
    val selectedFixture = runtimeFixture().also {
      writeAgentAddon(it.tempDir, "first-helper", "First selected guidance.")
      writeAgentAddon(it.tempDir, "middle-unselected", "UNSELECTED SENTINEL")
      writeAgentAddon(it.tempDir, "last-helper", "Last selected guidance.")
    }
    val resolvedSelection = resolvedSelectionJson(selectedFixture, "codex", "first-helper", "last-helper")
    val selectedLauncher = RecordingPhaseLauncher(invalidReviewUntilLaunchIndex = 5)

    val selected = CliRuntime.run(
      selectedFixture.runCommand(
        extra = listOf("--agent", "codex", "--agent-addon-selection-json", resolvedSelection),
      ),
      selectedFixture.context(selectedLauncher),
    )

    assertEquals(0, selected.exitCode, selected.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES, selectedLauncher.phaseOrder())
    val firstManifest = selectedFixture.tempDir.resolve(
      "agent-addons/first-helper/agent-addon.yaml",
    ).toRealPath().toString()
    val lastManifest = selectedFixture.tempDir.resolve(
      "agent-addons/last-helper/agent-addon.yaml",
    ).toRealPath().toString()
    selectedLauncher.requests
      .map { it.skillRunRequest.promptOverride.orEmpty() }
      .filter { PHASE_LINE.containsMatchIn(it) }
      .forEach { prompt ->
        val firstHeader = "### agent_addon_first_helper (addon_content:first-helper)"
        val lastHeader = "### agent_addon_last_helper (addon_content:last-helper)"
        assertContains(prompt, firstHeader)
        assertContains(prompt, lastHeader)
        assertContains(prompt, "First selected guidance.")
        assertContains(prompt, "Last selected guidance.")
        assertFalse(prompt.contains(firstManifest), prompt)
        assertFalse(prompt.contains(lastManifest), prompt)
        assertFalse(prompt.contains("middle-unselected"), prompt)
        assertFalse(prompt.contains("UNSELECTED SENTINEL"), prompt)
        assertTrue(prompt.indexOf(firstHeader) < prompt.indexOf(lastHeader), prompt)
      }
    val driverPrompts = selectedLauncher.requests
      .map { it.skillRunRequest.promptOverride.orEmpty() }
      .filterNot { PHASE_LINE.containsMatchIn(it) }
    driverPrompts.forEach { prompt ->
      assertContains(prompt, "## Selected agent add-ons")
    }
  }

  @Test
  fun `resume accepts changed ordered agent add-ons and applies them to future phases`() {
    val fixture = runtimeFixture().also {
      writeAgentAddon(it.tempDir, "first-helper", "First selected guidance.")
      writeAgentAddon(it.tempDir, "last-helper", "Last selected guidance.")
      writeAgentAddon(it.tempDir, "replacement-helper", "Replacement guidance.")
    }
    val orderedSelection = resolvedSelectionJson(fixture, "codex", "first-helper", "last-helper")
    val interruptedLauncher = InterruptAtImplementLauncher()
    val firstRun = CliRuntime.run(
      fixture.runCommand(
        extra = listOf("--agent", "codex", "--agent-addon-selection-json", orderedSelection),
      ),
      fixture.context(interruptedLauncher),
    )
    assertEquals(1, firstRun.exitCode, firstRun.stdout)
    val workflowId = firstRun.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    assertChangedAgentAddonSelectionApplied(fixture, workflowId)

    val resumeFixture = runtimeFixture().also {
      writeAgentAddon(it.tempDir, "first-helper", "First selected guidance.")
      writeAgentAddon(it.tempDir, "middle-unselected", "UNSELECTED RESUME SENTINEL")
      writeAgentAddon(it.tempDir, "last-helper", "Last selected guidance.")
    }
    val resumeSelection = resolvedSelectionJson(resumeFixture, "codex", "first-helper", "last-helper")
    val resumeInterruptedLauncher = InterruptAtImplementLauncher()
    val resumableRun = CliRuntime.run(
      resumeFixture.runCommand(
        extra = listOf("--agent", "codex", "--agent-addon-selection-json", resumeSelection),
      ),
      resumeFixture.context(resumeInterruptedLauncher),
    )
    assertEquals(1, resumableRun.exitCode, resumableRun.stdout)
    val resumableWorkflowId = resumableRun.stdout.lines()
      .single { it.startsWith("workflow_id:") }
      .substringAfter(":")
      .trim()
    val resumedLauncher = RecordingPhaseLauncher()
    val resumed = CliRuntime.run(
      resumeFixture.resumeCommand(resumableWorkflowId, resumeSelection),
      resumeFixture.context(resumedLauncher),
    )
    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES.dropWhile { it != "implement" }, resumedLauncher.phaseOrder())
    resumedLauncher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      val firstHeader = "### agent_addon_first_helper (addon_content:first-helper)"
      val lastHeader = "### agent_addon_last_helper (addon_content:last-helper)"
      assertContains(prompt, firstHeader)
      assertContains(prompt, lastHeader)
      assertTrue(prompt.indexOf(firstHeader) < prompt.indexOf(lastHeader), prompt)
      assertFalse(prompt.contains("middle-unselected"), prompt)
      assertFalse(prompt.contains("UNSELECTED RESUME SENTINEL"), prompt)
    }
  }

  private fun assertChangedAgentAddonSelectionApplied(fixture: FeatureTaskRuntimeCliFixture, workflowId: String) {
    val changedSelection = resolvedSelectionJson(fixture, "codex", "replacement-helper", "last-helper")
    val launcher = RecordingPhaseLauncher()

    val resumed = CliRuntime.run(
      fixture.resumeCommand(workflowId, changedSelection),
      fixture.context(launcher),
    )

    assertEquals(0, resumed.exitCode, resumed.stdout)
    val prompts = launcher.requests.map { it.skillRunRequest.promptOverride.orEmpty() }
    assertTrue(prompts.any { it.contains("Replacement guidance.") })
    assertTrue(prompts.all { !it.contains("First selected guidance.") })
  }

  @Test
  fun `real feature invocation rejects unsupported agent before execution`() {
    val unsupportedFixture = runtimeFixture().also { writeExecutionBudgetAddon(it.tempDir) }
    val unsupportedSelection = resolvedSelectionJson(unsupportedFixture, "codex")
    val unsupportedLauncher = RecordingPhaseLauncher()
    val unsupported = assertFailsWith<skillbill.error.InvalidAgentAddonSelectionError> {
      CliRuntime.run(
        unsupportedFixture.runCommand(
          extra = listOf("--agent", "claude", "--agent-addon-selection-json", unsupportedSelection),
        ),
        unsupportedFixture.context(unsupportedLauncher),
      )
    }
    assertContains(unsupported.message.orEmpty(), "incompatible with receiving agent 'claude'")
    assertEquals(emptyList(), unsupportedLauncher.requests)
    assertFalse(Files.exists(unsupportedFixture.dbPath), "workflow database must not be created")
  }

  @Test
  fun `selected agent add-on accepts compatible runtime override routes`() {
    val cases = listOf(
      "run-wide override" to listOf("--agent-override", "codex"),
      "phase override" to listOf("--phase-agent", "plan=codex"),
      "parallel review agent" to listOf(
        "--code-review-mode",
        "inline",
        "--parallel-review-agent",
        "codex",
      ),
    )

    cases.forEach { (case, overrideArgs) ->
      val fixture = runtimeFixture().also { writeExecutionBudgetAddon(it.tempDir) }
      val selection = resolvedSelectionJson(fixture, "codex")
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(
          extra = listOf("--agent", "codex", "--agent-addon-selection-json", selection) + overrideArgs,
        ),
        fixture.context(launcher),
      )

      assertEquals(0, result.exitCode, "$case: ${result.stdout}")
      assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder(), case)
      assertTrue(
        launcher.requests
          .map { it.skillRunRequest.promptOverride.orEmpty() }
          .filter { PHASE_LINE.containsMatchIn(it) }
          .all { prompt ->
            prompt.contains("### agent_addon_execution_budget (addon_content:execution-budget)")
          },
        case,
      )
    }
  }

  @Test
  fun `selected agent add-on rejects incompatible runtime override routes before workflow creation`() {
    val cases = listOf(
      "run-wide override" to listOf("--agent-override", "claude"),
      "phase override" to listOf("--phase-agent", "plan=claude"),
      "parallel review agent" to listOf(
        "--code-review-mode",
        "inline",
        "--parallel-review-agent",
        "claude",
      ),
    )

    cases.forEach { (case, overrideArgs) ->
      val fixture = runtimeFixture().also { writeExecutionBudgetAddon(it.tempDir) }
      val selection = resolvedSelectionJson(fixture, "codex")
      val launcher = RecordingPhaseLauncher()

      val failure = assertFailsWith<skillbill.error.InvalidAgentAddonSelectionError>(case) {
        CliRuntime.run(
          fixture.runCommand(
            extra = listOf("--agent", "codex", "--agent-addon-selection-json", selection) + overrideArgs,
          ),
          fixture.context(launcher),
        )
      }

      assertContains(failure.message.orEmpty(), "incompatible with receiving agent 'claude'", message = case)
      assertEquals(emptyList(), launcher.requests, case)
      assertFalse(Files.exists(fixture.dbPath), "$case must fail before workflow database creation")
    }
  }

  @Test
  fun `real feature invocation without agent add-on keeps prompts unchanged`() {
    val baselineFixture = runtimeFixture().also { writeExecutionBudgetAddon(it.tempDir) }
    val baselineLauncher = RecordingPhaseLauncher()
    val baseline = CliRuntime.run(
      baselineFixture.runCommand(extra = listOf("--agent", "codex")),
      baselineFixture.context(baselineLauncher),
    )
    assertEquals(0, baseline.exitCode, baseline.stdout)
    assertTrue(
      baselineLauncher.requests.all { request ->
        !request.skillRunRequest.promptOverride.orEmpty().contains("Agent add-on:")
      },
    )
  }

  @Test
  fun `feature-task command registers run status resume and abandon`() {
    val help = CliRuntime.run(listOf("feature-task", "--help"), CliRuntimeContext())

    assertEquals(0, help.exitCode, help.stdout)
    // AC5: canonical command help must contain no EXPERIMENTAL language.
    assertFalse(help.stdout.contains("EXPERIMENTAL"), help.stdout)
    assertContains(help.stdout, "status")
    assertContains(help.stdout, "resume")
    assertContains(help.stdout, "abandon")
    assertContains(help.stdout, "retry-blocked")
    assertContains(help.stdout, "repair-identity")
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
      "completed_phases: preplan, plan, implement, audit, review, validate, write_history, commit_push, pr",
    )
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
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
      "completed_phases: preplan, plan, implement, audit, review, validate, write_history, commit_push, pr",
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
      fixture.context(launcher, environment = mapOf("CLAUDECODE" to "1")),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("claude"), launcher.requests.map { it.agentId }.distinct())
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
      if (PHASE_LINE.containsMatchIn(request.skillRunRequest.promptOverride.orEmpty())) {
        assertEquals(5.minutes, request.skillRunRequest.timeout, result.stdout)
      }
    }
  }

  @Test
  fun `feature-task-runtime monitor emits per-phase progress lines including a fix-loop retry line`() {
    // F-017: with --monitor, per-phase progress lines (started/completed and a fix-loop iteration
    // line on a retry) are streamed to live stdout. Drive a retry via invalid-then-valid review.
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher(invalidReviewUntilLaunchIndex = 5)
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
      fixture.context(launcher, liveStdout = { live.append(it) }),
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
      goalFixture.context(goalLauncher, workflowGitOperations = goalGit),
    )

    assertEquals(0, goalRun.exitCode, goalRun.stdout)
    assertContains(goalRun.stdout, "status: complete")
    assertContains(goalRun.stdout, "resolved_branch: feat/pre-created-runtime-branch")
    assertContains(
      goalRun.stdout,
      "completed_phases: preplan, plan, implement, audit, review, validate, write_history, commit_push",
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
      directFixture.context(directLauncher, workflowGitOperations = directGit),
    )

    assertEquals(0, directRun.exitCode, directRun.stdout)
    assertContains(directRun.stdout, "status: complete")
    assertContains(directRun.stdout, "resolved_branch: feat/SKILL-650-runtime")
    assertContains(
      directRun.stdout,
      "completed_phases: preplan, plan, implement, audit, review, validate, write_history, commit_push, pr",
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
    // A clean run launches the nine forward phases; the loop-only implement_fix is never launched, so
    // it stays pending in the durable projection even on a fully forward-completed run (SKILL-85 M1).
    assertContains(status.stdout, "complete: ${ALL_PHASES.size}")
    assertContains(status.stdout, "pending: 1")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "phase: id=plan status=completed")
    assertContains(status.stdout, "origin=agent-executed")
    assertContains(status.stdout, "phase: id=implement_fix status=pending")
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
    assertContains(completedStatus.stdout, "phase: id=implement status=completed attempt=3")
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
      "completed_phases: preplan, plan, implement, audit, review, validate, write_history, commit_push, pr",
    )
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
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
  fun `feature-task runtime applies cursor execution matrix model directives`() {
    val fixture = runtimeFixture()
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "phase_tiers": {
            "preplan": "reasoning",
            "plan": "reasoning"
          },
          "agents": {
            "cursor": {
              "reasoning": {
                "model": "cursor-grok-4.5-high"
              },
              "implementation": {
                "model": "cursor-grok-4.5-medium"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val preplan = launcher.requests[ALL_PHASES.indexOf("preplan")].skillRunRequest
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    val implement = launcher.requests[ALL_PHASES.indexOf("implement")].skillRunRequest
    assertEquals("cursor-grok-4.5-high", preplan.modelOverride)
    assertNull(preplan.effortOverride)
    assertEquals("cursor-grok-4.5-high", plan.modelOverride)
    assertEquals("cursor-grok-4.5-medium", implement.modelOverride)
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
    val kotlinDelta = """
      diff --git a/src/Foo.kt b/src/Foo.kt
      --- a/src/Foo.kt
      +++ b/src/Foo.kt
      @@ -0,0 +1 @@
      +fun foo() = 1
    """.trimIndent()
    listOf(
      "omitted" to emptyList(),
      "auto" to listOf("--code-review-mode", "auto"),
      "inline" to listOf("--code-review-mode", "inline"),
      "delegated" to listOf("--code-review-mode", "delegated"),
    ).forEach { (expectedMode, modeArgs) ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()
      val git = FakeRuntimeGitOperations(trackedDelta = kotlinDelta)

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex") + modeArgs),
        fixture.context(launcher, workflowGitOperations = git),
      )

      assertEquals(0, result.exitCode, "$expectedMode: ${result.stdout}")
      val reviewPrompts = launcher.requests
        .map { it.skillRunRequest.promptOverride.orEmpty() }
        .filter { it.contains("bill-code-review mode:") }
      val forwardedMode = when (expectedMode) {
        "omitted", "auto" -> "inline"
        else -> expectedMode
      }
      assertTrue(
        reviewPrompts.any { it.contains("bill-code-review mode:$forwardedMode") },
        "$expectedMode missing forwarded review mode in driver launches",
      )
    }
  }

  @Test
  fun `feature-task runtime rejects an unknown review mode before workflow opening`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--code-review-mode", "external")),
      fixture.context(launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Unknown code-review execution mode 'external'")
    assertEquals(emptyList(), launcher.requests)
    assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
  }

  @Test
  fun `feature-task runtime rejects missing duplicate and conflicting review modes before workflow opening`() {
    listOf(
      "missing" to listOf("--code-review-mode"),
      "duplicate" to listOf("--code-review-mode", "auto", "--code-review-mode", "auto"),
      "conflicting" to listOf("--code-review-mode", "inline", "--code-review-mode", "auto"),
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
    assertContains(run.stdout, "Capable agents: claude, codex, cursor.")
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
    assertContains(resume.stdout, "Capable agents: claude, codex, cursor.")
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
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
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
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
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
  fun `feature-task-runtime resume refuses a terminal workflow without re-launching phases`() {
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

    assertEquals(1, resume.exitCode, resume.stdout)
    assertContains(resume.stdout, "is terminal and cannot be resumed")
    assertEquals(emptyList(), resumeLauncher.requests, resume.stdout)
  }

  @Test
  fun `feature-task runtime router resumes existing post-plan workflow at implement`() {
    val fixture = runtimeFixture()
    val interruptedLauncher = InterruptAtImplementLauncher()
    val firstRun = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(interruptedLauncher),
    )
    assertEquals(1, firstRun.exitCode, firstRun.stdout)
    val workflowId = firstRun.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    assertEquals(listOf("preplan", "plan", "implement"), interruptedLauncher.phaseOrder())

    val resumedLauncher = RecordingPhaseLauncher()
    val resumed = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "resume",
        workflowId,
        "SKILL-650",
        fixture.specPath.toString(),
        "--repo-root",
        fixture.tempDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(resumedLauncher),
    )

    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertContains(resumed.stdout, "workflow_id: $workflowId")
    assertEquals(
      AGENT_LAUNCHED_PHASES.dropWhile { it != "implement" },
      resumedLauncher.phaseOrder(),
    )
    val implementPrompt = resumedLauncher.requests.first().skillRunRequest.promptOverride.orEmpty()
    assertContains(implementPrompt, "### from: plan")
    assertFalse(implementPrompt.contains("### from: preplan"), implementPrompt)
    assertFalse(implementPrompt.contains("preplan_digest"), implementPrompt)
  }

  @Test
  fun `feature-task runtime completion drains a non-empty telemetry outbox`() {
    val fixture = runtimeFixture()
    val requester = RecordingTelemetryRequester()
    fixture.materializeDatabaseWithTelemetry(level = "anonymous", requester = requester)
    seedTelemetryOutbox(fixture.dbPath, "skillbill_fixture_event")
    assertEquals(1, pendingTelemetryOutboxCount(fixture.dbPath))

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(RecordingPhaseLauncher(), requester = requester),
    )

    assertEquals(0, run.exitCode, run.stdout)
    assertEquals(0, pendingTelemetryOutboxCount(fixture.dbPath))
    // The run records its own events too, so the batch count is incidental; every request must
    // still be the fixture proxy, which is what proves nothing reached a real relay.
    assertTrue(requester.requests.isNotEmpty())
    assertTrue(requester.requests.all { it.contains(TELEMETRY_FIXTURE_PROXY_URL) }, requester.requests.toString())
  }

  @Test
  fun `feature-task runtime completion with telemetry off transmits nothing and retains the outbox`() {
    val fixture = runtimeFixture()
    val requester = RecordingTelemetryRequester()
    // The database only materializes through an enabled read, so enable first and then resolve
    // the install back to 'off' for the run under assertion.
    fixture.materializeDatabaseWithTelemetry(level = "anonymous", requester = requester)
    seedTelemetryOutbox(fixture.dbPath, "skillbill_fixture_event")
    writeTelemetryConfig(fixture.tempDir, level = "off", proxyUrl = TELEMETRY_FIXTURE_PROXY_URL)

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(RecordingPhaseLauncher(), requester = requester),
    )

    assertEquals(0, run.exitCode, run.stdout)
    assertEquals(emptyList(), requester.requests)
    assertEquals(0, syncedTelemetryOutboxCount(fixture.dbPath))
    assertTrue(pendingTelemetryOutboxCount(fixture.dbPath) >= 1)
  }

  @Test
  @Suppress("TooGenericExceptionThrown")
  fun `a throwing telemetry drain leaves the feature-task runtime outcome byte-for-byte unchanged`() {
    val passing = runtimeDrainOutcome(RecordingTelemetryRequester())
    val throwing = runtimeDrainOutcome(
      // RuntimeException is caught by neither autoSyncTelemetry nor CliRuntime, so it is the type
      // that would reach the operator's result if the drain were not isolated.
      RecordingTelemetryRequester(failure = { throw RuntimeException("telemetry proxy exploded") }),
    )

    assertEquals(0, passing.exitCode)
    assertEquals(passing.exitCode, throwing.exitCode)
    assertEquals(passing.stdout, throwing.stdout)
    assertEquals(passing.payload, throwing.payload)
    assertEquals("", passing.stderr)
    assertEquals("", throwing.stderr)
  }

  private fun runtimeDrainOutcome(requester: RecordingTelemetryRequester): DrainOutcome {
    val fixture = runtimeFixture()
    val stderr = StringBuilder()
    fixture.materializeDatabaseWithTelemetry(level = "anonymous", requester = requester)
    seedTelemetryOutbox(fixture.dbPath, "skillbill_fixture_event")

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(RecordingPhaseLauncher(), requester = requester, liveStderr = { stderr.append(it) }),
    )

    // workflow_id is minted per run, so it is the one field two runs may legitimately differ on.
    val workflowId = run.payload?.get("workflow_id")?.toString().orEmpty()
    return DrainOutcome(
      exitCode = run.exitCode,
      stdout = run.stdout.replace(workflowId, "<workflow-id>"),
      payload = run.payload.orEmpty().filterKeys { it != "workflow_id" },
      stderr = stderr.toString(),
    )
  }
}

private data class DrainOutcome(
  val exitCode: Int,
  val stdout: String,
  val payload: Map<String, Any?>,
  val stderr: String,
)

private data class FeatureTaskRuntimeCliFixture(
  val tempDir: Path,
  val dbPath: Path,
  val specPath: Path,
) {
  @Suppress("LongParameterList")
  fun context(
    launcher: AgentRunLauncher,
    environment: Map<String, String> = emptyMap(),
    liveStdout: (String) -> Unit = {},
    liveStderr: (String) -> Unit = {},
    workflowGitOperations: WorkflowGitOperations = FakeRuntimeGitOperations(),
    requester: HttpRequester = UnconfiguredHttpRequester,
  ): CliRuntimeContext = CliRuntimeContext(
    userHome = tempDir.also { installFakeRuntimeMcpBin(it) },
    agentRunLauncher = launcher,
    environment = environment,
    requester = requester,
    liveStdout = liveStdout,
    liveStderr = liveStderr,
    workflowGitOperations = workflowGitOperations,
    executableLookup = ExecutableLookup { true },
    reviewNativeAgentPreflight = ReviewNativeAgentPreflightPort.NONE,
  )

  fun materializeDatabaseWithTelemetry(level: String, requester: HttpRequester) =
    materializeTelemetryDatabase(tempDir, dbPath, level, context(RecordingPhaseLauncher(), requester = requester))

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

  fun resumeCommand(workflowId: String, selectionJson: String? = null, agentId: String = "codex"): List<String> =
    buildList {
      addAll(
        listOf(
          "--db",
          dbPath.toString(),
          "feature-task",
          "resume",
          workflowId,
          "SKILL-650",
          specPath.toString(),
          "--repo-root",
          tempDir.toString(),
          "--agent",
          agentId,
        ),
      )
      selectionJson?.let {
        add("--agent-addon-selection-json")
        add(it)
      }
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

private fun goalContinuationValidationDepth(dbPath: Path, workflowId: String): String =
  requireNotNull(goalContinuationArtifact(dbPath, workflowId)?.get("validation_depth") as? String) {
    "goal_continuation.validation_depth missing for $workflowId"
  }

@Suppress("UNCHECKED_CAST")
private fun goalContinuationArtifact(dbPath: Path, workflowId: String): Map<String, Any?>? {
  val artifactsJson = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.prepareStatement(
      "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rs ->
        check(rs.next()) { "workflow $workflowId missing" }
        rs.getString(1)
      }
    }
  }
  val artifacts = requireNotNull(
    JsonSupport.anyToStringAnyMap(
      JsonSupport.jsonElementToValue(requireNotNull(JsonSupport.parseObjectOrNull(artifactsJson))),
    ),
  ) { "artifacts_json for $workflowId is not an object map" }
  return artifacts["goal_continuation"] as Map<String, Any?>?
}

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

private fun writeExecutionBudgetAddon(repo: Path) =
  writeAgentAddon(repo, "execution-budget", "Execution budget fixture guidance.")

private fun writeAgentAddon(repo: Path, slug: String, guidance: String) {
  val root = repo.resolve("agent-addons/$slug")
  Files.createDirectories(root)
  Files.writeString(
    root.resolve("agent-addon.yaml"),
    """
      |contract_version: "1.0"
      |slug: $slug
      |description: $slug fixture.
      |agent_ids: [codex]
      |consumers: [bill-feature]
    """.trimMargin(),
  )
  Files.writeString(root.resolve("content.md"), "## Boundary\n\n$guidance\n")
}

private fun resolvedSelectionJson(
  fixture: FeatureTaskRuntimeCliFixture,
  receivingAgent: String,
  vararg slugs: String = arrayOf("execution-budget"),
): String {
  val result = CliRuntime.run(
    buildList {
      addAll(
        listOf(
          "agent-addon",
          "resolve-selection",
          "--repo-root",
          fixture.tempDir.toString(),
          "--receiving-agent",
          receivingAgent,
          "--format",
          "json",
        ),
      )
      slugs.forEach { slug -> addAll(listOf("--token", "agent-addon:$slug")) }
    },
    fixture.context(RecordingPhaseLauncher()),
  )
  assertEquals(0, result.exitCode, result.stdout)
  return result.stdout.trim()
}

private val PHASE_LINE = Regex("^Phase: ([a-z_-]+) ", setOf(RegexOption.MULTILINE))

private fun phaseIdFromPromptOrNull(prompt: String): String? = PHASE_LINE.find(prompt)?.groupValues?.get(1)

// Returns one schema-valid phase output per launch. The delivered prompt pins the runtime phase,
// so the test double reads that phase id and echoes it back in the validated output.
private class RecordingPhaseLauncher(
  private val invalidFromLaunchIndex: Int? = null,
  // When set, review launches before this global launch index emit invalid output and later review
  // launches emit valid output, driving an invalid-then-valid review fix-loop retry.
  private val invalidReviewUntilLaunchIndex: Int? = null,
  private val decomposePlan: Boolean = false,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = CopyOnWriteArrayList()

  fun phaseOrder(): List<String> = requests.mapNotNull { request ->
    PHASE_LINE.find(request.skillRunRequest.promptOverride.orEmpty())?.groupValues?.get(1)
  }

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val launchIndex = requests.size
    requests += request
    val prompt = request.skillRunRequest.promptOverride.orEmpty()
    val phaseId = PHASE_LINE.find(prompt)?.groupValues?.get(1)
    if (phaseId == null) {
      simulateGovernedEvidenceReads(request.skillRunRequest)
      return AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
        exitStatus = 0,
        stdout = "NO_FINDINGS",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val invalid = (invalidFromLaunchIndex?.let { launchIndex >= it } ?: false) ||
      isInvalidReviewRetry(launchIndex)
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
    val phaseId = PHASE_LINE.find(requests[launchIndex].skillRunRequest.promptOverride.orEmpty())
      ?.groupValues?.get(1)
    return launchIndex < limit && phaseId == "review"
  }

  companion object {
    // Missing the required status/summary/produced_outputs fields, so the per-phase
    // output validator rejects it and the runner never marks the phase complete.
    const val INVALID_PHASE_OUTPUT = "not a json object"

    fun validPhaseOutput(phaseId: String): String {
      // A clean review/audit must emit a verification signal (an empty findings/gaps array
      // affirms no blocking findings / every criterion met) or the runtime gate blocks it (SKILL-85
      // Subtask 4 F-003 for review, Subtask 5 AC1 for audit).
      val producedOutputs = when (phaseId) {
        "review" -> "findings: []"
        "audit" -> "gaps: []"
        // preplan, plan, and implement feed the bounded planning projections, so they emit the
        // declared projection body rather than a generic task list.
        "preplan" -> PREPLAN_DIGEST_OUTPUTS
        "plan" -> EXECUTABLE_PLAN_OUTPUTS
        "implement" -> IMPLEMENTATION_RECEIPT_OUTPUTS
        "validate" -> VALIDATION_RESULT_OUTPUTS
        "write_history" -> HISTORY_RESULT_OUTPUTS
        "commit_push" -> COMMIT_PUSH_RESULT_OUTPUTS
        else -> """tasks: ["task-1"]"""
      }
      val base =
        """
        contract_version: "0.3"
        phase_id: "$phaseId"
        status: "completed"
        summary: "Phase produced a validated output."
        ${if (phaseId == "audit") "verdict: \"satisfied\"" else ""}
        produced_outputs:
          $producedOutputs
        """.trimIndent()
      return base
    }

    // Flow-style so each stays a single YAML line the phase-output template can substitute directly.
    private const val PREPLAN_DIGEST_OUTPUTS: String =
      """{projection_kind: "preplanning_digest", contract_version: "0.1", affected_boundaries: ["runtime-cli"], """ +
        """risks: ["Fixture risk."], """ +
        """rollout: {flag_required: false, flag_pattern: "none", notes: "No flag needed."}, """ +
        """validation_strategy: ["Focused runtime tests."]}"""

    private const val EXECUTABLE_PLAN_OUTPUTS: String =
      """{projection_kind: "executable_plan", contract_version: "0.1", mode: "direct", tasks: [{task_id: "task-1", """ +
        """description: "Fixture task.", criterion_refs: ["AC-001"], """ +
        """target_paths_or_symbols: ["src/Foo.kt"], test_obligations: ["Focused test."]}], """ +
        """validation_strategy: ["Focused runtime tests."]}"""

    private const val IMPLEMENTATION_RECEIPT_OUTPUTS: String =
      """{projection_kind: "implementation_receipt", contract_version: "0.1", completed_task_ids: ["task-1"], """ +
        """changed_paths: ["src/Foo.kt"], tests_executed: [{name: "FooTest", outcome: "passed"}], """ +
        """reconciliation_evidence: {reconciled: true, evidence: "Fixture tree at target state."}, """ +
        """repository_checkpoint: {fingerprint: "fixture-checkpoint-1"}, """ +
        // The mutating-phase reconciliation gate reads this alongside the receipt's own evidence.
        """reconciled_state: {reconciled: true, """ +
        """evidence: "All planned changes are present at their intended state."}}"""

    private const val VALIDATION_RESULT_OUTPUTS: String =
      """{validation_result: {validation_status: "passed", checks: ["FooTest"], """ +
        """repository_checkpoint: {fingerprint: "fixture-checkpoint-1"}, """ +
        """gate_run_count: 1, gate_runs: [{duration_ms: 1, outcome: "passed", """ +
        """cache_mode: "forced_full", executed_work_units: 1}]}}"""

    private const val HISTORY_RESULT_OUTPUTS: String =
      """{history_result: {changed_paths: ["agent/history.md"], decisions_recorded: []}}"""

    private const val COMMIT_PUSH_RESULT_OUTPUTS: String =
      """{commit_push_result: {message: "SKILL-650: runtime cli fixture subtask", """ +
        """changed_paths: ["src/Foo.kt"], """ +
        """branch: "feat/pre-created-runtime-branch", base_branch: "main", pushed: true}}"""

    fun validPhaseOutputForTest(phaseId: String): String = validPhaseOutput(phaseId)

    val DECOMPOSE_PLAN_OUTPUT: String = """
      {
        "contract_version": "0.3",
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

private class InterruptAtImplementLauncher : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    val phaseId = phaseIdFromPromptOrNull(request.skillRunRequest.promptOverride.orEmpty())
    if (phaseId == null) {
      simulateGovernedEvidenceReads(request.skillRunRequest)
      return AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
        exitStatus = 0,
        stdout = "NO_FINDINGS",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    if (phaseId == "implement") {
      return AgentRunLaunchFacts(
        agent = InstallAgent.CODEX,
        exitStatus = null,
        stdout = "",
        stderr = "interrupted after completed planning",
        timedOut = false,
        spawnFailed = true,
      )
    }
    return AgentRunLaunchFacts(
      agent = InstallAgent.CODEX,
      exitStatus = 0,
      stdout = RecordingPhaseLauncher.validPhaseOutputForTest(phaseId),
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  fun phaseOrder(): List<String> = requests.mapNotNull { request ->
    phaseIdFromPromptOrNull(request.skillRunRequest.promptOverride.orEmpty())
  }
}

private val ALL_PHASES =
  listOf("preplan", "plan", "implement", "audit", "review", "validate", "write_history", "commit_push", "pr")
private val AGENT_LAUNCHED_PHASES = ALL_PHASES.filterNot { it == "review" }

// Records checkouts and reports a configurable current branch so branch-setup is exercised through
// the CLI without a real git repo. The default reports an existing feature branch (reuse path).
private class FakeRuntimeGitOperations(
  private var currentBranchValue: String = "feat/pre-created-runtime-branch",
  private val checkoutResult: WorkflowGitOperationResult? = null,
  private val trackedDelta: String = "",
) : WorkflowGitOperations,
  GoalSubtaskReviewGitOperationsProvider,
  RepositoryFingerprintGitOperationsProvider,
  RepositoryOwnedPathsGitOperationsProvider,
  ScopedStagingGitOperationsProvider {
  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations = TestRepositoryOwnedPathsOperations

  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations = TestRepositoryFingerprintOperations

  override val scopedStagingOperations: ScopedStagingGitOperations = object : ScopedStagingGitOperations {
    override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")

    override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")

    override fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")

    override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")

    override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult(
        status = "ok",
        value = paths.joinToString(separator = "\u0000") { path -> "identity\t$path" },
      )
  }

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
    WorkflowGitOperationResult(status = "ok", value = "2".repeat(40))

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "ok",
    value = revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) } ?: "1".repeat(40),
  )

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

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
    object : GoalSubtaskReviewGitOperations {
      override fun captureBaseline(repoRoot: Path, expectedBranch: String) = GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
      )

      override fun buildInput(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewInputResult = GoalSubtaskReviewInputResult(
        status = "ok",
        input = GoalSubtaskReviewInput(
          reviewBaseSha = baseline.reviewBaseSha,
          currentHeadSha = baseline.reviewBaseSha,
          trackedDelta = trackedDelta,
          ownedUntrackedPatches = "",
        ),
      )

      override fun recoverBaseline(
        repoRoot: Path,
        request: skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
        status = "error",
        error = "Goal review baseline recovery is not used by this runtime CLI fixture.",
      )
    }
}

class CursorAgentRuntimeCliTest {
  @Test
  fun `cursor is accepted for feature-task run`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
    assertTrue(
      launcher.requests.all { it.agentId == "cursor" },
      "All phases should use cursor agent",
    )
  }

  @Test
  fun `cursor is accepted for feature-task resume`() {
    val fixture = runtimeFixture()
    val interruptedLauncher = InterruptAtImplementLauncher()

    val firstRun = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(interruptedLauncher),
    )
    assertEquals(1, firstRun.exitCode, firstRun.stdout)
    val workflowId = firstRun.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val resumedLauncher = RecordingPhaseLauncher()
    val resumed = CliRuntime.run(
      fixture.resumeCommand(workflowId, agentId = "cursor"),
      fixture.context(resumedLauncher),
    )

    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES.dropWhile { it != "implement" }, resumedLauncher.phaseOrder())
    assertTrue(
      resumedLauncher.requests.all { it.agentId == "cursor" },
      "All resumed phases should use cursor agent",
    )
  }

  @Test
  fun `cursor goal-child invocation route succeeds`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(launcher.requests.isNotEmpty(), "Should launch cursor for at least one phase")
    assertTrue(launcher.requests.all { it.agentId == "cursor" }, "All phases should use cursor")
  }

  @Test
  fun `cursor accepted for phase-agent override`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--phase-agent", "plan=cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())

    val planRequest = launcher.requests.single {
      phaseIdFromPromptOrNull(it.skillRunRequest.promptOverride.orEmpty()) == "plan"
    }
    assertEquals("cursor", planRequest.agentId)

    val nonPlanPhaseRequests = launcher.requests.filter {
      val phaseId = phaseIdFromPromptOrNull(it.skillRunRequest.promptOverride.orEmpty())
      phaseId != null && phaseId != "plan"
    }
    assertTrue(nonPlanPhaseRequests.all { it.agentId != "cursor" }, "Non-plan phases should not use cursor")
  }

  @Test
  fun `cursor accepted for run-wide agent override`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent-override", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(
      launcher.requests.all { it.agentId == "cursor" },
      "All phases should use cursor override",
    )
  }

  @Test
  fun `cursor accepted for parallel review agent selection`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val git = FakeRuntimeGitOperations(
      trackedDelta = """
        diff --git a/src/Foo.kt b/src/Foo.kt
        --- a/src/Foo.kt
        +++ b/src/Foo.kt
        @@ -0,0 +1 @@
        +fun foo() = 1
      """.trimIndent(),
    )

    val result = CliRuntime.run(
      fixture.runCommand(
        extra = listOf(
          "--agent",
          "codex",
          "--code-review-mode",
          "inline",
          "--parallel-review-agent",
          "cursor",
        ),
      ),
      fixture.context(launcher, workflowGitOperations = git),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(
      launcher.requests.any { it.agentId == "cursor" },
      "parallel review agent cursor must launch a driver stage",
    )
  }
}
