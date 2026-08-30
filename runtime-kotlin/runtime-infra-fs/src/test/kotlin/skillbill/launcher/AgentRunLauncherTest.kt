package skillbill.launcher

import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.launcher.agentrun.ProcessAgentRunAdapter
import skillbill.launcher.agentrun.WorktreeActivityProbe
import skillbill.launcher.agentrun.headlessAgentRunAdapters
import skillbill.launcher.process.AgentRunActivityProbe
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.launcher.process.AgentRunProcessRequest
import skillbill.launcher.process.AgentRunProcessResult
import skillbill.launcher.process.AgentRunProcessRunner
import skillbill.launcher.process.JvmAgentRunProcessRunner
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SupervisorProcessLoopEndToEndTest {
  @Test
  fun `supervisor-emitted declared events feed the declared-progress tracker within one run`() {
    val store = SharedDeclaredProgressStore()
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 0.8"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 3.seconds
  progressIdleTimeout = 100.milliseconds
  operationDeadline = 10.seconds
  statusHeartbeatInterval = 100.milliseconds
        progressProbe = AgentRunProgressProbe { null }
  progressEmitter = AgentRunProgressEmitter { store.record(it) }
  declaredProgressProbe = AgentRunDeclaredProgressProbe { store.snapshot() }
},
    )

    assertFalse(
      result.timedOut,
      "declared events emitted by the supervisor loop must feed the tracker in the same run; " +
        "without that link the legacy idle timeout would have killed the run (liveness=${result.liveness})",
    )
    assertEquals(0, result.exitStatus)
    assertTrue(store.recorded.isNotEmpty(), "supervisor must have emitted at least one declared event")
    assertTrue(
      store.recorded.any { it.eventKind == GoalProgressEventKind.OPERATION_STARTED },
      "the supervisor process loop must drive operation_started",
    )
    assertTrue(
      store.recorded.any { it.eventKind == GoalProgressEventKind.OPERATION_HEARTBEAT },
      "the supervisor process loop must drive operation_heartbeat",
    )
    assertTrue(
      store.recorded.any { it.eventKind == GoalProgressEventKind.OPERATION_COMPLETED },
      "the supervisor process loop must drive operation_completed",
    )
    assertEquals("child_agent_run", result.liveness?.activeOperationName)
  }
}

internal class RecordingAgentRunProcessRunner(
  private val result: AgentRunProcessResult = AgentRunProcessResult(
    exitStatus = 0,
    stdout = "ok",
    stderr = "",
    timedOut = false,
    interrupted = false,
    spawnFailed = false,
  ),
) : AgentRunProcessRunner {
  val requests: MutableList<AgentRunProcessRequest> = mutableListOf()

  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    requests += request
    return result
  }
}

class HeadlessAgentRunAdapterTest {
  private fun phaseRunRequest(): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-88",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 1,
    timeout = 10.seconds,
    goalContinuation = null,
  ).copy(promptOverride = "Phase: preplan")

  private fun skillRunRequest(): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-88",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 1,
    timeout = 3.seconds,
    goalContinuation = null,
    promptOverride = "Test prompt",
    streamOutputForLiveness = true,
  )

  @Test
  fun `headless runtime adapters cover exactly the runtime-capable install agents`() {
    val adapters = headlessAgentRunAdapters(RecordingAgentRunProcessRunner())

    assertEquals(
      setOf(InstallAgent.CLAUDE, InstallAgent.CODEX, InstallAgent.JUNIE, InstallAgent.CURSOR),
      adapters.keys,
    )
  }

  @Test
  fun `process adapter carries codex fork turns none to the worker start request`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = phaseRunRequest().copy(conversationIsolation = ConversationIsolation.NONE)
    val builder = CodexAgentRunCommandBuilder()

    ProcessAgentRunAdapter(InstallAgent.CODEX, builder, runner, ALL_EXECUTABLES_AVAILABLE).launch(request)

    assertEquals(ConversationIsolation.NONE, runner.requests.single().conversationIsolation)
    assertEquals("none", runner.requests.single().conversationIsolation?.forkTurns)
  }

  @Test
  fun `cursor is registered as a headless adapter with correct builder and decoder`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)

    val cursorAdapter = adapters[InstallAgent.CURSOR]
    assertNotNull(cursorAdapter, "cursor must be registered as a headless adapter")
    assertTrue(cursorAdapter is ProcessAgentRunAdapter, "cursor adapter must be ProcessAgentRunAdapter")

    val request = phaseRunRequest()
    cursorAdapter.launch(request)

    val captured = runner.requests.single()
    assertEquals("agent", captured.command.first())
    assertTrue(captured.command.contains("--workspace"))
  }

  @Test
  fun `cursor launch is not refused and succeeds`() {
    val launcher = FileSystemAgentRunLauncher(JvmAgentRunProcessRunner(), ALL_EXECUTABLES_AVAILABLE)

    val outcome = launcher.launch(
      AgentRunLaunchRequest(
        agentId = "cursor",
        skillRunRequest = skillRunRequest(),
      ),
    )

    assertFalse(outcome is UnsupportedAgentRunLaunch, "cursor launch must not be refused")
  }

  @Test
  fun `cursor process launch honors timeout expiry with streamed output`() {
    val runner = RecordingAgentRunProcessRunner(
      result = AgentRunProcessResult(
        exitStatus = null,
        stdout = "partial output",
        stderr = "slow",
        timedOut = true,
        interrupted = false,
        spawnFailed = false,
      ),
    )
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)
    val request = skillRunRequest().copy(
      promptOverride = "Run timeout test",
      timeout = 100.milliseconds,
    )

    requireNotNull(adapters[InstallAgent.CURSOR]).launch(request)

    val captured = runner.requests.single()
    assertEquals(100.milliseconds, captured.timeout)
    assertTrue(captured.command.contains("stream-json"))
  }

  @Test
  fun `cursor process launch honors interruption`() {
    val runner = RecordingAgentRunProcessRunner(
      result = AgentRunProcessResult(
        exitStatus = null,
        stdout = "partial",
        stderr = "interrupted",
        timedOut = false,
        interrupted = true,
        spawnFailed = false,
      ),
    )
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)

    val outcome = requireNotNull(adapters[InstallAgent.CURSOR]).launch(skillRunRequest())

    assertTrue(outcome.interrupted)
    assertFalse(outcome.timedOut)
    assertEquals("interrupted", outcome.stderr)
  }

  @Test
  fun `cursor durable-progress policies remain in force with streamed output`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)
    val request = skillRunRequest().copy(
      promptOverride = "Test progress policies",
      streamOutputForLiveness = true,
    )

    requireNotNull(adapters[InstallAgent.CURSOR]).launch(request)

    val captured = runner.requests.single()
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, captured.idlePolicy)
  }
}

class ReadOnlyPhaseLivenessTest {
  @Test
  fun `read-only phase alive process is not idle-killed when it emits no durable progress`() {
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 0.4"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 5.seconds
  progressIdleTimeout = 100.milliseconds
  progressProbe = AgentRunProgressProbe { null }
  idlePolicy = AgentRunIdlePolicy.HEARTBEAT_EXTENDED
},
    )

    assertFalse(
      result.timedOut,
      "a live read-only phase process must not be idle-killed; liveness=${result.liveness}",
    )
    assertEquals(0, result.exitStatus)
  }

  @Test
  fun `a process producing no durable progress and no heartbeat extension is killed by the idle timeout`() {
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 5"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 10.seconds
  progressIdleTimeout = 100.milliseconds
  progressProbe = AgentRunProgressProbe { null }
  idlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY
},
    )

    assertTrue(
      result.timedOut,
      "a process with no durable progress must be killed under DB_PROGRESS_ONLY",
    )
  }
}
