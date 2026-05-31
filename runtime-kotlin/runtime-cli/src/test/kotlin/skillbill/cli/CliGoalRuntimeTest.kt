package skillbill.cli

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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class CliGoalRuntimeTest {
  @Test
  fun `goal command is registered with status subcommand`() {
    val result = CliRuntime.run(listOf("goal", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "Run a decomposed goal in the foreground.")
    assertContains(result.stdout, "status")
    assertContains(result.stdout, "reset")
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
    assertContains(liveStdout.toString(), "version=0.1.0 build_id=0.1.0")
    assertContains(liveStdout.toString(), "child-1-stdout")
    assertContains(liveStdout.toString(), "goal SKILL-901: completion confirmed")
    assertContains(liveStderr.toString(), "child-1-stderr")
    assertEquals(listOf(null, null), launcher.requests.map { it.skillRunRequest.timeout })
    assertEquals(1, fixture.pullRequests.requests.size)
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
    assertContains(liveStdout.toString(), "goal SKILL-901: runtime executable=")
    assertContains(liveStdout.toString(), "version=0.1.0 build_id=0.1.0")
    assertEquals(false, liveStdout.toString().contains("child-1-stdout"), liveStdout.toString())
    assertEquals(false, liveStderr.toString().contains("child-1-stderr"), liveStderr.toString())
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
    assertContains(liveStdout.toString(), "version=0.1.0 build_id=0.1.0")
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
    assertEquals("blocked", staleWorkflow["workflow_status"])
    assertContains(staleWorkflow["artifacts"]?.toString().orEmpty(), "stale running child")
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
  ): CliRuntimeContext = CliRuntimeContext(
    userHome = tempDir,
    workflowGitOperations = GoalTestWorkflowGitOperations,
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
    request.skillRunRequest.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: status heartbeat (90s): child run still active; workflow: " +
        "subtask $subtaskId workflow wfl-$subtaskId step implement durable_progress\n",
    )
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

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-commit")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")
}
