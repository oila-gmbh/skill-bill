package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.error.InvalidAgentAddonSelectionError
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliFeatureTaskRuntimeLaunchTest {
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
    val unsupported = assertFailsWith<InvalidAgentAddonSelectionError> {
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
    )

    cases.forEach { (case, overrideArgs) ->
      val fixture = runtimeFixture().also { writeExecutionBudgetAddon(it.tempDir) }
      val selection = resolvedSelectionJson(fixture, "codex")
      val launcher = RecordingPhaseLauncher()

      val failure = assertFailsWith<InvalidAgentAddonSelectionError>(case) {
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
      fixture.context(launcher) { liveStderr = { stderr.append(it) } },
    )

    assertEquals(0, run.exitCode, run.stdout)
    assertContains(run.stdout, "status: complete")
    assertContains(
      run.stdout,
      "completed_phases: $COMPLETED_PHASES_CLEAN_RUN",
    )
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
    assertContains(stderr.toString(), "feature-task-runtime is a deprecated alias for feature-task")

    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    val statusStderr = StringBuilder()
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task-runtime", "status", workflowId),
      fixture.context(RecordingPhaseLauncher()) { liveStderr = { statusStderr.append(it) } },
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
      canonicalFixture.context(RecordingPhaseLauncher()) { liveStderr = { canonicalStderr.append(it) } },
    )
    assertEquals(0, canonical.exitCode, canonical.stdout)

    val aliasFixture = runtimeFixture()
    val aliasStderr = StringBuilder()
    val alias = CliRuntime.run(
      featureTaskCommand(aliasFixture, command = "feature-task-runtime"),
      aliasFixture.context(RecordingPhaseLauncher()) { liveStderr = { aliasStderr.append(it) } },
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
}
