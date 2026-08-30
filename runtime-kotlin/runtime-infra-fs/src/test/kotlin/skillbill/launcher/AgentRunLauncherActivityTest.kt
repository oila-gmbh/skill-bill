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

class AgentRunLauncherActivityTest {
  @Test
  fun `declared live long operation survives past former idle window and is classified working`() {
    var sequence = 0
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 0.8"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 3.seconds
  // Idle window far shorter than the run: without the declared long op
        // this would time out. The live declared operation must keep it alive.
        progressIdleTimeout = 100.milliseconds
  operationDeadline = 10.seconds
  declaredProgressProbe = AgentRunDeclaredProgressProbe {
          AgentRunDeclaredProgressSnapshot(
            latestEvent = GoalProgressEvent(
              eventKind = GoalProgressEventKind.OPERATION_STARTED,
              workflowId = "wfl-child",
              workflowPhase = "validate",
              processAlive = true,
              sequenceNumber = sequence,
              timestamp = "2026-06-02T10:0${sequence++}:00Z",
              operationName = "gradlew check",
              expectedLong = true,
            ),
            processAlive = true,
          )
        }
},
    )

    assertFalse(result.timedOut, "declared live long op must not be killed by the idle timeout")
    assertEquals(0, result.exitStatus)
  }

  @Test
  fun `operation observed only via heartbeat anchors its deadline to first observation not process start`() {
    // SKILL-64 Subtask 3 (F-P01): the durable store keeps only the latest
    // declared event, so the supervisor often first ingests an
    // OPERATION_HEARTBEAT (never the OPERATION_STARTED). The operation deadline
    // MUST be measured from when the operation was FIRST OBSERVED, not from
    // process start.
    //
    // This test discriminates the anchor point. The probe withholds every
    // declared snapshot for the first WITHHELD_POLLS supervisor polls, so the
    // first OPERATION_HEARTBEAT is only ingested well past the operation
    // deadline measured from process start. The poll interval is ~250ms, so the
    // first observation lands at ~1.5s — comfortably greater than the 1s
    // operation deadline — while the run only continues for ~0.5s afterwards,
    // comfortably less than that same deadline. Therefore:
    //   - process-start anchoring  => (now - processStart) ~1.5s >= 1s deadline
    //                                  => UNRESPONSIVE => the run is KILLED.
    //   - first-observation anchoring => (now - firstObservation) <= ~0.5s < 1s
    //                                  => WORKING => the run SURVIVES to exit 0.
    // The assertion below therefore FAILS if operationStartedNanos regresses to
    // anchoring at process start and PASSES with first-observation anchoring.
    val withheldPolls = WITHHELD_POLLS
    var probeCalls = 0
    var sequence = 0
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 2"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 5.seconds
  so this test isolates the
        // deadline anchor rather than the idle classifier.
        operationDeadline = 1.seconds
  declaredProgressProbe = AgentRunDeclaredProgressProbe {
          // Withhold the first observation until the elapsed time from process
          // start already exceeds the operation deadline.
          if (probeCalls++ < withheldPolls) {
            null
          } else {
            AgentRunDeclaredProgressSnapshot(
              latestEvent = GoalProgressEvent(
                eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
                workflowId = "wfl-child",
                workflowPhase = "validate",
                processAlive = true,
                sequenceNumber = sequence,
                timestamp = "2026-06-02T10:0${sequence++}:00Z",
                operationName = "gradlew check",
                expectedLong = true,
              ),
              processAlive = true,
            )
          }
        }
},
    )

    assertFalse(
      result.timedOut,
      "heartbeat-only long op must not be killed: its deadline anchors to first observation, " +
        "not process start (liveness=${result.liveness})",
    )
    assertEquals(0, result.exitStatus)
    assertNotEquals(
      GoalRunnerLivenessState.UNRESPONSIVE,
      result.liveness?.livenessState,
      "process-start anchoring would have produced an operation_deadline_overrun kill",
    )
  }

  @Test
  fun `declared dead process produces a deterministic unresponsive kill`() {
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 5"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 5.seconds
  progressIdleTimeout = 5.seconds
  operationDeadline = 50.milliseconds
  declaredProgressProbe = AgentRunDeclaredProgressProbe {
          AgentRunDeclaredProgressSnapshot(
            latestEvent = GoalProgressEvent(
              eventKind = GoalProgressEventKind.OPERATION_STARTED,
              workflowId = "wfl-child",
              workflowPhase = "implement",
              processAlive = true,
              sequenceNumber = 1,
              timestamp = "2026-06-02T10:00:00Z",
              operationName = "gradlew check",
              expectedLong = true,
            ),
            processAlive = true,
          )
        }
},
    )

    assertTrue(result.timedOut, "operation deadline overrun must produce a deterministic kill")
    assertEquals(GoalRunnerLivenessState.UNRESPONSIVE, result.liveness?.livenessState)
  }

  @Test
  fun `process lifecycle drives declared operation events without phase agent self-report`() {
    // SKILL-64 Subtask 3 (AC25, AC21, AC22, AC23): the process-lifecycle wrapper
    // emits operation_started on launch, gated operation_heartbeat ticks while
    // alive, and operation_completed on exit — with the authoritative
    // process-alive signal and a stable long-op identity — without the child
    // self-reporting anything.
    val emissions = mutableListOf<AgentRunProgressEmission>()
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 0.35"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 3.seconds
  statusHeartbeatInterval = 100.milliseconds
  progressEmitter = AgentRunProgressEmitter { emissions += it }
},
    )

    assertEquals(0, result.exitStatus)
    val started = emissions.first()
    assertEquals(GoalProgressEventKind.OPERATION_STARTED, started.eventKind)
    assertEquals("child_agent_run", started.operationName)
    assertEquals("long_child_run", started.operationKind)
    assertTrue(started.expectedLong)
    assertTrue(started.processAlive)
    assertTrue(
      emissions.any { it.eventKind == GoalProgressEventKind.OPERATION_HEARTBEAT },
      "expected at least one gated operation_heartbeat",
    )
    val completed = emissions.last()
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, completed.eventKind)
    assertEquals(GoalProgressOutcome.SUCCEEDED, completed.outcome)
    assertFalse(completed.processAlive)
    assertEquals(1, emissions.count { it.eventKind == GoalProgressEventKind.OPERATION_STARTED })
    assertEquals(1, emissions.count { it.eventKind == GoalProgressEventKind.OPERATION_COMPLETED })
  }

  @Test
  fun `process lifecycle emits timed-out completion when wall clock cap elapses`() {
    val emissions = mutableListOf<AgentRunProgressEmission>()
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 5"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 300.milliseconds
  progressEmitter = AgentRunProgressEmitter { emissions += it }
},
    )

    assertTrue(result.timedOut)
    val completed = emissions.last()
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, completed.eventKind)
    assertEquals(GoalProgressOutcome.TIMED_OUT, completed.outcome)
    assertFalse(completed.processAlive)
  }

  @Test
  fun `process lifecycle emits cancelled completion when parent thread is interrupted`() {
    val emissions = Collections.synchronizedList(mutableListOf<AgentRunProgressEmission>())
    val runner = JvmAgentRunProcessRunner()
    val worker = thread(start = true) {
      runner.run(
        testAgentRunProcessRequest(
  listOf("sh", "-c", "sleep 30"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 30.seconds
  progressEmitter = AgentRunProgressEmitter { emissions += it }
},
      )
    }

    Thread.sleep(150)
    worker.interrupt()
    worker.join(5_000)

    assertFalse(worker.isAlive)
    val completed = emissions.last()
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, completed.eventKind)
    assertEquals(GoalProgressOutcome.CANCELLED, completed.outcome)
  }

  @Test
  fun `a faulty emitter never fails the run`() {
    val result = JvmAgentRunProcessRunner().run(
      testAgentRunProcessRequest(
  listOf("sh", "-c", "printf done"),
  Path.of(".").toAbsolutePath().normalize(),
) {
  timeout = 3.seconds
  progressEmitter = AgentRunProgressEmitter { error("emitter boom") }
},
    )

    assertEquals(0, result.exitStatus)
    assertEquals("done", result.stdout)
  }

  @Test
  fun `launch facts expose provider-neutral child session path and id for codex and other builders`() {
    // SKILL-64 Subtask 3 (AC6, AC11, AC14): every supported headless builder's
    // adapter exposes a provider-neutral child session path (working dir) and a
    // deterministic, non-secret session id, with no provider-private token log
    // consulted.
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)
    // Every supported headless builder's adapter exposes a provider-neutral child session path.
    listOf(InstallAgent.CODEX, InstallAgent.CLAUDE, InstallAgent.JUNIE, InstallAgent.CURSOR).forEach { agent ->
      val facts = requireNotNull(adapters[agent]).launch(skillRunRequest())
      assertEquals("/tmp/skillbill-agent-run", facts.childSessionPath, "session path for $agent")
      val sessionId = requireNotNull(facts.childSessionId) { "session id for $agent" }
      assertContains(sessionId, agent.id)
      assertContains(sessionId, "SKILL-56")
      assertContains(sessionId, "subtask-2")
    }
  }

  @Test
  fun `worktree activity probe tracks meaningful file changes and ignores build outputs`() {
    val root = Files.createTempDirectory("skillbill-worktree-activity")
    val probe = WorktreeActivityProbe(root, scanIntervalNanos = 0)
    val initial = probe.activityToken()

    Files.writeString(root.resolve("source.kt"), "source")
    val sourceChanged = probe.activityToken()

    Files.createDirectories(root.resolve("build"))
    Files.writeString(root.resolve("build/generated.txt"), "generated")
    val buildChanged = probe.activityToken()

    assertNotEquals(initial, sourceChanged)
    assertEquals(sourceChanged, buildChanged)
  }

}

