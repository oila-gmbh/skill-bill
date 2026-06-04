package skillbill.cli

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.workflow.WorkflowGitOperations
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
      listOf("--db", fixture.dbPath.toString(), "feature-task-runtime", "status", workflowId),
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
    assertContains(result.stdout, "completed_phases: preplan, plan, implement, review, audit, validate")
    assertEquals(listOf("codex"), launcher.requests.map { it.agentId }.distinct())
    assertEquals(6, launcher.requests.size)
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
    val orderedPhases = ALL_PHASES
    val agentByPhase = orderedPhases.mapIndexed { index, phaseId ->
      phaseId to launcher.requests[index].agentId
    }.toMap()
    assertEquals(6, launcher.requests.size, result.stdout)
    assertEquals("claude", agentByPhase["plan"], result.stdout)
    assertEquals(
      listOf("codex", "codex", "codex", "codex", "codex"),
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
    assertContains(status.stdout, "complete: 6")
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
    // Preplan and plan complete; implement never validates and blocks immediately.
    val launcher = RecordingPhaseLauncher(invalidFromLaunchIndex = 2)
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
    assertContains(result.stdout, "completed_phases: preplan, plan, implement, review, audit, validate")
    assertEquals(6, launcher.requests.size)
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
    // Defaults to an already-checked-out feature branch so the runtime reuses it (no checkout) and
    // existing completion/blocked expectations stand; branch-setup tests override this to start on
    // the default branch. The real git adapter is bypassed because the tempDir is not a git repo.
    workflowGitOperations: WorkflowGitOperations = FakeRuntimeGitOperations(),
  ): CliRuntimeContext = CliRuntimeContext(
    userHome = tempDir,
    agentRunLauncher = launcher,
    environment = environment,
    liveStdout = liveStdout,
    workflowGitOperations = workflowGitOperations,
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

// Returns one schema-valid phase output per launch. The delivered prompt pins the runtime phase,
// so the test double reads that phase id and echoes it back in the validated output.
private class RecordingPhaseLauncher(
  private val invalidFromLaunchIndex: Int? = null,
  // When set, review launches before this global launch index emit invalid output and later review
  // launches emit valid output, driving an invalid-then-valid review fix-loop retry.
  private val invalidReviewUntilLaunchIndex: Int? = null,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val launchIndex = requests.size
    requests += request
    val invalid = (invalidFromLaunchIndex?.let { launchIndex >= it } ?: false) ||
      isInvalidReviewRetry(launchIndex)
    val phaseId = phaseIdFromPrompt(request.skillRunRequest.promptOverride.orEmpty())
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = if (invalid) INVALID_PHASE_OUTPUT else validPhaseOutput(phaseId),
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
    private val PHASE_LINE = Regex("^Phase: ([a-z-]+) ", setOf(RegexOption.MULTILINE))

    fun phaseIdFromPrompt(prompt: String): String =
      PHASE_LINE.find(prompt)?.groupValues?.get(1) ?: error("Prompt did not contain a phase header: $prompt")

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

private val ALL_PHASES = listOf("preplan", "plan", "implement", "review", "audit", "validate")

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
}
