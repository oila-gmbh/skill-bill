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

class CliGoalRuntimeControlTest {
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
    assertContains(result.stdout, "repair")
    assertContains(result.stdout, "--debug-child-output")
    assertContains(result.stdout, "raw child streams hidden")

    val preflightHelp = CliRuntime.run(listOf("goal", "preflight", "--help"), CliRuntimeContext())
    assertEquals(0, preflightHelp.exitCode, preflightHelp.stdout)
    assertContains(preflightHelp.stdout, "read-only goal verdict")
    assertContains(preflightHelp.stdout, "--format")
    assertContains(preflightHelp.stdout, "--agent-addon")
  }

  @Test
  fun `goal preflight emits one json verdict without launching a child`() {
    val fixture = goalFixture(subtaskCount = 2, seedWorkflow = false)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "preflight",
        "SKILL-901",
        "--agent",
        "codex",
        "--repo-root",
        fixture.tempDir.toString(),
        "--format",
        "json",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = requireNotNull(
      JsonSupport.anyToStringAnyMap(
        JsonSupport.jsonElementToValue(requireNotNull(JsonSupport.parseObjectOrNull(result.stdout))),
      ),
    ) { "Expected preflight JSON object but got: ${result.stdout}" }
    assertEquals("new_work", payload["verdict"])
    assertEquals("SKILL-901", payload["issue_key"])
    assertEquals(true, payload["manifest_missing"])
    assertTrue(payload.containsKey("gate_block"))
    assertTrue(payload.containsKey("rehydrate_targets"))
    assertTrue(launcher.requests.isEmpty())
    assertTrue(launcher.childLaunches.isEmpty())
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

    assertEquals(2, result.exitCode, result.stdout)
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

}

