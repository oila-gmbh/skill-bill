package skillbill.cli

import kotlinx.serialization.json.JsonElement
import skillbill.SkillBillVersion
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.cli.core.CliRuntime
import skillbill.cli.goal.GOAL_EXIT_BLOCKED
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION
import skillbill.db.core.DatabaseRuntime
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.time.NoopRuntimeTimingPort
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
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliGoalRuntimeExecutionTest {
  @Test
  fun `goal status help documents diff observability cost controls`() {
    val status = CliRuntime.run(listOf("goal", "status", "--help"), CliRuntimeContext())
    val watch = CliRuntime.run(listOf("goal", "watch", "--help"), CliRuntimeContext())

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "--diff-stat")
    assertContains(status.stdout, "Runs git diff --numstat once")
    assertContains(status.stdout, "--diff-hunk")
    assertContains(status.stdout, "noisier")
    assertContains(status.stdout, "--monitor")
    assertEquals(0, watch.exitCode, watch.stdout)
    assertContains(watch.stdout, "--interval-seconds")
    assertContains(watch.stdout, "repeated git cost")
    assertContains(watch.stdout, "--show-unchanged")
    assertContains(watch.stdout, "shown by default")
    assertContains(watch.stdout, "--suppress-unchanged")
    assertContains(watch.stdout.replace(Regex("""\s+"""), " "), "Zero follows until the goal finishes")
  }

  @Test
  fun `goal reset soft preserves state and reports a terminal blocked child`() {
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

    assertEquals(1, reset.exitCode, reset.stdout)
    assertContains(reset.stdout, "status: recovery_required")
    assertContains(reset.stdout, "mode: soft")
    assertContains(reset.stdout, "before: status=blocked")
    assertContains(reset.stdout, "after: status=in_progress")
    assertContains(reset.stdout, "id=1; status=complete")
    assertContains(reset.stdout, "id=2; status=in_progress")
    assertContains(reset.stdout, "last_resumable_step=review")
    assertContains(reset.stdout, "recovery: subtask=2; workflow_id=")
    assertContains(reset.stdout, "classification=incompatible_terminal")
    assertContains(
      reset.stdout,
      "recovery_command: skill-bill goal reset SKILL-901 --subtask 2 --delete-child-workflow",
    )
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )
    assertContains(status.stdout, "complete: 1")
    assertContains(status.stdout, "pending: 0")
    assertContains(status.stdout, "blocked: 1")
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
        "--repo-root",
        fixture.tempDir.toString(),
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
        "--repo-root",
        fixture.tempDir.toString(),
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
  fun `goal foreground run completes all subtasks with bounded terminal surfaces`() {
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
    assertContains(result.stdout, "goal SKILL-901: finished")
    assertContains(result.stdout, "summary: ")
    assertContains(result.stdout, "2/2 subtasks complete; pending=0; blocked=0")
    assertContains(result.stdout, "PR https://github.com/example/skill-bill/pull/901")
    assertEquals(2, result.stdout.lines().count { it.isNotBlank() })
    assertEquals(listOf(1, 2), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    assertContains(liveStdout.toString(), "goal SKILL-901: launched runtime executable=")
    assertContains(liveStdout.toString(), "version=${SkillBillVersion.VALUE} build_id=${SkillBillVersion.VALUE}")
    assertContains(liveStdout.toString(), "goal watch SKILL-901 --repo-root")
    assertContains(liveStdout.toString(), "goal status SKILL-901 --repo-root")
    assertContains(liveStdout.toString(), "child-1-stdout")
    assertContains(liveStderr.toString(), "child-1-stderr")
    assertEquals(listOf(null, null), launcher.childLaunches.map { it.skillRunRequest.timeout })
    assertEquals(1, fixture.pullRequests.requests.size)
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.prepareStatement(
        "SELECT normalized_issue_key, governed_spec_path, route_scope " +
          "FROM feature_task_execution_identities ORDER BY governed_spec_path",
      ).use { statement ->
        statement.executeQuery().use { rows ->
          val identities = buildList {
            while (rows.next()) {
              add(Triple(rows.getString(1), rows.getString(2), rows.getString(3)))
            }
          }
          assertEquals(2, identities.size)
          assertTrue(identities.all { it.first == "SKILL-901" && it.third == "goal_child" })
          assertEquals(
            listOf(
              ".feature-specs/SKILL-901-goal/spec_subtask_1_part.md",
              ".feature-specs/SKILL-901-goal/spec_subtask_2_part.md",
            ),
            identities.map { it.second },
          )
        }
      }
    }
  }

  @Test
  fun `goal run does not relay progress or transition events`() {
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
    assertEquals(1, output.lines().count { it.startsWith("goal SKILL-901: launched") })
    assertContains(output, "goal watch SKILL-901 --repo-root")
    assertContains(output, "goal status SKILL-901 --repo-root")
    assertFalse(output.contains("goal_event:"), output)
    assertFalse(output.contains("heartbeat"), output)
    assertFalse(output.contains("goal_observability:"), output)
    assertFalse(output.contains("child-1-stdout"), output)
    assertFalse(output.contains("child-1-stderr"), output)
    assertContains(result.stdout, "goal SKILL-901: finished")
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
        executableLookup = ExecutableLookup { true },
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf("claude"), launcher.childLaunches.map { it.agentId }.distinct())
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
        environment = mapOf("CLAUDECODE" to "1", "SKILL_BILL_AGENT" to "junie"),
        executableLookup = ExecutableLookup { true },
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    // --agent codex (from goalCommand) wins over SKILL_BILL_AGENT and detection.
    assertEquals(listOf("codex"), launcher.childLaunches.map { it.agentId }.distinct())
  }

  @Test
  fun `goal default live output emits the monitoring block and hides raw child output`() {
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
    assertContains(liveStdout.toString(), "goal watch SKILL-901 --repo-root")
    assertContains(liveStdout.toString(), "goal status SKILL-901 --repo-root")
    assertFalse(liveStdout.toString().contains("heartbeat"), liveStdout.toString())
    assertFalse(liveStdout.toString().contains("goal_observability:"), liveStdout.toString())
    assertContains(liveStdout.toString(), "goal SKILL-901: launched runtime executable=")
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
  fun `goal interrupted legacy child blocks without recapturing a runtime review baseline`() {
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

    assertInterruptedLegacyChildOutput(status, watch, resumed, launcher)
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

  private fun assertInterruptedLegacyChildOutput(
    status: CliExecutionResult,
    watch: CliExecutionResult,
    resumed: CliExecutionResult,
    launcher: GoalFixtureAgentRunLauncher,
  ) {
    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "current_subtask: 1")
    assertContains(status.stdout, "current_step: implement")
    // SKILL-103 AC1: the CLI child carries no persisted agent attribution, so active_agent
    // is omitted (rendered as none) rather than leaked from the caller's --agent codex.
    assertContains(status.stdout, "active_agent: none")
    // SKILL-175: the continuation child is a RUNTIME-mode workflow with no live worker ownership,
    // so liveness is idle (UNKNOWN is reserved for lease-read failure / non-runtime-mode rows).
    assertContains(status.stdout, "execution_liveness: idle")
    assertContains(status.stdout, "latest_liveness_signal: liveness=durable_progress phase=implement")
    assertContains(status.stdout, "role=phase_subagent sequence=12")
    assertContains(status.stdout, "latest_observability: phase=implement role=phase_subagent")
    assertContains(status.stdout, "liveness=durable_progress sequence=12")
    assertEquals(0, watch.exitCode, watch.stdout)
    assertContains(watch.stdout, "watch_refresh: index=1 status=ok current_subtask=1 current_step=implement")
    assertContains(watch.stdout, "watch_observability: index=1 phase=implement role=phase_subagent")
    assertContains(watch.stdout, "sequence=12")
    assertEquals(GOAL_EXIT_BLOCKED, resumed.exitCode, resumed.stdout)
    assertEquals(2, launcher.requests.size)
    assertTrue(launcher.childLaunches.isEmpty())
    assertContains(resumed.stdout, "Could not capture the goal-subtask review baseline")
  }
}

/**
 * SKILL-160 subtask 2: `--include-shared-preplan` cascade + relaunch. Kept in its own class so it
 * does not push [CliGoalRuntimeTest] over the detekt LargeClass threshold.
 */}

