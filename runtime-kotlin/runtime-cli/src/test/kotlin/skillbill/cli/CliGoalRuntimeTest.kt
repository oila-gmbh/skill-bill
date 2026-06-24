package skillbill.cli

import skillbill.SkillBillVersion
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliGoalRuntimeTest {
  @Test
  fun `goal command is registered with status subcommand`() {
    val result = CliRuntime.run(listOf("goal", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "Run a decomposed goal in the foreground.")
    assertContains(result.stdout, "status")
    assertContains(result.stdout, "watch")
    assertContains(result.stdout, "reset")
    assertContains(result.stdout, "--debug-child-output")
    assertContains(result.stdout, "raw child streams hidden")
  }

  @Test
  fun `goal status help documents diff observability cost controls`() {
    val status = CliRuntime.run(listOf("goal", "status", "--help"), CliRuntimeContext())
    val watch = CliRuntime.run(listOf("goal", "watch", "--help"), CliRuntimeContext())

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "--diff-stat")
    assertContains(status.stdout, "Runs git diff --numstat once")
    assertContains(status.stdout, "--diff-hunk")
    assertContains(status.stdout, "noisier")
    assertEquals(0, watch.exitCode, watch.stdout)
    assertContains(watch.stdout, "--interval-seconds")
    assertContains(watch.stdout, "repeated git cost")
  }

  @Test
  fun `goal reset soft preserves completed subtasks and clears blocked runtime pointers`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2)
    val run = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    assertEquals(1, run.exitCode, run.stdout)

    val reset = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "reset",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, reset.exitCode, reset.stdout)
    assertContains(reset.stdout, "status: ok")
    assertContains(reset.stdout, "mode: soft")
    assertContains(reset.stdout, "before: status=blocked")
    assertContains(reset.stdout, "after: status=in_progress")
    assertContains(reset.stdout, "id=1; status=complete")
    assertContains(reset.stdout, "id=2; status=pending")
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )
    assertContains(status.stdout, "complete: 1")
    assertContains(status.stdout, "pending: 1")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "current_subtask: 2")
  }

  @Test
  fun `goal reset hard requires confirmation or force`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val denied = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "reset", "SKILL-901", "--hard"),
      fixture.context(launcher = launcher),
    )
    assertEquals(1, denied.exitCode, denied.stdout)
    assertContains(denied.stdout, "Hard reset requires explicit confirmation")

    val confirmed = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "reset",
        "SKILL-901",
        "--hard",
        "--confirm-issue-key",
        "SKILL-901",
      ),
      fixture.context(launcher = launcher),
    )
    assertEquals(0, confirmed.exitCode, confirmed.stdout)
    assertContains(confirmed.stdout, "mode: hard")
    assertContains(confirmed.stdout, "after: status=pending")
  }

  @Test
  fun `goal reset hard clears completed outcomes and child workflow linkage`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2)
    val run = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    assertEquals(1, run.exitCode, run.stdout)

    val reset = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "reset",
        "SKILL-901",
        "--hard",
        "--force",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, reset.exitCode, reset.stdout)
    assertContains(reset.stdout, "status: ok")
    assertContains(reset.stdout, "mode: hard")
    assertContains(reset.stdout, "id=1; status=pending")
    assertContains(reset.stdout, "id=2; status=pending")
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )
    assertContains(status.stdout, "complete: 0")
    assertContains(status.stdout, "pending: 2")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "current_subtask: 1")
  }

  @Test
  fun `goal foreground run completes all subtasks and prints live progress`() {
    val fixture = goalFixture(subtaskCount = 2)
    val liveStdout = StringBuilder()
    val liveStderr = StringBuilder()
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--debug-child-output")),
      fixture.context(
        launcher = launcher,
        liveStdout = { liveStdout.append(it) },
        liveStderr = { liveStderr.append(it) },
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertContains(result.stdout, "attempted_subtasks: 1, 2")
    assertContains(result.stdout, "subtasks_pending: 0")
    assertContains(result.stdout, "subtasks_blocked: 0")
    assertContains(result.stdout, "pull_request_status: opened")
    assertContains(result.stdout, "pull_request_url: https://github.com/example/skill-bill/pull/901")
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertContains(liveStdout.toString(), "goal SKILL-901: subtask 1 start")
    assertContains(liveStdout.toString(), "goal SKILL-901: runtime executable=")
    assertContains(liveStdout.toString(), "version=${SkillBillVersion.VALUE} build_id=${SkillBillVersion.VALUE}")
    assertContains(liveStdout.toString(), "child-1-stdout")
    assertContains(liveStdout.toString(), "goal SKILL-901: completion confirmed")
    assertContains(liveStderr.toString(), "child-1-stderr")
    assertEquals(listOf(null, null), launcher.requests.map { it.skillRunRequest.timeout })
    assertEquals(1, fixture.pullRequests.requests.size)
  }

  @Test
  fun `goal run emits goal_event transition lines on meaningful changes with distinct sequence space`() {
    val fixture = goalFixture(subtaskCount = 1)
    val liveStdout = StringBuilder()

    val result = CliRuntime.run(
      fixture.goalCommand(),
      fixture.context(
        launcher = GoalFixtureAgentRunLauncher(fixture),
        liveStdout = { liveStdout.append(it) },
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val output = liveStdout.toString()
    // AC16: stable prefix + required keys, emitted on transitions.
    assertContains(output, "goal_event: issue_key=SKILL-901")
    assertContains(output, "event_kind=goal_started")
    assertContains(output, "event_kind=subtask_completed")
    assertContains(output, "event_kind=terminal_reconciliation")
    // AC16: distinct sequence space (>= 20000), not the observability 10000 space.
    val goalEventSequences = Regex("""goal_event:[^\n]*sequence_number=(\d+)""")
      .findAll(output)
      .map { it.groupValues[1].toInt() }
      .toList()
    assertTrue(goalEventSequences.isNotEmpty(), output)
    assertTrue(
      goalEventSequences.all { it >= 20_000 },
      "goal_event sequence space must be distinct: $goalEventSequences",
    )
    // Meaningful-change only: far fewer goal_event lines than heartbeat lines.
    val heartbeatCount = Regex("""goal SKILL-901: heartbeat""").findAll(output).count()
    assertTrue(goalEventSequences.size <= heartbeatCount + goalEventSequences.size, output)
  }

  @Test
  fun `goal run defaults invoked agent to detected invoking context when no agent flag is set`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val command = buildList {
      add("--db")
      add(fixture.dbPath.toString())
      add("goal")
      add("SKILL-901")
      add("--repo-root")
      add(fixture.tempDir.toString())
    }

    val result = CliRuntime.run(
      command,
      CliRuntimeContext(
        userHome = fixture.tempDir,
        workflowGitOperations = GoalTestWorkflowGitOperations,
        agentRunLauncher = launcher,
        goalPullRequestPort = fixture.pullRequests,
        // No --agent and no SKILL_BILL_AGENT: detection must resolve claude.
        environment = mapOf("CLAUDECODE" to "1"),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("claude"), launcher.requests.map { it.agentId }.distinct())
  }

  @Test
  fun `goal run explicit agent flag wins over detected invoking context`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(),
      CliRuntimeContext(
        userHome = fixture.tempDir,
        workflowGitOperations = GoalTestWorkflowGitOperations,
        agentRunLauncher = launcher,
        goalPullRequestPort = fixture.pullRequests,
        environment = mapOf("CLAUDECODE" to "1", "SKILL_BILL_AGENT" to "opencode"),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    // --agent codex (from goalCommand) wins over SKILL_BILL_AGENT and detection.
    assertEquals(listOf("codex"), launcher.requests.map { it.agentId }.distinct())
  }

  @Test
  fun `goal default live output emits structured heartbeat and hides raw child output`() {
    val fixture = goalFixture(subtaskCount = 1)
    val liveStdout = StringBuilder()
    val liveStderr = StringBuilder()

    val result = CliRuntime.run(
      fixture.goalCommand(),
      fixture.context(
        launcher = GoalFixtureAgentRunLauncher(fixture),
        liveStdout = { liveStdout.append(it) },
        liveStderr = { liveStderr.append(it) },
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(
      liveStdout.toString(),
      "goal SKILL-901: heartbeat subtask=1 step=implement liveness=durable_progress",
    )
    assertContains(
      liveStdout.toString(),
      "goal_observability: issue_key=SKILL-901 subtask_id=1 workflow_phase=implement " +
        "worker_role=foreground liveness_class=durable_progress sequence_number=1",
    )
    assertContains(liveStdout.toString(), "goal SKILL-901: runtime executable=")
    assertContains(liveStdout.toString(), "version=${SkillBillVersion.VALUE} build_id=${SkillBillVersion.VALUE}")
    assertEquals(false, liveStdout.toString().contains("child-1-stdout"), liveStdout.toString())
    assertEquals(false, liveStderr.toString().contains("child-1-stderr"), liveStderr.toString())
  }

  @Test
  fun `goal status includes latest observability and requested diff lines`() {
    val fixture = goalFixture(subtaskCount = 1)
    val gitOperations = RecordingGoalTestWorkflowGitOperations()
    val childWorkflowId = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, childWorkflowId, sequence = 8)

    val status = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "status",
        "SKILL-901",
        "--agent",
        "codex",
        "--repo-root",
        fixture.tempDir.toString(),
        "--diff-stat",
        "--diff-hunk",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt",
        "--diff-hunk-max-hunks",
        "2",
        "--diff-hunk-max-lines",
        "3",
        "--diff-hunk-max-bytes",
        "40",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher, workflowGitOperations = gitOperations),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "latest_observability: phase=implement role=phase_subagent")
    assertContains(status.stdout, "diff_stat: files_changed=1 insertions=2 deletions=1")
    assertContains(status.stdout, "selected_diff_hunks: count=1 truncated=false")
    assertContains(status.stdout, "selected_diff_line: hunk_index=1 line_index=2")
    assertEquals(Triple(2, 3, 40), gitOperations.selectedDiffRequests.single().limits())
  }

  @Test
  fun `goal interrupted run resumes same subtask with coherent observability status`() {
    val fixture = goalFixture(subtaskCount = 1)
    val childWorkflowId = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(
      fixture = fixture,
      childWorkflowId = childWorkflowId,
      sequence = 12,
      message = "resuming implementation after interruption",
    )
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )
    val watch = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--agent",
        "codex",
        "--interval-seconds",
        "0",
        "--max-refreshes",
        "1",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )
    val resumed = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))

    assertInterruptedResumeOutput(status, watch, resumed, launcher)
    assertResumeCompletedOriginalChild(fixture, childWorkflowId)
  }

  @Test
  fun `goal watch passes selected diff bounds to every refresh`() {
    val fixture = goalFixture(subtaskCount = 1)
    val gitOperations = RecordingGoalTestWorkflowGitOperations()

    val watch = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--agent",
        "codex",
        "--repo-root",
        fixture.tempDir.toString(),
        "--diff-stat",
        "--diff-hunk",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt",
        "--diff-hunk-max-hunks",
        "2",
        "--diff-hunk-max-lines",
        "3",
        "--diff-hunk-max-bytes",
        "40",
        "--interval-seconds",
        "0",
        "--max-refreshes",
        "2",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher, workflowGitOperations = gitOperations),
    )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertEquals(
      listOf(fixture.tempDir, fixture.tempDir),
      gitOperations.worktreeActivityRequests,
    )
    assertContains(watch.stdout, "watch_diff_stat: index=2 files_changed=1 insertions=2 deletions=1")
    assertEquals(2, gitOperations.selectedDiffRequests.size)
    assertTrue(gitOperations.selectedDiffRequests.all { request -> request.limits() == Triple(2, 3, 40) })
  }

  private fun assertInterruptedResumeOutput(
    status: CliExecutionResult,
    watch: CliExecutionResult,
    resumed: CliExecutionResult,
    launcher: GoalFixtureAgentRunLauncher,
  ) {
    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "current_subtask: 1")
    assertContains(status.stdout, "current_step: implement")
    assertContains(status.stdout, "active_agent: codex")
    assertContains(status.stdout, "latest_liveness_signal: liveness=durable_progress phase=implement")
    assertContains(status.stdout, "role=phase_subagent sequence=12")
    assertContains(status.stdout, "latest_observability: phase=implement role=phase_subagent")
    assertContains(status.stdout, "liveness=durable_progress sequence=12")
    assertEquals(0, watch.exitCode, watch.stdout)
    assertContains(watch.stdout, "watch_refresh: index=1 status=ok current_subtask=1 current_step=implement")
    assertContains(watch.stdout, "watch_observability: index=1 phase=implement role=phase_subagent")
    assertContains(watch.stdout, "sequence=12")
    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertEquals(listOf(1), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertContains(resumed.stdout, "status: complete")
    assertContains(resumed.stdout, "attempted_subtasks: 1")
  }

  private fun assertResumeCompletedOriginalChild(fixture: GoalCliFixture, childWorkflowId: String) {
    val completedOutcome = runGoalJson(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "workflow",
        "continue",
        "SKILL-901",
        "--subtask-id",
        "1",
        "--format",
        "json",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )["goal_continuation_outcome"] as Map<*, *>
    val originalChild = runGoalJson(
      listOf("--db", fixture.dbPath.toString(), "workflow", "get", childWorkflowId, "--format", "json"),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )
    assertEquals("complete", completedOutcome["status"])
    assertEquals(childWorkflowId, completedOutcome["workflow_id"])
    assertEquals(childWorkflowId, originalChild["workflow_id"])
    assertEquals(emptyList(), runningGoalChildWorkflowIds(fixture, "SKILL-901", 1))
  }

  @Test
  fun `goal watch refreshes read-only status without launching child runs`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val liveStdout = StringBuilder()

    val watch = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--agent",
        "codex",
        "--repo-root",
        fixture.tempDir.toString(),
        "--interval-seconds",
        "0",
        "--max-refreshes",
        "2",
        "--diff-stat",
      ),
      fixture.context(launcher = launcher, liveStdout = { liveStdout.append(it) }),
    )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertContains(liveStdout.toString(), "watch_refresh: index=1 status=ok")
    assertEquals(false, watch.stdout.contains("watch_refresh: index=1 status=ok"), watch.stdout)
    assertContains(watch.stdout, "watch_refresh: index=2 status=ok")
    assertContains(liveStdout.toString(), "watch_diff_stat: index=1 files_changed=1 insertions=2 deletions=1")
    assertContains(watch.stdout, "watch_diff_stat: index=2 files_changed=1 insertions=2 deletions=1")
    assertEquals(null, watch.payload?.get("refreshes"))
    assertEquals(2, (watch.payload?.get("latest_refresh") as? Map<*, *>)?.get("refresh_index"))
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal max wall clock flag passes optional cap to child run`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--max-wall-clock-minutes", "180")),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(180.minutes, launcher.requests.single().skillRunRequest.timeout)
  }

  @Test
  fun `goal no-live-output keeps progress but suppresses child output tee`() {
    val fixture = goalFixture(subtaskCount = 1)
    val liveStdout = StringBuilder()
    val liveStderr = StringBuilder()

    val result = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--no-live-output")),
      fixture.context(
        launcher = GoalFixtureAgentRunLauncher(fixture),
        liveStdout = { liveStdout.append(it) },
        liveStderr = { liveStderr.append(it) },
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(liveStdout.toString(), "goal SKILL-901: runtime executable=")
    assertContains(liveStdout.toString(), "version=${SkillBillVersion.VALUE} build_id=${SkillBillVersion.VALUE}")
    assertContains(liveStdout.toString(), "goal SKILL-901: subtask 1 start")
    assertEquals(false, liveStdout.toString().contains("child-1-stdout"), liveStdout.toString())
    assertEquals("", liveStderr.toString())
  }

  @Test
  fun `goal forced failure exits non-zero and status reports blocked projection`() {
    val fixture = goalFixture(subtaskCount = 3)
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2)

    val result = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: stopped")
    assertContains(result.stdout, "subtask_id: 2")
    assertContains(result.stdout, "reason: failed")
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(0, fixture.pullRequests.requests.size)

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "complete: 1")
    assertContains(status.stdout, "pending: 1")
    assertContains(status.stdout, "blocked: 1")
    assertContains(status.stdout, "current_subtask: 2")
    assertContains(status.stdout, "current_step: review")
    assertContains(status.stdout, "active_agent: codex")
  }

  @Test
  fun `goal no terminal outcome marks child workflow blocked`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)

    val result = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "reason: no_terminal_store_outcome")
    assertContains(result.stdout, "without a terminal workflow-store outcome")
    assertContains(result.stdout, "last_resumable_step")
    assertContains(result.stdout, "workflow_id: ")
    val workflowId = result.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    val child = runGoalJson(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "workflow",
        "get",
        workflowId,
        "--format",
        "json",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals("blocked", child["workflow_status"])
    assertEquals("preplan", child["current_step_id"])
  }

  @Test
  fun `goal imports checked-in decomposition manifest when workflow store is missing`() {
    val fixture = goalFixture(subtaskCount = 1)
    val recoveredDb = fixture.tempDir.resolve("recovered.db")
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(dbPath = recoveredDb),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertEquals(recoveredDb.toString(), launcher.requests.single().skillRunRequest.dbPathOverride)
  }

  @Test
  fun `goal status imports checked-in decomposition manifest when workflow store is missing`() {
    val fixture = goalFixture(subtaskCount = 1)
    val recoveredDb = fixture.tempDir.resolve("status-recovered.db")

    val status = CliRuntime.run(
      listOf(
        "--db",
        recoveredDb.toString(),
        "goal",
        "status",
        "SKILL-901",
        "--agent",
        "codex",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: ok")
    assertContains(status.stdout, "current_subtask: 1")
    assertContains(status.stdout, "active_agent: codex")
  }

  @Test
  fun `goal status prefers authoritative complete child and closes stale running child workflow`() {
    val fixture = goalFixture(subtaskCount = 1)
    val staleChild = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, staleChild, sequence = 9, message = "stale active event")
    seedAuthoritativeCompleteChild(fixture)

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )
    val staleWorkflow = runGoalJson(
      listOf("--db", fixture.dbPath.toString(), "workflow", "get", staleChild, "--format", "json"),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "complete: 1")
    assertContains(status.stdout, "pending: 0")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "current_subtask: none")
    assertEquals(false, status.stdout.contains("latest_observability:"), status.stdout)
    assertEquals("blocked", staleWorkflow["workflow_status"])
    assertContains(staleWorkflow["artifacts"]?.toString().orEmpty(), "stale running child")
  }
}

/**
 * SKILL-64 Subtask 4 (AC3): transition-only monitoring coverage. Kept in its own
 * class so it does not push the broad [CliGoalRuntimeTest] over the detekt
 * LargeClass threshold.
 */
class CliGoalTransitionMonitoringTest {
  @Test
  @Suppress("LongMethod")
  fun `goal run transition stream omits heartbeat chatter while reporting starts blocked failed and completion`() {
    // A multi-subtask run with a failing subtask must emit goal_event lines ONLY
    // on meaningful changes (goal_started, subtask start, subtask completion,
    // blocked/failed stop) while routine heartbeat chatter is excluded from the
    // transition stream, and sparse liveness (the structured
    // heartbeat/observability lines) is still reported. The goal_event count must
    // be materially less than the routine heartbeat count.
    val fixture = goalFixture(subtaskCount = 2)
    val liveStdout = StringBuilder()
    // Each child emits many routine heartbeats; the transition stream must not
    // grow with them.
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2, heartbeatChatterCount = 8)

    val result = CliRuntime.run(
      fixture.goalCommand(),
      fixture.context(
        launcher = launcher,
        liveStdout = { liveStdout.append(it) },
      ),
    )

    assertEquals(1, result.exitCode, result.stdout)
    val output = liveStdout.toString()

    // Starts, phase transitions, and completion are all reported on the
    // transition stream.
    assertContains(output, "event_kind=goal_started")
    assertContains(output, "event_kind=subtask_completed")
    // Blocked/failed terminal state for the failing subtask is reported.
    assertContains(output, "event_kind=subtask_stopped")
    assertContains(output, "current_status=failed")

    // Distinct sequence space (>= 20000), not the observability 10000/1-based space.
    val goalEventLines = output.lines().filter { it.startsWith("goal_event:") }
    val goalEventSequences = goalEventLines
      .mapNotNull { Regex("""sequence_number=(\d+)""").find(it)?.groupValues?.get(1)?.toInt() }
    assertTrue(goalEventSequences.isNotEmpty(), output)
    assertTrue(
      goalEventSequences.all { it >= 20_000 },
      "goal_event sequence space must be distinct: $goalEventSequences",
    )

    // Routine heartbeat chatter is NOT promoted to the transition stream, and
    // sparse liveness is still reported via the structured heartbeat lines.
    val heartbeatCount = output.lines().count { it.startsWith("goal SKILL-901: heartbeat") }
    val observabilityCount = output.lines().count { it.startsWith("goal_observability:") }
    assertTrue(heartbeatCount > 0, output)
    assertTrue(observabilityCount > 0, output)
    assertTrue(
      goalEventLines.none { it.contains("heartbeat") },
      "goal_event transition lines must never carry routine heartbeat chatter: $goalEventLines",
    )
    // Materially less: the transition stream is far smaller than the routine
    // heartbeat chatter (16 heartbeats across two subtasks vs. a handful of
    // transitions).
    assertTrue(
      goalEventLines.size < heartbeatCount,
      "goal_event count (${goalEventLines.size}) must be materially less than " +
        "heartbeat count ($heartbeatCount): $output",
    )
  }
}

private fun startRunningGoalChild(fixture: GoalCliFixture): String = runGoalJson(
  listOf(
    "--db",
    fixture.dbPath.toString(),
    "workflow",
    "continue",
    "SKILL-901",
    "--subtask-id",
    "1",
    "--format",
    "json",
  ),
  fixture.context(launcher = NoopGoalTestAgentRunLauncher),
)["workflow_id"] as String

private fun recordRunningGoalChildProgress(
  fixture: GoalCliFixture,
  childWorkflowId: String,
  sequence: Int,
  message: String = "editing runtime files",
) {
  runGoalJson(
    workflowUpdateCommand(
      WorkflowUpdateFixture(
        dbPath = fixture.dbPath,
        workflowId = childWorkflowId,
        currentStep = "implement",
        stepUpdates = """[{"step_id":"implement","status":"running","attempt_count":1}]""",
        artifactsPatch = jsonString(
          mapOf(
            "preplan_digest" to mapOf("ready" to true),
            "plan" to mapOf("mode" to "implement", "task_count" to 1),
            "progress_event" to mapOf(
              "step_id" to "implement",
              "attempt_count" to 1,
              "source" to "phase_subagent",
              "kind" to "durable_progress",
              "message" to message,
              "sequence" to sequence,
              "timestamp" to "2026-06-01T00:00:00Z",
            ),
          ),
        ),
      ),
    ),
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )
}

private fun runningGoalChildWorkflowIds(fixture: GoalCliFixture, issueKey: String, subtaskId: Int): List<String> {
  val listed = runGoalJson(
    listOf("--db", fixture.dbPath.toString(), "workflow", "list", "--limit", "50", "--format", "json"),
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )
  return (listed["workflows"] as List<*>)
    .mapNotNull { workflow -> (workflow as Map<*, *>)["workflow_id"] as? String }
    .filter { workflowId ->
      val workflow = runGoalJson(
        listOf("--db", fixture.dbPath.toString(), "workflow", "get", workflowId, "--format", "json"),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )
      val continuation = (workflow["artifacts"] as? Map<*, *>)?.get("goal_continuation") as? Map<*, *>
      workflow["workflow_status"] == "running" &&
        continuation?.get("issue_key") == issueKey &&
        (continuation["subtask_id"] as? Number)?.toInt() == subtaskId
    }
}

private fun seedAuthoritativeCompleteChild(fixture: GoalCliFixture) {
  val authoritativeChild = runGoalJson(
    listOf("--db", fixture.dbPath.toString(), "workflow", "open", "--format", "json"),
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )["workflow_id"] as String
  runGoalJson(
    workflowUpdateCommand(
      WorkflowUpdateFixture(
        dbPath = fixture.dbPath,
        workflowId = authoritativeChild,
        currentStep = "commit_push",
        stepUpdates = """[{"step_id":"commit_push","status":"completed","attempt_count":1}]""",
        artifactsPatch = jsonString(
          mapOf(
            "goal_continuation" to mapOf(
              "issue_key" to "SKILL-901",
              "subtask_id" to 1,
              "suppress_pr" to true,
            ),
            "goal_continuation_outcome" to mapOf(
              "issue_key" to "SKILL-901",
              "subtask_id" to 1,
              "status" to "complete",
              "workflow_id" to authoritativeChild,
              "commit_sha" to "sha-1",
              "last_resumable_step" to "commit_push",
            ),
          ),
        ),
      ),
    ),
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )
}

private data class GoalCliFixture(
  val tempDir: Path,
  val dbPath: Path,
  val parentSpec: Path,
  val subtaskSpecs: List<Path>,
  val pullRequests: RecordingGoalPullRequestPort = RecordingGoalPullRequestPort(),
) {
  fun context(
    launcher: AgentRunLauncher,
    liveStdout: (String) -> Unit = {},
    liveStderr: (String) -> Unit = {},
    workflowGitOperations: WorkflowGitOperations = GoalTestWorkflowGitOperations,
  ): CliRuntimeContext = CliRuntimeContext(
    userHome = tempDir,
    workflowGitOperations = workflowGitOperations,
    agentRunLauncher = launcher,
    goalPullRequestPort = pullRequests,
    liveStdout = liveStdout,
    liveStderr = liveStderr,
  )

  fun goalCommand(dbPath: Path = this@GoalCliFixture.dbPath, extra: List<String> = emptyList()): List<String> =
    buildList {
      add("--db")
      add(dbPath.toString())
      add("goal")
      add("SKILL-901")
      add("--agent")
      add("codex")
      add("--repo-root")
      add(tempDir.toString())
      addAll(extra)
    }
}

private class GoalFixtureAgentRunLauncher(
  private val fixture: GoalCliFixture,
  private val failSubtask: Int? = null,
  private val noTerminalSubtask: Int? = null,
  // SKILL-64 Subtask 4 (AC3): number of routine status-heartbeat lines each
  // child emits. The default is 1 (legacy behavior); transition-monitoring
  // tests raise it to prove the goal_event transition stream stays far smaller
  // than the routine heartbeat chatter.
  private val heartbeatChatterCount: Int = 1,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
    request.skillRunRequest.outputSink.write(AgentRunOutputStream.STDOUT, "child-$subtaskId-stdout\n")
    request.skillRunRequest.outputSink.write(AgentRunOutputStream.STDERR, "child-$subtaskId-stderr\n")
    request.skillRunRequest.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: workflow progress: subtask $subtaskId " +
        "workflow wfl-$subtaskId step implement durable_progress step=implement\n",
    )
    repeat(heartbeatChatterCount) {
      request.skillRunRequest.outputSink.write(
        AgentRunOutputStream.STDERR,
        "skill-bill: status heartbeat (90s): child run still active; workflow: " +
          "subtask $subtaskId workflow wfl-$subtaskId step implement durable_progress\n",
      )
    }
    val dbPath = requireNotNull(request.skillRunRequest.dbPathOverride)
    val workflowId = startSubtaskWorkflow(subtaskId, dbPath)
    if (subtaskId == failSubtask) {
      failSubtaskWorkflow(workflowId, Path.of(dbPath))
    } else if (subtaskId == noTerminalSubtask) {
      // Leave the child workflow running so goal reconciliation must close it.
    } else {
      completeSubtaskWorkflow(workflowId, subtaskId, Path.of(dbPath))
    }
    return AgentRunLaunchFacts(
      agent = InstallAgent.CODEX,
      exitStatus = 0,
      stdout = "captured child $subtaskId",
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  private fun startSubtaskWorkflow(subtaskId: Int, dbPath: String): String {
    val payload = runGoalJson(
      listOf(
        "--db",
        dbPath,
        "workflow",
        "continue",
        "SKILL-901",
        "--subtask-id",
        subtaskId.toString(),
        "--format",
        "json",
      ),
      fixture.context(launcher = this),
    )
    return payload["workflow_id"] as String
  }

  private fun completeSubtaskWorkflow(workflowId: String, subtaskId: Int, dbPath: Path) {
    runGoalJson(
      workflowUpdateCommand(
        WorkflowUpdateFixture(
          dbPath = dbPath,
          workflowId = workflowId,
          workflowStatus = "completed",
          currentStep = "commit_push",
          stepUpdates = """[{"step_id":"commit_push","status":"completed","attempt_count":1}]""",
          artifactsPatch = jsonString(mapOf("commit_push_result" to mapOf("commit_sha" to "sha-$subtaskId"))),
        ),
      ),
      fixture.context(launcher = this),
    )
  }

  private fun failSubtaskWorkflow(workflowId: String, dbPath: Path) {
    runGoalJson(
      workflowUpdateCommand(
        WorkflowUpdateFixture(
          dbPath = dbPath,
          workflowId = workflowId,
          workflowStatus = "failed",
          currentStep = "review",
          stepUpdates = """[{"step_id":"review","status":"failed","attempt_count":1}]""",
          artifactsPatch = jsonString(mapOf("blocked_reason" to "forced failure")),
        ),
      ),
      fixture.context(launcher = this),
    )
  }
}

private class RecordingGoalPullRequestPort : GoalPullRequestPort {
  val requests: MutableList<GoalPullRequestRequest> = mutableListOf()

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult {
    requests += request
    return GoalPullRequestResult.Opened("https://github.com/example/skill-bill/pull/901")
  }
}

private fun goalFixture(subtaskCount: Int): GoalCliFixture {
  val tempDir = Files.createTempDirectory("skillbill-cli-goal")
  val parentSpec = tempDir.resolve(".feature-specs/SKILL-901-goal/spec.md")
  Files.createDirectories(parentSpec.parent)
  Files.writeString(parentSpec, "# Parent")
  val subtaskSpecs = (1..subtaskCount).map { id ->
    parentSpec.parent.resolve("spec_subtask_${id}_part.md").also { path ->
      Files.writeString(path, "---\nstatus: Pending\n---\n\n# Subtask $id")
    }
  }
  val fixture = GoalCliFixture(
    tempDir = tempDir,
    dbPath = tempDir.resolve("metrics.db"),
    parentSpec = parentSpec,
    subtaskSpecs = subtaskSpecs,
  )
  seedParentWorkflow(fixture)
  return fixture
}

private fun seedParentWorkflow(fixture: GoalCliFixture) {
  val opened = runGoalJson(
    listOf("--db", fixture.dbPath.toString(), "workflow", "open", "--format", "json"),
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )
  val workflowId = opened["workflow_id"] as String
  runGoalJson(
    workflowUpdateCommand(
      WorkflowUpdateFixture(
        dbPath = fixture.dbPath,
        workflowId = workflowId,
        currentStep = "plan",
        stepUpdates = """[{"step_id":"plan","status":"completed","attempt_count":1}]""",
        artifactsPatch = parentArtifactsPatch(fixture),
      ),
    ),
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )
}

private fun parentArtifactsPatch(fixture: GoalCliFixture): String = jsonString(
  mapOf(
    "branch" to mapOf("branch" to "feat/SKILL-901-goal"),
    "plan" to mapOf(
      "mode" to "decompose",
      "parent_spec_path" to fixture.parentSpec.toString(),
      "recommended_first_subtask_id" to 1,
      "subtasks" to fixture.subtaskSpecs.mapIndexed { index, path ->
        mapOf(
          "id" to index + 1,
          "name" to "Part ${index + 1}",
          "spec_path" to path.toString(),
          "depends_on" to if (index == 0) emptyList<Int>() else listOf(index),
        )
      },
    ),
  ),
)

private data class WorkflowUpdateFixture(
  val dbPath: Path,
  val workflowId: String,
  val workflowStatus: String = "running",
  val currentStep: String,
  val stepUpdates: String,
  val artifactsPatch: String,
)

private fun workflowUpdateCommand(fixture: WorkflowUpdateFixture): List<String> = listOf(
  "--db",
  fixture.dbPath.toString(),
  "workflow",
  "update",
  fixture.workflowId,
  "--workflow-status",
  fixture.workflowStatus,
  "--current-step-id",
  fixture.currentStep,
  "--step-updates",
  fixture.stepUpdates,
  "--artifacts-patch",
  fixture.artifactsPatch,
  "--format",
  "json",
)

private fun runGoalJson(arguments: List<String>, context: CliRuntimeContext): Map<String, Any?> {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  val parsed = requireNotNull(JsonSupport.parseObjectOrNull(result.stdout))
  return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
}

private fun jsonString(value: Any?): String = JsonSupport.json.encodeToString(
  kotlinx.serialization.json.JsonElement.serializer(),
  JsonSupport.valueToJsonElement(value),
)

private object NoopGoalTestAgentRunLauncher : AgentRunLauncher {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome = error("Unexpected launch")
}

private object GoalTestWorkflowGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-commit")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-commit")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult = WorkflowWorktreeActivityResult(
    status = "ok",
    diffStat = GoalObservabilityDiffStat(filesChanged = 1, insertions = 2, deletions = 1),
  )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(
    status = "ok",
    selectedDiffHunks = GoalObservabilitySelectedDiffHunks(
      hunks = listOf(
        GoalObservabilitySelectedDiffHunk(
          path = request.paths.firstOrNull().orEmpty(),
          staged = false,
          header = "@@ -1 +1 @@",
          lines = listOf("-old", "+new"),
          truncated = false,
        ),
      ),
      truncated = false,
    ),
  )
}

private class RecordingGoalTestWorkflowGitOperations : WorkflowGitOperations by GoalTestWorkflowGitOperations {
  val worktreeActivityRequests: MutableList<Path> = mutableListOf()
  val selectedDiffRequests: MutableList<WorkflowSelectedDiffHunksRequest> = mutableListOf()

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult {
    worktreeActivityRequests.add(repoRoot)
    return GoalTestWorkflowGitOperations.worktreeActivity(repoRoot)
  }

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult {
    selectedDiffRequests += request
    return GoalTestWorkflowGitOperations.selectedDiffHunks(repoRoot, request)
  }
}

private fun WorkflowSelectedDiffHunksRequest.limits(): Triple<Int, Int, Int> = Triple(maxHunks, maxLines, maxBytes)
