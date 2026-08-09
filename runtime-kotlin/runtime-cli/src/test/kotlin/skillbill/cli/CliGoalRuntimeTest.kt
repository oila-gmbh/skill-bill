package skillbill.cli

import skillbill.SkillBillVersion
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.cli.core.CliRuntime
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
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
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
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
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

@Suppress("LargeClass") // one CLI surface per suite; splitting would scatter goal-verb coverage
class CliGoalRuntimeTest {
  @Test
  fun `goal command is registered with status subcommand`() {
    val result = CliRuntime.run(listOf("goal", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "Run a decomposed goal in the foreground.")
    assertContains(result.stdout, "status")
    assertContains(result.stdout, "watch")
    assertContains(result.stdout, "pause")
    assertContains(result.stdout, "stop")
    assertContains(result.stdout, "resume")
    assertContains(result.stdout, "reset")
    assertContains(result.stdout, "--debug-child-output")
    assertContains(result.stdout, "raw child streams hidden")
  }

  @Test
  fun `goal completion drains a non-empty telemetry outbox`() {
    val fixture = goalFixture(subtaskCount = 1)
    val requester = RecordingTelemetryRequester()
    fixture.materializeDatabaseWithTelemetry(level = "anonymous", requester = requester)
    seedTelemetryOutbox(fixture.dbPath, "skillbill_fixture_event")
    assertEquals(1, pendingTelemetryOutboxCount(fixture.dbPath))

    val result = CliRuntime.run(
      fixture.goalCommand(),
      fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture), requester = requester),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "goal SKILL-901: finished")
    assertEquals(0, pendingTelemetryOutboxCount(fixture.dbPath))
    assertTrue(requester.requests.isNotEmpty())
    assertTrue(requester.requests.all { it.contains(TELEMETRY_FIXTURE_PROXY_URL) }, requester.requests.toString())
  }

  @Test
  fun `goal run accepts positive stop-after subtask and does not launch the next child`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--stop-after-subtask", "1")),
      fixture.context(launcher = launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "goal SKILL-901: paused")
    assertEquals(listOf(1), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )
    assertContains(status.stdout, "paused: true")
    assertContains(status.stdout, "stop_after_subtask: 1")
    assertContains(status.stdout, "complete: 1")
  }

  @Test
  fun `goal run rejects missing and non-positive stop-after subtask values`() {
    val missingFixture = goalFixture(subtaskCount = 1)
    val missing = CliRuntime.run(
      missingFixture.goalCommand(extra = listOf("--stop-after-subtask")),
      missingFixture.context(launcher = GoalFixtureAgentRunLauncher(missingFixture)),
    )
    assertEquals(1, missing.exitCode, missing.stdout)
    assertContains(missing.stdout, "option --stop-after-subtask requires a value")

    val zeroFixture = goalFixture(subtaskCount = 1)
    val zero = CliRuntime.run(
      zeroFixture.goalCommand(extra = listOf("--stop-after-subtask", "0")),
      zeroFixture.context(launcher = GoalFixtureAgentRunLauncher(zeroFixture)),
    )
    assertEquals(1, zero.exitCode, zero.stdout)
    assertContains(zero.stdout, "--stop-after-subtask must be a positive integer")

    val unknownFixture = goalFixture(subtaskCount = 1)
    val unknown = CliRuntime.run(
      unknownFixture.goalCommand(extra = listOf("--stop-after-subtask", "99")),
      unknownFixture.context(launcher = GoalFixtureAgentRunLauncher(unknownFixture)),
    )
    assertEquals(1, unknown.exitCode, unknown.stdout)
    assertContains(unknown.stdout, "has no subtask '99'")
  }

  @Test
  fun `goal pause is consumed at an unlaunched boundary and remains idempotent`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val command = listOf(
      "--db",
      fixture.dbPath.toString(),
      "goal",
      "pause",
      "SKILL-901",
      "--repo-root",
      fixture.tempDir.toString(),
    )

    val first = CliRuntime.run(command, fixture.context(launcher = launcher))
    val second = CliRuntime.run(command, fixture.context(launcher = launcher))

    assertEquals(0, first.exitCode, first.stdout)
    assertEquals(0, second.exitCode, second.stdout)
    assertContains(first.stdout, "goal SKILL-901: paused")
    assertContains(first.stdout, "reason: operator_request")
    assertEquals(true, first.payload?.get("pause_requested"))
    assertEquals(true, first.payload?.get("paused"))
    assertEquals(true, second.payload?.get("pause_requested"))
    assertEquals(true, second.payload?.get("paused"))
    assertEquals(emptyList(), launcher.childLaunches)
  }

  @Test
  fun `goal stop records the operator stop and stays idempotent across two invocations`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val command = goalControlCommand(fixture, "stop")

    val first = CliRuntime.run(command, fixture.context(launcher = launcher))
    val second = CliRuntime.run(command, fixture.context(launcher = launcher))

    // No runner holds this fixture, so the stop is durable-only: exit 0 both times, no termination.
    assertEquals(0, first.exitCode, first.stdout)
    assertEquals(0, second.exitCode, second.stdout)
    assertEquals("no_live_lease", first.payload?.get("status"))
    assertEquals("operator_stop", first.payload?.get("pause_reason"))
    assertEquals(false, first.payload?.get("termination_attempted"))
    assertContains(first.stdout, "reason: operator_stop")
    assertContains(first.stdout, "paused at: ")
    assertEquals("already_stopped", second.payload?.get("status"))
    assertEquals(false, second.payload?.get("termination_attempted"))
    assertEquals(emptyList(), launcher.childLaunches)
  }

  @Test
  fun `goal stop reports the durable pause on status without a second termination`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    CliRuntime.run(goalControlCommand(fixture, "stop"), fixture.context(launcher = launcher))
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )

    assertContains(status.stdout, "paused: true")
    assertEquals(emptyList(), launcher.childLaunches)
  }

  @Test
  fun `goal stop on an unknown issue key reports not_found with a non-zero exit`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "stop",
        "SKILL-404",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertEquals("not_found", result.payload?.get("status"))
  }

  @Test
  fun `goal resume clears a durable pause and reports not_paused when nothing is paused`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val pauseCommand = goalControlCommand(fixture, "pause")
    val resumeCommand = goalControlCommand(fixture, "resume")

    CliRuntime.run(pauseCommand, fixture.context(launcher = launcher))
    forcePendingPauseRequest(fixture.dbPath)
    val resumed = CliRuntime.run(resumeCommand, fixture.context(launcher = launcher))
    val again = CliRuntime.run(resumeCommand, fixture.context(launcher = launcher))
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertContains(resumed.stdout, "goal SKILL-901: resumed")
    assertContains(resumed.stdout, "cleared reason: operator_request")
    assertEquals(false, resumed.payload?.get("paused"))
    assertEquals(false, resumed.payload?.get("pause_requested"))
    assertEquals("not_paused", again.payload?.get("status"))
    assertContains(status.stdout, "paused: false")
    assertContains(status.stdout, "pause_requested: false")
    assertEquals(emptyList(), launcher.childLaunches)
  }

  @Test
  fun `goal launch clears a pause request that never reached a boundary`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(goalControlCommand(fixture, "pause"), fixture.context(launcher = launcher))
    forcePendingPauseRequest(fixture.dbPath)

    val result = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(listOf(1), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    assertContains(status.stdout, "paused: false")
    assertContains(status.stdout, "pause_requested: false")
  }

  @Test
  fun `goal watch stops after a durable pause without polling forever`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "pause",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher = launcher),
    ).also { result -> assertEquals(0, result.exitCode, result.stdout) }

    val watch = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
        "--interval-seconds",
        "0",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertEquals(1, watch.payload?.get("refresh_count"))
    assertEquals("goal_paused", watch.payload?.get("stop_reason"))
  }

  // A pause request is honoured at the next launch boundary, so the current subtask keeps running.
  // Treating the request as terminal ended the monitor immediately and left the operator blind for the
  // rest of that subtask.
  @Test
  fun `goal watch keeps following while a pause is requested but not reached`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(goalControlCommand(fixture, "pause"), fixture.context(launcher = launcher))
    forcePendingPauseRequest(fixture.dbPath)

    val watch = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
        "--interval-seconds",
        "0",
        "--max-refreshes",
        "1",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertEquals("max_refreshes", watch.payload?.get("stop_reason"))
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
    assertEquals(1, resumed.exitCode, resumed.stdout)
    assertEquals(2, launcher.requests.size)
    assertTrue(launcher.childLaunches.isEmpty())
    assertContains(resumed.stdout, "Could not capture the goal-subtask review baseline")
  }
}

/**
 * SKILL-160 subtask 2: `--include-shared-preplan` cascade + relaunch. Kept in its own class so it
 * does not push [CliGoalRuntimeTest] over the detekt LargeClass threshold.
 */
class CliGoalSharedPreplanReplanTest {
  @Test
  fun `include-shared-preplan lists cascade then relaunch regenerates without reopening terminals`() {
    val fixture = goalFixture(subtaskCount = 3)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    advanceToSubtaskThreeBoundary(fixture, launcher)
    assertSharedPreplanCascade(fixture, launcher)
    assertRelaunchRegeneratesIntoSubtaskThree(fixture, launcher)
  }

  private fun advanceToSubtaskThreeBoundary(fixture: GoalCliFixture, launcher: GoalFixtureAgentRunLauncher) {
    val advanced = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--stop-after-subtask", "2")),
      fixture.context(launcher = launcher),
    )
    assertEquals(1, advanced.exitCode, advanced.stdout)
    assertEquals(listOf(1, 2), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    val statusBefore = goalStatus(fixture, launcher)
    assertContains(statusBefore.stdout, "shared_preplan=true")
    assertContains(statusBefore.stdout, "complete: 2")
    CliRuntime.run(goalControlCommand(fixture, "resume"), fixture.context(launcher = launcher))
  }

  private fun assertSharedPreplanCascade(fixture: GoalCliFixture, launcher: GoalFixtureAgentRunLauncher) {
    val replan = CliRuntime.run(
      listOf(
        "--db", fixture.dbPath.toString(),
        "goal", "replan", "SKILL-901",
        "--subtask", "3",
        "--include-shared-preplan",
        "--repo-root", fixture.tempDir.toString(),
      ),
      fixture.context(launcher = launcher),
    )
    assertEquals(0, replan.exitCode, replan.stdout)
    assertContains(replan.stdout, "discarded_shared_preplan: true")
    assertContains(replan.stdout, "cascaded_plans=")
    assertTrue(
      replan.stdout.contains("cascaded_plans=1,2") || replan.stdout.contains("cascaded_plans=1"),
      replan.stdout,
    )
    assertEquals(true, replan.payload?.get("discarded_shared_preplan"))
    @Suppress("UNCHECKED_CAST")
    val cascaded = replan.payload?.get("cascaded_plan_subtask_ids") as? List<*>
    assertTrue(!cascaded.isNullOrEmpty(), "cascade must name at least one sibling plan")
    assertFalse(3 in cascaded.mapNotNull { (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull() })
    // The replan deletes children hydrated from a discarded plan, but never a completed subtask's:
    // that would drop the commit_sha and workflow_id mapping this cascade is required to preserve.
    val clearedChildren = (replan.payload.get("cleared_child_subtask_ids") as? List<*>)
      ?.mapNotNull { (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull() }
      .orEmpty()
    assertFalse(1 in clearedChildren, "a completed subtask's child must survive the cascade")
    assertFalse(2 in clearedChildren, "a completed subtask's child must survive the cascade")
    val statusAfter = goalStatus(fixture, launcher)
    assertContains(statusAfter.stdout, "shared_preplan=false")
    assertContains(statusAfter.stdout, "complete: 2")
    assertTrue(
      statusAfter.stdout.contains("planning_reason:") &&
        (statusAfter.stdout.contains("not started") || statusAfter.stdout.contains("preplan")),
      statusAfter.stdout,
    )
  }

  private fun assertRelaunchRegeneratesIntoSubtaskThree(
    fixture: GoalCliFixture,
    launcher: GoalFixtureAgentRunLauncher,
  ) {
    val planningBefore = launcher.requests.count {
      it.skillRunRequest.goalContinuation == null && it.skillRunRequest.promptOverride != null
    }
    launcher.childLaunches.clear()
    val relaunch = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    assertEquals(0, relaunch.exitCode, relaunch.stdout)
    assertEquals(listOf(3), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    val planningAfter = launcher.requests.count {
      it.skillRunRequest.goalContinuation == null && it.skillRunRequest.promptOverride != null
    }
    assertTrue(
      planningAfter > planningBefore,
      "relaunch must regenerate shared preplan/plans after opt-in discard",
    )
    assertContains(goalStatus(fixture, launcher).stdout, "complete: 3")
  }

  private fun goalStatus(fixture: GoalCliFixture, launcher: GoalFixtureAgentRunLauncher) = CliRuntime.run(
    listOf(
      "--db", fixture.dbPath.toString(),
      "goal", "status", "SKILL-901",
      "--agent", "codex",
      "--repo-root", fixture.tempDir.toString(),
    ),
    fixture.context(launcher = launcher),
  )
}

/**
 * Goal-watch follow-loop coverage is isolated from the broader goal runtime suite so each test
 * class stays focused and below the detekt LargeClass threshold.
 */
class CliGoalWatchRuntimeTest {
  @Test
  fun `goal watch resets two idle refreshes with live and stops only after three consecutive idle refreshes`() {
    val fixture = goalFixture(subtaskCount = 1)
    val childWorkflowId = startRunningRuntimeGoalChild(fixture)
    val resetOutput = StringBuilder()
    var observed = 0

    val reset = CliRuntime.run(
      listOf(
        "--db", fixture.dbPath.toString(),
        "goal", "watch", "SKILL-901",
        "--interval-seconds", "0",
        "--max-refreshes", "4",
        "--show-unchanged",
      ),
      fixture.context(
        launcher = NoopGoalTestAgentRunLauncher,
        liveStdout = {
          resetOutput.append(it)
          if (it.startsWith("watch_refresh:") && ++observed == 2) {
            seedLiveWorkerLease(fixture, childWorkflowId)
          }
        },
      ),
    )

    assertEquals("max_refreshes", reset.payload?.get("stop_reason"))
    assertEquals(4, reset.payload?.get("refresh_count"))
    assertContains(resetOutput.toString(), "index=3 status=ok")
    assertContains(resetOutput.toString(), "execution_liveness=live")

    clearWorkerLease(fixture, childWorkflowId)
    val idleOutput = StringBuilder()
    val idle = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--interval-seconds",
        "0",
      ),
      fixture.context(
        launcher = NoopGoalTestAgentRunLauncher,
        liveStdout = { idleOutput.append(it) },
      ),
    )

    assertEquals("goal_idle", idle.payload?.get("stop_reason"))
    assertEquals(3, idle.payload?.get("refresh_count"))
    assertContains(idle.stdout, "watch_refresh: index=3 status=ok")
    assertContains(idle.stdout, "execution_liveness=idle")
    assertFalse(idleOutput.toString().contains("index=4"), idleOutput.toString())
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
    assertEquals(2, watch.payload?.get("refresh_count"))
    assertEquals("max_refreshes", watch.payload?.get("stop_reason"))
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal watch explicit one shot preserves payload and does not sleep`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

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
        "60",
        "--max-refreshes",
        "1",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertEquals("ok", watch.payload?.get("status"))
    assertEquals("SKILL-901", watch.payload?.get("issue_key"))
    assertEquals(1, watch.payload?.get("refresh_count"))
    assertEquals(60, watch.payload?.get("interval_seconds"))
    assertEquals("max_refreshes", watch.payload?.get("stop_reason"))
    assertEquals(1, (watch.payload?.get("latest_refresh") as? Map<*, *>)?.get("refresh_index"))
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal watch accepts zero refresh bound and stops when goal is not found`() {
    val fixture = goalFixture(subtaskCount = 1)

    val watch = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-404",
        "--interval-seconds",
        "60",
        "--max-refreshes",
        "0",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(1, watch.exitCode, watch.stdout)
    assertEquals(1, watch.payload?.get("refresh_count"))
    assertEquals("not_found", watch.payload?.get("stop_reason"))
    assertContains(watch.stdout, "watch_refresh: index=1 status=not_found")
  }

  @Test
  fun `goal watch rejects negative refresh bound`() {
    val fixture = goalFixture(subtaskCount = 1)

    val watch = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--max-refreshes",
        "-1",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(1, watch.exitCode, watch.stdout)
    assertContains(watch.stdout, "--max-refreshes must be non-negative")
  }

  @Test
  fun `goal watch default follows until the first terminal projection`() {
    val fixture = goalFixture(subtaskCount = 1)
    val childWorkflowId = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, childWorkflowId, sequence = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val liveStdout = StringBuilder()
    var refreshesObserved = 0

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
      ),
      fixture.context(
        launcher = launcher,
        liveStdout = {
          liveStdout.append(it)
          if (it.startsWith("watch_refresh:") && ++refreshesObserved == 1) {
            completeRunningGoalChild(fixture, childWorkflowId)
          }
        },
      ),
    )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertEquals(2, watch.payload?.get("refresh_count"))
    assertEquals("goal_terminal", watch.payload?.get("stop_reason"))
    assertContains(liveStdout.toString(), "watch_refresh: index=1 status=ok")
    assertContains(watch.stdout, "watch_refresh: index=2 status=ok")
    assertFalse(watch.stdout.contains("watch_refresh: index=3"), watch.stdout)
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal watch blocked but pending continues until its explicit bound`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 1)
    val run = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    assertEquals(1, run.exitCode, run.stdout)
    val launchCount = launcher.requests.size

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
        "2",
      ),
      fixture.context(launcher = launcher),
    )

    val latest = watch.payload?.get("latest_refresh") as? Map<*, *>
    assertEquals(2, watch.payload?.get("refresh_count"))
    assertEquals("max_refreshes", watch.payload?.get("stop_reason"))
    assertEquals(1, latest?.get("blocked_count"))
    assertEquals(1, latest?.get("pending_count"))
    assertEquals(launchCount, launcher.requests.size)
  }

  @Test
  fun `goal watch shows unchanged refreshes by default and supports opt in suppression`() {
    val fixture = goalFixture(subtaskCount = 1)
    val childWorkflowId = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, childWorkflowId, sequence = 1)
    val suppressedOutput = StringBuilder()
    val shownOutput = StringBuilder()
    var suppressedRefreshesObserved = 0

    val suppressed = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--interval-seconds",
        "0",
        "--max-refreshes",
        "3",
        "--suppress-unchanged",
      ),
      fixture.context(
        launcher = NoopGoalTestAgentRunLauncher,
        liveStdout = {
          suppressedOutput.append(it)
          if (it.startsWith("watch_refresh:") && ++suppressedRefreshesObserved == 1) {
            advanceRunningGoalChildToReview(fixture, childWorkflowId)
          }
        },
      ),
    )
    val shown = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "watch",
        "SKILL-901",
        "--interval-seconds",
        "0",
        "--max-refreshes",
        "3",
      ),
      fixture.context(
        launcher = NoopGoalTestAgentRunLauncher,
        liveStdout = { shownOutput.append(it) },
      ),
    )

    assertWatchRefreshRendering(suppressedOutput, shownOutput, suppressed, shown)
  }

  private fun assertWatchRefreshRendering(
    suppressedOutput: StringBuilder,
    shownOutput: StringBuilder,
    suppressed: CliExecutionResult,
    shown: CliExecutionResult,
  ) {
    assertEquals(3, suppressedOutput.lines().count { it.startsWith("watch_refresh:") })
    assertContains(
      suppressedOutput.toString(),
      "watch_refresh: index=1 status=ok current_subtask=1 current_step=implement",
    )
    assertContains(
      suppressedOutput.toString(),
      "watch_refresh: index=2 status=ok current_subtask=1 current_step=review",
    )
    assertEquals(3, shownOutput.lines().count { it.startsWith("watch_refresh:") })
    assertContains(suppressed.stdout, "watch_refresh: index=3 status=ok")
    assertContains(shown.stdout, "watch_refresh: index=3 status=ok")
    assertEquals(3, suppressed.payload?.get("refresh_count"))
    assertEquals(3, shown.payload?.get("refresh_count"))
  }
}

class CliGoalExecutionOptionsTest {
  @Test
  fun `goal max wall clock flag passes optional cap to child run`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--max-wall-clock-minutes", "180")),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(180.minutes, launcher.childLaunches.single().skillRunRequest.timeout)
  }

  @Test
  fun `goal no-live-output keeps launch monitoring but suppresses child output tee`() {
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
    assertContains(liveStdout.toString(), "goal SKILL-901: launched runtime executable=")
    assertContains(liveStdout.toString(), "version=${SkillBillVersion.VALUE} build_id=${SkillBillVersion.VALUE}")
    assertContains(liveStdout.toString(), "goal watch SKILL-901 --repo-root")
    assertFalse(liveStdout.toString().contains("heartbeat"), liveStdout.toString())
    assertEquals(false, liveStdout.toString().contains("child-1-stdout"), liveStdout.toString())
    assertEquals("", liveStderr.toString())
  }

  @Test
  fun `goal forced failure exits non-zero and status reports blocked projection`() {
    val fixture = goalFixture(subtaskCount = 3)
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2)

    val result = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "goal SKILL-901: failed at subtask 2")
    assertEquals(listOf(1, 2), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
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
    // SKILL-103 AC1: CLI child carries no persisted agent => active_agent omitted.
    assertContains(status.stdout, "active_agent: none")
  }

  @Test
  fun `goal no terminal outcome marks child workflow blocked`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)

    val result = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "goal SKILL-901: blocked at subtask 1")
    assertContains(result.stdout, "without a terminal workflow-store outcome")
    val workflowId = result.payload?.get("workflow_id")?.toString().orEmpty()
    assertTrue(workflowId.isNotBlank())
    val child = RuntimeWorkflowTestSupport.get(
      fixture.dbPath,
      workflowId,
      fixture.context(launcher = launcher),
    )

    assertEquals("blocked", child["workflow_status"])
    // SKILL-175: the goal runner's pre-opened child is hydrated through planning (preplan + plan
    // completed, implement current), so a no-terminal-outcome block parks at implement — not the
    // fresh continuation child's initial preplan.
    assertEquals("implement", child["current_step_id"])
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
    assertContains(result.stdout, "goal SKILL-901: finished")
    assertEquals(recoveredDb.toString(), launcher.childLaunches.single().skillRunRequest.dbPathOverride)
  }

  @Test
  fun `goal status reads checked-in decomposition manifest without importing workflow state`() {
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
    DriverManager.getConnection("jdbc:sqlite:$recoveredDb").use { connection ->
      connection.prepareStatement("SELECT COUNT(*) FROM feature_task_workflows WHERE issue_key = ?").use { statement ->
        statement.setString(1, "SKILL-901")
        statement.executeQuery().use { rows ->
          assertTrue(rows.next())
          assertEquals(0, rows.getInt(1))
        }
      }
    }
    // SKILL-103 AC1: no child run persisted => active_agent is omitted (rendered as none), never
    // sourced from the status caller's --agent resolution chain.
    assertContains(status.stdout, "active_agent: none")
  }

  @Test
  fun `goal status prefers authoritative complete child without mutating stale running child workflow`() {
    val fixture = goalFixture(subtaskCount = 1)
    val staleChild = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, staleChild, sequence = 9, message = "stale active event")
    seedAuthoritativeCompleteChild(fixture)

    val status = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )
    val staleWorkflow = RuntimeWorkflowTestSupport.get(
      fixture.dbPath,
      staleChild,
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "complete: 1")
    assertContains(status.stdout, "pending: 0")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "current_subtask: none")
    assertEquals(false, status.stdout.contains("latest_observability:"), status.stdout)
    assertEquals("running", staleWorkflow["workflow_status"])
    assertEquals(false, staleWorkflow["artifacts"]?.toString().orEmpty().contains("stale running child"))
  }
}

/**
 * Kept in its own class so it does not push the broad [CliGoalRuntimeTest] over the detekt
 * LargeClass threshold.
 */
class CliGoalUnaddressedFindingsTest {
  @Test
  fun `an unreadable ledger reports itself instead of an affirmative zero`() {
    val fixture = goalFixture(subtaskCount = 1)
    DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
      connection.prepareStatement(
        "INSERT INTO unaddressed_findings (issue_key, workflow_id, subtask_id, review_pass_number, " +
          "finding_ordinal, severity, issue_category, location, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
      ).use { statement ->
        statement.setString(1, "SKILL-901")
        statement.setString(2, "wftr-poison-1")
        statement.setInt(3, 1)
        statement.setInt(4, 1)
        statement.setInt(5, 1)
        statement.setString(6, "critical")
        statement.setString(7, "behavior_correctness")
        statement.setString(8, "src/Feature.kt:42")
        statement.setString(9, "Poison row persisted by an older writer")
        statement.executeUpdate()
      }
    }

    val result = CliRuntime.run(
      fixture.goalCommand(),
      fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture)),
    )

    assertContains(result.stdout, "goal SKILL-901: finished")
    assertFalse(result.stdout.contains("Poison row persisted"))
    assertFalse(result.stdout.contains("unaddressed_findings"))
  }
}

/** Bounded default goal output coverage, isolated from the broad runtime suite. */
class CliGoalTransitionMonitoringTest {
  @Test
  @Suppress("LongMethod")
  fun `goal run emits only launch monitoring and one bounded terminal notification`() {
    val fixture = goalFixture(subtaskCount = 2)
    val liveStdout = StringBuilder()
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2, childDiagnosticChatterCount = 8)

    val result = CliRuntime.run(
      fixture.goalCommand(),
      fixture.context(
        launcher = launcher,
        liveStdout = { liveStdout.append(it) },
      ),
    )

    assertEquals(1, result.exitCode, result.stdout)
    val output = liveStdout.toString()
    assertEquals(1, output.lines().count { it.startsWith("goal SKILL-901: launched") })
    assertContains(output, "goal watch SKILL-901 --repo-root")
    assertContains(output, "goal status SKILL-901 --repo-root")
    assertFalse(output.contains("goal_event:"), output)
    assertFalse(output.contains("heartbeat"), output)
    assertFalse(output.contains("goal_observability:"), output)
    assertFalse(output.contains("child-1-stdout"), output)
    assertFalse(output.contains("child-1-stderr"), output)
    assertContains(result.stdout, "goal SKILL-901: failed at subtask 2")
    assertEquals(1, result.stdout.lines().count { it.isNotBlank() })
  }
}

/**
 * SKILL-128: the goal per-launch idle-progress timeout default and override. Kept in its own class
 * so it does not push [CliGoalRuntimeTest] over the detekt LargeClass threshold (the file's
 * established convention).
 */
class CliGoalProgressIdleTimeoutTest {
  @Test
  fun `goal progress idle timeout flag passes value to child run`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--progress-idle-timeout-minutes", "25")),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(25.minutes, launcher.childLaunches.single().skillRunRequest.progressIdleTimeout)
  }

  @Test
  fun `goal progress idle timeout defaults to a bounded cap when flag absent`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(extra = emptyList()),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(10.minutes, launcher.childLaunches.single().skillRunRequest.progressIdleTimeout)
  }

  @Test
  fun `goal progress idle timeout zero disables the cap`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      fixture.goalCommand(extra = listOf("--progress-idle-timeout-minutes", "0")),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(null, launcher.childLaunches.single().skillRunRequest.progressIdleTimeout)
  }
}

private fun startRunningRuntimeGoalChild(fixture: GoalCliFixture): String {
  val childWorkflowId = startRunningGoalChild(fixture)
  val component = RuntimeComponent::class.create(
    fixture.context(launcher = NoopGoalTestAgentRunLauncher).toRuntimeContext(),
  )
  val runtimeWorkflow = assertIs<WorkflowOpenResult.Ok>(
    component.workflowService.open(
      kind = WorkflowFamilyKind.TASK_RUNTIME,
      dbOverride = fixture.dbPath.toString(),
    ),
  )
  DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      "UPDATE feature_task_workflows SET artifacts_json = replace(artifacts_json, ?, ?) " +
        "WHERE mode = 'runtime' AND instr(artifacts_json, ?) > 0",
    ).use { statement ->
      statement.setString(1, childWorkflowId)
      statement.setString(2, runtimeWorkflow.workflowId)
      statement.setString(3, childWorkflowId)
      assertTrue(statement.executeUpdate() >= 1)
    }
  }
  return runtimeWorkflow.workflowId
}

private fun seedLiveWorkerLease(fixture: GoalCliFixture, workflowId: String) {
  DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO feature_task_runtime_worker_leases (
        workflow_id, contract_version, generation, owner_token, host_identity, boot_identity,
        pid, process_birth_token, lease_state, heartbeat_at, expires_at, phase_id, phase_attempt
      ) VALUES (?, ?, 1, ?, ?, ?, 1234, ?, 'active', ?, ?, 'implement', 1)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION)
      statement.setString(3, "owner-token-cli-watch")
      statement.setString(4, "test-host")
      statement.setString(5, "test-boot")
      statement.setString(6, "birth-1234")
      statement.setString(7, "2999-01-01T00:00:00Z")
      statement.setString(8, "2999-01-01T00:01:00Z")
      statement.executeUpdate()
    }
  }
}

private fun clearWorkerLease(fixture: GoalCliFixture, workflowId: String) {
  DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      "DELETE FROM feature_task_runtime_worker_leases WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeUpdate()
    }
  }
}

private fun startRunningGoalChild(fixture: GoalCliFixture): String = RuntimeWorkflowTestSupport.continueByIssueKey(
  dbPath = fixture.dbPath,
  issueKey = "SKILL-901",
  subtaskId = 1,
  context = fixture.context(launcher = NoopGoalTestAgentRunLauncher),
)["workflow_id"] as String

private fun recordRunningGoalChildProgress(
  fixture: GoalCliFixture,
  childWorkflowId: String,
  sequence: Int,
  message: String = "editing runtime files",
) {
  runtimeWorkflowUpdate(
    fixture,
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
  )
}

private fun advanceRunningGoalChildToReview(fixture: GoalCliFixture, childWorkflowId: String) {
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = childWorkflowId,
      currentStep = "review",
      stepUpdates = """[{"step_id":"review","status":"running","attempt_count":1}]""",
      artifactsPatch = jsonString(emptyMap<String, Any?>()),
    ),
  )
}

private fun completeRunningGoalChild(fixture: GoalCliFixture, childWorkflowId: String) {
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = childWorkflowId,
      workflowStatus = "completed",
      currentStep = "commit_push",
      stepUpdates = """[{"step_id":"commit_push","status":"completed","attempt_count":1}]""",
      artifactsPatch = jsonString(
        mapOf(
          "commit_push_result" to mapOf("commit_sha" to "sha-1"),
          "goal_continuation_outcome" to mapOf(
            "issue_key" to "SKILL-901",
            "subtask_id" to 1,
            "status" to "complete",
            "workflow_id" to childWorkflowId,
            "commit_sha" to "sha-1",
            "last_resumable_step" to "commit_push",
          ),
        ),
      ),
    ),
  )
}

private fun seedAuthoritativeCompleteChild(fixture: GoalCliFixture) {
  val authoritativeChild = RuntimeWorkflowTestSupport.open(
    fixture.dbPath,
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )["workflow_id"] as String
  runtimeWorkflowUpdate(
    fixture,
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
  )
}

internal data class GoalCliFixture(
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
    requester: HttpRequester = UnconfiguredHttpRequester,
  ): CliRuntimeContext = CliRuntimeContext(
    userHome = tempDir,
    environment = emptyMap(),
    requester = requester,
    workflowGitOperations = workflowGitOperations,
    agentRunLauncher = launcher,
    goalPullRequestPort = pullRequests,
    liveStdout = liveStdout,
    liveStderr = liveStderr,
    // CLI unit tests assert orchestration, not host PATH; production still uses PathExecutableLookup.
    executableLookup = ExecutableLookup { true },
  )

  fun materializeDatabaseWithTelemetry(level: String, requester: HttpRequester) = materializeTelemetryDatabase(
    tempDir,
    dbPath,
    level,
    context(launcher = NoopGoalTestAgentRunLauncher, requester = requester),
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

internal class GoalFixtureAgentRunLauncher(
  private val fixture: GoalCliFixture,
  private val failSubtask: Int? = null,
  private val noTerminalSubtask: Int? = null,
  // Multiple child diagnostic lines keep the default-output contract honest: none
  // may be relayed by the parent, regardless of child output volume.
  private val childDiagnosticChatterCount: Int = 1,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()
  val childLaunches: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    val skillRequest = request.skillRunRequest
    if (skillRequest.goalContinuation == null && skillRequest.promptOverride != null) {
      return planningLaunchOutcome(skillRequest)
    }
    childLaunches += request
    val subtaskId = requireNotNull(skillRequest.subtaskId)
    skillRequest.outputSink.write(AgentRunOutputStream.STDOUT, "child-$subtaskId-stdout\n")
    skillRequest.outputSink.write(AgentRunOutputStream.STDERR, "child-$subtaskId-stderr\n")
    skillRequest.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: workflow progress: subtask $subtaskId " +
        "workflow wftr-$subtaskId step implement durable_progress step=implement\n",
    )
    repeat(childDiagnosticChatterCount) {
      skillRequest.outputSink.write(
        AgentRunOutputStream.STDERR,
        "skill-bill: status heartbeat (90s): child run still active; workflow: " +
          "subtask $subtaskId workflow wftr-$subtaskId step implement durable_progress\n",
      )
    }
    val dbPath = requireNotNull(skillRequest.dbPathOverride)
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

  private fun planningLaunchOutcome(
    skillRequest: skillbill.ports.agentrun.model.SkillRunRequest,
  ): AgentRunLaunchOutcome {
    val phaseId = Regex("""Phase: (\w+) \(""")
      .find(skillRequest.promptOverride.orEmpty())
      ?.groupValues?.get(1)
      ?: "preplan"
    return AgentRunLaunchFacts(
      agent = InstallAgent.CODEX,
      exitStatus = 0,
      stdout = phasePlanningPayload(phaseId),
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  private fun startSubtaskWorkflow(subtaskId: Int, dbPath: String): String {
    val payload = RuntimeWorkflowTestSupport.continueByIssueKey(
      dbPath = Path.of(dbPath),
      issueKey = "SKILL-901",
      subtaskId = subtaskId,
      context = fixture.context(launcher = this),
    )
    return payload["workflow_id"] as String
  }

  private fun completeSubtaskWorkflow(workflowId: String, subtaskId: Int, dbPath: Path) {
    runtimeWorkflowUpdate(
      fixture,
      WorkflowUpdateFixture(
        dbPath = dbPath,
        workflowId = workflowId,
        workflowStatus = "completed",
        currentStep = "commit_push",
        stepUpdates = """[{"step_id":"commit_push","status":"completed","attempt_count":1}]""",
        artifactsPatch = jsonString(mapOf("commit_push_result" to mapOf("commit_sha" to "sha-$subtaskId"))),
      ),
      launcher = this,
    )
  }

  private fun failSubtaskWorkflow(workflowId: String, dbPath: Path) {
    runtimeWorkflowUpdate(
      fixture,
      WorkflowUpdateFixture(
        dbPath = dbPath,
        workflowId = workflowId,
        workflowStatus = "failed",
        currentStep = "review",
        stepUpdates = """[{"step_id":"review","status":"failed","attempt_count":1}]""",
        artifactsPatch = jsonString(mapOf("blocked_reason" to "forced failure")),
      ),
      launcher = this,
    )
  }
}

internal class RecordingGoalPullRequestPort : GoalPullRequestPort {
  val requests: MutableList<GoalPullRequestRequest> = mutableListOf()

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult {
    requests += request
    return GoalPullRequestResult.Opened("https://github.com/example/skill-bill/pull/901")
  }
}

private fun goalControlCommand(fixture: GoalCliFixture, subcommand: String): List<String> = listOf(
  "--db",
  fixture.dbPath.toString(),
  "goal",
  subcommand,
  "SKILL-901",
  "--repo-root",
  fixture.tempDir.toString(),
)

private fun forcePendingPauseRequest(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    val rows = mutableListOf<Pair<String, String>>()
    connection.prepareStatement(
      "SELECT parent_workflow_id, control_state_json FROM goal_runner_controls",
    ).use { statement ->
      statement.executeQuery().use { result ->
        while (result.next()) rows += result.getString(1) to result.getString(2)
      }
    }
    rows.forEach { (parentWorkflowId, json) ->
      val state = JsonSupport.anyToStringAnyMap(
        JsonSupport.jsonElementToValue(requireNotNull(JsonSupport.parseObjectOrNull(json))),
      ).orEmpty().toMutableMap()
      state["paused"] = false
      state["pause_requested"] = true
      state["pause_consumed"] = false
      state["pause_reason"] = "operator_request"
      connection.prepareStatement(
        "UPDATE goal_runner_controls SET control_state_json = ? WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, JsonSupport.mapToJsonString(state))
        statement.setString(2, parentWorkflowId)
        statement.executeUpdate()
      }
    }
  }
}

internal fun goalFixture(subtaskCount: Int): GoalCliFixture {
  val tempDir = Files.createTempDirectory("skillbill-cli-goal")
  val parentSpec = tempDir.resolve(".feature-specs/SKILL-901-goal/spec.md")
  Files.createDirectories(parentSpec.parent)
  Files.writeString(
    parentSpec,
    """
      # Parent

      ## Acceptance Criteria

      1. The decomposed goal completes every governed subtask.
    """.trimIndent(),
  )
  val subtaskSpecs = (1..subtaskCount).map { id ->
    parentSpec.parent.resolve("spec_subtask_${id}_part.md").also { path ->
      Files.writeString(path, subtaskSpecText(id))
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
  val opened = RuntimeWorkflowTestSupport.open(
    fixture.dbPath,
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )
  val workflowId = opened["workflow_id"] as String
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = workflowId,
      currentStep = "plan",
      stepUpdates = """[{"step_id":"plan","status":"completed","attempt_count":1}]""",
      artifactsPatch = parentArtifactsPatch(fixture),
    ),
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

private fun runtimeWorkflowUpdate(
  fixture: GoalCliFixture,
  update: WorkflowUpdateFixture,
  launcher: AgentRunLauncher = NoopGoalTestAgentRunLauncher,
): Map<String, Any?> = RuntimeWorkflowTestSupport.update(
  dbPath = update.dbPath,
  workflowId = update.workflowId,
  workflowStatus = update.workflowStatus,
  currentStepId = update.currentStep,
  stepUpdates = RuntimeWorkflowTestSupport.parseStepUpdates(update.stepUpdates),
  artifactsPatch = RuntimeWorkflowTestSupport.parseArtifactsPatch(update.artifactsPatch),
  context = fixture.context(launcher = launcher),
)

private fun jsonString(value: Any?): String = JsonSupport.json.encodeToString(
  kotlinx.serialization.json.JsonElement.serializer(),
  JsonSupport.valueToJsonElement(value),
)

private fun phasePlanningPayload(phaseId: String): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"$phaseId",""" +
    """"status":"completed","summary":"$phaseId","produced_outputs":""" +
    (planningProjectionOutputs(phaseId) ?: """{"result":"$phaseId"}""") + "}"

// preplan and plan feed the bounded planning projections, so their payloads carry the declared shape.
private fun planningProjectionOutputs(phaseId: String): String? = when (phaseId) {
  "preplan" ->
    """{"projection_kind":"preplanning_digest","contract_version":"0.1","affected_boundaries":["runtime-cli"],""" +
      """"risks":["Fixture risk."],""" +
      """"rollout":{"flag_required":false,"flag_pattern":"none","notes":"No flag needed."},""" +
      """"validation_strategy":["Focused runtime tests."]}"""
  "plan" ->
    """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[{"task_id":"task-1",""" +
      """"description":"Fixture task.","criterion_refs":["AC-001"],""" +
      """"target_paths_or_symbols":["src/Foo.kt"],"test_obligations":["Focused test."]}],""" +
      """"validation_strategy":["Focused runtime tests."]}"""
  else -> null
}

private fun subtaskSpecText(id: Int): String =
  "---\nstatus: Pending\n---\n\n# Subtask $id\n\n## Acceptance Criteria\n\n1. Subtask $id delivers its part.\n"

private object NoopGoalTestAgentRunLauncher : AgentRunLauncher {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome = error("Unexpected launch")
}

private object GoalTestWorkflowGitOperations :
  WorkflowGitOperations,
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

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
    object : GoalSubtaskReviewGitOperations {
      override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult =
        GoalSubtaskReviewBaselineResult(
          status = "ok",
          baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        )

      override fun buildInput(repoRoot: Path, baseline: GoalSubtaskReviewBaseline, expectedBranch: String): Nothing =
        error("Goal review input is not used by this goal CLI fixture.")

      override fun recoverBaseline(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
        status = "error",
        error = "Goal review baseline recovery is not used by this goal CLI fixture.",
      )
    }

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
