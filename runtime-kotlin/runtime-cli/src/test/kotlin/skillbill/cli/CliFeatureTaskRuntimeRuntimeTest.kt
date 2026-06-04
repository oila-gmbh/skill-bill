package skillbill.cli

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliFeatureTaskRuntimeRuntimeTest {
  @Test
  fun `feature-task-runtime command registers run status and resume`() {
    val help = CliRuntime.run(listOf("feature-task-runtime", "--help"), CliRuntimeContext())

    assertEquals(0, help.exitCode, help.stdout)
    assertContains(help.stdout, "EXPERIMENTAL")
    assertContains(help.stdout, "status")
    assertContains(help.stdout, "resume")
    // The documented explicit `run` form is a real subcommand, not a misparsed positional.
    assertContains(help.stdout, "explicit form")
    assertContains(help.stdout, "--phase-agent")
    assertContains(help.stdout, "--agent-override")
    assertContains(help.stdout, "--monitor")
    assertContains(help.stdout, "--max-wall-clock-minutes")
  }

  @Test
  fun `feature-task-runtime run requires issue key and spec path`() {
    val missingArgs = CliRuntime.run(listOf("feature-task-runtime"), CliRuntimeContext())
    assertEquals(1, missingArgs.exitCode, missingArgs.stdout)
    assertContains(missingArgs.stdout, "issue_key is required")

    val fixture = runtimeFixture()
    val missingSpec = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task-runtime", "SKILL-650"),
      fixture.context(RecordingPhaseLauncher()),
    )
    assertEquals(1, missingSpec.exitCode, missingSpec.stdout)
    assertContains(missingSpec.stdout, "spec_path is required")
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
    assertContains(result.stdout, "completed_phases: plan, implement, review, audit, validate")
    assertEquals(listOf("codex"), launcher.requests.map { it.agentId }.distinct())
    assertEquals(5, launcher.requests.size)
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
  fun `feature-task-runtime run SKILL_BILL_AGENT wins over detected invoking context`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(),
      fixture.context(launcher, environment = mapOf("CLAUDECODE" to "1", "SKILL_BILL_AGENT" to "opencode")),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("opencode"), launcher.requests.map { it.agentId }.distinct())
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
    val orderedPhases = listOf("plan", "implement", "review", "audit", "validate")
    val agentByPhase = orderedPhases.mapIndexed { index, phaseId ->
      phaseId to launcher.requests[index].agentId
    }.toMap()
    assertEquals(5, launcher.requests.size, result.stdout)
    assertEquals("claude", agentByPhase["plan"], result.stdout)
    assertEquals(
      listOf("codex", "codex", "codex", "codex"),
      orderedPhases.drop(1).map { agentByPhase.getValue(it) },
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
    val launcher = RecordingPhaseLauncher(invalidReviewUntilLaunchIndex = 3)
    val live = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--monitor")),
      fixture.context(launcher, liveStdout = { live.append(it) }),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val streamed = live.toString()
    assertContains(streamed, "phase plan started")
    assertContains(streamed, "phase plan completed")
    assertContains(streamed, "phase review fix_loop")
    assertContains(streamed, "phase review completed")
  }

  @Test
  fun `feature-task-runtime status reports per-phase projection after a completed run`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(0, run.exitCode, run.stdout)
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task-runtime", "status", workflowId),
      fixture.context(launcher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: ok")
    assertContains(status.stdout, "complete: 5")
    assertContains(status.stdout, "pending: 0")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "current_phase: none")
    assertContains(status.stdout, "phase: id=plan status=completed")
  }

  @Test
  fun `feature-task-runtime status reports not_found for an unknown workflow id`() {
    val fixture = runtimeFixture()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task-runtime", "status", "wftr-missing"),
      fixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: not_found")
  }

  @Test
  fun `feature-task-runtime status reports a blocked phase derived from the ledger`() {
    val fixture = runtimeFixture()
    // Plan completes (launch 0 valid); implement never validates and blocks after the bounded fix loop.
    val launcher = RecordingPhaseLauncher(invalidFromLaunchIndex = 1)
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(1, run.exitCode, run.stdout)
    assertContains(run.stdout, "status: blocked")
    // F-006: the rendered run output names the specific blocked phase and reason. `implement` is a
    // non-fix-loop phase, so it blocks immediately on invalid output with the matching wording.
    assertContains(run.stdout, "last_incomplete_phase: implement")
    assertContains(run.stdout, "blocked_reason:")
    assertContains(run.stdout, "does not participate in a fix loop")
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "feature-task-runtime", "status", workflowId),
      fixture.context(launcher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: ok")
    assertContains(status.stdout, "complete: 1")
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
        "feature-task-runtime",
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
    assertContains(result.stdout, "completed_phases: plan, implement, review, audit, validate")
    assertEquals(5, launcher.requests.size)
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
        "feature-task-runtime",
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
  ): CliRuntimeContext = CliRuntimeContext(
    userHome = tempDir,
    agentRunLauncher = launcher,
    environment = environment,
    liveStdout = liveStdout,
  )

  fun runCommand(extra: List<String> = emptyList()): List<String> = buildList {
    add("--db")
    add(dbPath.toString())
    add("feature-task-runtime")
    add("SKILL-650")
    add(specPath.toString())
    add("--repo-root")
    add(tempDir.toString())
    addAll(extra)
  }
}

private fun runtimeFixture(): FeatureTaskRuntimeCliFixture {
  val tempDir = Files.createTempDirectory("skillbill-cli-feature-task-runtime")
  val specPath = tempDir.resolve(".feature-specs/SKILL-650-runtime/spec.md")
  Files.createDirectories(specPath.parent)
  Files.writeString(
    specPath,
    """
    # SKILL-650 runtime spec

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

// Returns one schema-valid phase output per launch. AgentRunLaunchRequest carries no phase id, so
// the test double infers the phase from launch order. The echoed phase_id is only cosmetic: it adds
// no wrong-phase regression protection, since the runner labels validation with its own phaseId and
// never cross-checks the agent-supplied phase_id.
private class RecordingPhaseLauncher(
  private val invalidFromLaunchIndex: Int? = null,
  // When set, launches at indices in [reviewIndex, this) emit invalid output and later launches
  // emit valid output, driving an invalid-then-valid review fix-loop retry. The runner labels
  // validation with its own phase id and never reads the agent-supplied phase_id, so the cosmetic
  // emitted phase_id below need not track the exact phase — only validity matters.
  private val invalidReviewUntilLaunchIndex: Int? = null,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val launchIndex = requests.size
    requests += request
    val invalid = (invalidFromLaunchIndex?.let { launchIndex >= it } ?: false) ||
      isInvalidReviewRetry(launchIndex)
    val cosmeticPhaseId = ORDERED_PHASES.getOrElse(launchIndex) { "plan" }
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = if (invalid) INVALID_PHASE_OUTPUT else validPhaseOutput(cosmeticPhaseId),
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  private fun isInvalidReviewRetry(launchIndex: Int): Boolean {
    val limit = invalidReviewUntilLaunchIndex ?: return false
    val reviewIndex = ORDERED_PHASES.indexOf("review")
    return launchIndex in reviewIndex until limit
  }

  private companion object {
    val ORDERED_PHASES = listOf("plan", "implement", "review", "audit", "validate")

    // Missing the required status/summary/produced_outputs fields, so the per-phase
    // output validator rejects it and the runner never marks the phase complete.
    val INVALID_PHASE_OUTPUT =
      """
      contract_version: "0.1"
      phase_id: "implement"
      """.trimIndent()

    fun validPhaseOutput(phaseId: String): String = """
      contract_version: "0.1"
      phase_id: "$phaseId"
      status: "completed"
      summary: "Phase produced a validated output."
      produced_outputs:
        tasks: ["task-1"]
    """.trimIndent()
  }
}
