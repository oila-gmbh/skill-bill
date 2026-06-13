package skillbill.launcher

import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.launcher.agentrun.WorktreeActivityProbe
import skillbill.launcher.agentrun.headlessAgentRunAdapters
import skillbill.launcher.process.AgentRunActivityProbe
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
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherTest {
  // A phase-briefing prompt override still drives the per-agent CLI directly (not the
  // goal-continuation skill-bill command), with delivery mechanics unchanged per agent.
  @Test
  fun `a phase-briefing prompt override drives the per-agent CLI for stdin-delivered agents`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = skillRunRequest(goalContinuation = null).copy(promptOverride = PHASE_PROMPT)

    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CLAUDE]).launch(request)

    val captured = runner.requests.single()
    assertEquals("claude", captured.command[0])
    assertEquals(PHASE_PROMPT, captured.stdinText)
    // Delivery mechanics are unchanged: stdin for claude, never a trailing argv token.
    assertEquals("--add-dir", captured.command[captured.command.size - 2])
  }

  @Test
  fun `a phase-briefing prompt override drives the per-agent CLI for argv-delivered agents`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = skillRunRequest(goalContinuation = null).copy(promptOverride = PHASE_PROMPT)

    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.OPENCODE]).launch(request)

    val captured = runner.requests.single()
    assertEquals(listOf("opencode", "run"), captured.command.take(2))
    assertEquals(PHASE_PROMPT, captured.command.last())
  }

  @Test
  fun `supported agent without headless path returns unsupported outcome`() {
    val launcher = FileSystemAgentRunLauncher(JvmAgentRunProcessRunner())

    val outcome = launcher.launch(
      AgentRunLaunchRequest(
        agentId = "copilot",
        skillRunRequest = skillRunRequest(),
      ),
    )

    assertIs<UnsupportedAgentRunLaunch>(outcome)
    assertEquals(InstallAgent.COPILOT, outcome.agent)
    assertContains(outcome.reason, "does not have a supported headless")
  }

  @Test
  fun `timeout and spawn-failure paths stay launch-level facts`() {
    val timeoutRunner = RecordingAgentRunProcessRunner(
      result = AgentRunProcessResult(
        exitStatus = null,
        stdout = "partial",
        stderr = "slow",
        timedOut = true,
        interrupted = false,
        spawnFailed = false,
      ),
    )
    val timeout = requireNotNull(
      headlessAgentRunAdapters(timeoutRunner)[InstallAgent.CODEX],
    ).launch(skillRunRequest())
    assertTrue(timeout.timedOut)
    assertFalse(timeout.spawnFailed)
    assertEquals(null, timeout.exitStatus)

    val spawnRunner = RecordingAgentRunProcessRunner(
      result = AgentRunProcessResult(
        exitStatus = null,
        stdout = "",
        stderr = "missing executable",
        timedOut = false,
        interrupted = false,
        spawnFailed = true,
      ),
    )
    val spawnFailure = requireNotNull(
      headlessAgentRunAdapters(spawnRunner)[InstallAgent.CODEX],
    ).launch(skillRunRequest())
    assertFalse(spawnFailure.timedOut)
    assertTrue(spawnFailure.spawnFailed)
    assertEquals("missing executable", spawnFailure.stderr)
  }

  @Test
  fun `adapter invokes process runner once per launch`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapter = requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CODEX])

    adapter.launch(
      skillRunRequest(issueKey = "SKILL-56", goalContinuation = null)
        .copy(promptOverride = "$PHASE_PROMPT\nIssue key: SKILL-56"),
    )
    adapter.launch(
      skillRunRequest(issueKey = "SKILL-57", goalContinuation = null)
        .copy(promptOverride = "$PHASE_PROMPT\nIssue key: SKILL-57"),
    )

    assertEquals(2, runner.requests.size)
    assertContains(requireNotNull(runner.requests[0].stdinText), "SKILL-56")
    assertContains(requireNotNull(runner.requests[1].stdinText), "SKILL-57")
  }

  @Test
  fun `jvm process runner tees live output while preserving captured output`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "printf stdout-line; printf stderr-line >&2"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        outputSink = { stream, text -> events += stream to text },
      ),
    )

    assertEquals(0, result.exitStatus)
    assertEquals("stdout-line", result.stdout)
    assertEquals("stderr-line", result.stderr)
    assertTrue(
      events.any { it.first == AgentRunOutputStream.STDOUT && it.second.contains("stdout-line") },
    )
    assertTrue(
      events.any { it.first == AgentRunOutputStream.STDERR && it.second.contains("stderr-line") },
    )
  }

  @Test
  fun `jvm process runner closes child stdin for non-interactive runs`() {
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "if read line; then printf got; else printf eof; fi"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
      ),
    )

    assertEquals(0, result.exitStatus)
    assertEquals("eof", result.stdout)
  }

  @Test
  fun `jvm process runner writes configured stdin text before closing child stdin`() {
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "cat"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        stdinText = "prompt over stdin",
      ),
    )

    assertEquals(0, result.exitStatus)
    assertEquals("prompt over stdin", result.stdout)
  }

  @Test
  fun `install scripts refuse process-level execution during goal continuation`() {
    val repoRoot = repoRoot()
    val fixtureRoot = Files.createTempDirectory("skillbill-goal-continuation-install-guard")

    listOf("install.sh", "uninstall.sh").forEach { scriptName ->
      val script = fixtureRoot.resolve(scriptName)
      Files.copy(repoRoot.resolve(scriptName), script)

      val result = JvmAgentRunProcessRunner().run(
        AgentRunProcessRequest(
          command = listOf(bashExecutable().toString(), script.toString()),
          workingDirectory = fixtureRoot,
          timeout = 3.seconds,
          environment = mapOf(
            "HOME" to fixtureRoot.resolve("home").toString(),
            "SKILL_BILL_GOAL_CONTINUATION" to "1",
          ),
          inheritEnvironment = false,
        ),
      )

      assertEquals(64, result.exitStatus, "$scriptName stdout=${result.stdout} stderr=${result.stderr}")
      assertContains(result.stdout, "Refusing to run $scriptName during skill-bill goal-continuation.")
      assertFalse(result.stdout.contains("Applying install through the runtime plan/apply path."))
      assertFalse(result.stdout.contains("Uninstall complete"))
    }
  }

  @Test
  fun `jvm process runner stops a live process after workflow progress stays idle without wall clock cap`() {
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        progressIdleTimeout = 100.milliseconds,
        progressProbe = AgentRunProgressProbe { null },
      ),
    )

    assertTrue(result.timedOut)
    assertEquals(null, result.exitStatus)
    assertContains(result.stderr, "without durable workflow progress")
    assertContains(result.stderr, "No file activity was observed")
  }

  @Test
  fun `jvm process runner reports durable workflow progress labels`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    var probeCount = 0
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.4"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        progressProbe = object : AgentRunProgressProbe {
          override fun progressToken(): String = "token-${probeCount++}"
          override fun progressLabel(): String = "subtask 7 workflow wfl-child step implement"
        },
        outputSink = { stream, text -> events += stream to text },
      ),
    )

    assertEquals(0, result.exitStatus)
    assertTrue(
      events.any { event ->
        event.first == AgentRunOutputStream.STDERR &&
          "skill-bill: workflow progress: subtask 7 workflow wfl-child step implement" in event.second
      },
    )
  }

  @Test
  fun `jvm process runner treats file activity as idle liveness`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    var activityProbeCount = 0
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.8"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        progressIdleTimeout = 500.milliseconds,
        fileActivityGraceTimeout = 2.seconds,
        progressProbe = AgentRunProgressProbe { "workflow-token" },
        activityProbe = object : AgentRunActivityProbe {
          override fun activityToken(): String = if (activityProbeCount++ < 2) "files-before" else "files-after"
          override fun activityLabel(): String = "worktree files changed"
        },
        outputSink = { stream, text -> events += stream to text },
      ),
    )

    assertEquals(0, result.exitStatus)
    assertFalse(result.timedOut)
    assertTrue(
      events.any { event ->
        event.first == AgentRunOutputStream.STDERR &&
          "skill-bill: file activity observed; durable workflow progress is still pending" in event.second
      },
    )
  }

  @Test
  fun `jvm process runner emits periodic status heartbeat during long active runs`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.35"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        statusHeartbeatInterval = 100.milliseconds,
        progressProbe = object : AgentRunProgressProbe {
          override fun progressToken(): String = "workflow-token"
          override fun progressLabel(): String = "subtask 4 workflow wfl-child step preplan"
        },
        outputSink = { stream, text -> events += stream to text },
      ),
    )

    assertEquals(0, result.exitStatus)
    assertFalse(result.timedOut)
    assertTrue(
      events.any { event ->
        event.first == AgentRunOutputStream.STDERR &&
          "skill-bill: status heartbeat (100ms): child run still active;" in event.second &&
          "workflow: subtask 4 workflow wfl-child step preplan" in event.second
      },
    )
  }

  @Test
  fun `jvm process runner stops after bounded file activity grace without durable workflow progress`() {
    var activityProbeCount = 0
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        progressIdleTimeout = 100.milliseconds,
        fileActivityGraceTimeout = 300.milliseconds,
        progressProbe = AgentRunProgressProbe { "workflow-token" },
        activityProbe = object : AgentRunActivityProbe {
          override fun activityToken(): String = "files-${activityProbeCount++}"
          override fun activityLabel(): String = "worktree files changed"
        },
      ),
    )

    assertTrue(result.timedOut)
    assertContains(result.stderr, "without durable workflow progress")
    assertContains(result.stderr, "file-activity grace window was exhausted")
  }

  @Test
  fun `jvm process runner kills child when parent thread is interrupted`() {
    val runner = JvmAgentRunProcessRunner()
    var result: AgentRunProcessResult? = null
    val worker = thread(start = true) {
      result = runner.run(
        AgentRunProcessRequest(
          command = listOf("sh", "-c", "sleep 30"),
          workingDirectory = Path.of(".").toAbsolutePath().normalize(),
          timeout = 30.seconds,
        ),
      )
    }

    Thread.sleep(150)
    worker.interrupt()
    worker.join(5_000)

    assertFalse(worker.isAlive)
    val completed = assertNotNull(result)
    assertFalse(completed.timedOut)
    assertTrue(completed.interrupted)
    assertContains(completed.stderr, "interrupted by parent signal")
    assertEquals("parent_interrupted", completed.liveness?.reason)
    assertEquals("killed", completed.liveness?.processState)
  }

  @Test
  fun `declared live long operation survives past former idle window and is classified working`() {
    var sequence = 0
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.8"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        // Idle window far shorter than the run: without the declared long op
        // this would time out. The live declared operation must keep it alive.
        progressIdleTimeout = 100.milliseconds,
        operationDeadline = 10.seconds,
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
        },
      ),
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
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 2"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        // Disarm the idle window: a heartbeat-only WORKING long operation must be
        // governed purely by the operation deadline, so this test isolates the
        // deadline anchor rather than the idle classifier.
        operationDeadline = 1.seconds,
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
        },
      ),
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
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        progressIdleTimeout = 5.seconds,
        operationDeadline = 50.milliseconds,
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
        },
      ),
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
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.35"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        statusHeartbeatInterval = 100.milliseconds,
        progressEmitter = AgentRunProgressEmitter { emissions += it },
      ),
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
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 300.milliseconds,
        progressEmitter = AgentRunProgressEmitter { emissions += it },
      ),
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
        AgentRunProcessRequest(
          command = listOf("sh", "-c", "sleep 30"),
          workingDirectory = Path.of(".").toAbsolutePath().normalize(),
          timeout = 30.seconds,
          progressEmitter = AgentRunProgressEmitter { emissions += it },
        ),
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
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "printf done"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        progressEmitter = AgentRunProgressEmitter { error("emitter boom") },
      ),
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
    val adapters = headlessAgentRunAdapters(runner)
    listOf(InstallAgent.CODEX, InstallAgent.CLAUDE, InstallAgent.OPENCODE, InstallAgent.JUNIE).forEach { agent ->
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

  private companion object {
    const val PHASE_PROMPT = "Phase: plan\nTask: produce an ordered plan.\nRequired final output: one raw JSON object."
  }

  private fun skillRunRequest(
    issueKey: String = "SKILL-56",
    goalContinuation: SkillRunGoalContinuationContext? = goalContinuationContext(),
  ): SkillRunRequest = SkillRunRequest(
    issueKey = issueKey,
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 2,
    dbPathOverride = "/tmp/skillbill-agent-run/metrics.db",
    timeout = 3.seconds,
    goalContinuation = goalContinuation,
  )

  private fun goalContinuationContext(childWorkflowId: String? = null): SkillRunGoalContinuationContext =
    SkillRunGoalContinuationContext(
      parentIssueKey = "SKILL-56",
      subtaskId = 2,
      goalBranch = "feat/SKILL-56-goal",
      suppressPr = true,
      specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
      parentWorkflowId = "wfl-parent",
      lastResumableStep = "implement",
      childWorkflowId = childWorkflowId,
    )

  private fun repoRoot(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      if (Files.isRegularFile(current.resolve("install.sh")) && Files.isDirectory(current.resolve("runtime-kotlin"))) {
        return current
      }
      current = current.parent
    }
    error("Could not locate repository root from ${Path.of("").toAbsolutePath().normalize()}")
  }

  private fun bashExecutable(): Path =
    listOf(Path.of("/usr/bin/bash"), Path.of("/bin/bash")).firstOrNull(Files::isExecutable)
      ?: error("Could not locate bash executable")
}

// SKILL-64 Subtask 3 (F-NT01): end-to-end coverage that the SUPERVISOR PROCESS
// LOOP drives operation_started/heartbeat/completed into a durable store AND that
// the SAME run reads them back to exercise the DeclaredProgressTracker
// hasDeclaredEvent==true branch — joining the emit side and the read/classify
// side inside ONE JvmAgentRunProcessRunner.run via the real ProcessWaitLoop.
class SupervisorProcessLoopEndToEndTest {
  @Test
  fun `supervisor-emitted declared events feed the declared-progress tracker within one run`() {
    // A single shared store receives the supervisor-emitted operation_* events and
    // is read back by the SAME run's declaredProgressProbe, so
    // DeclaredProgressTracker observes a real declared event and hasDeclaredEvent
    // becomes true.
    //
    // Discrimination: the idle window (100ms) is far shorter than the run (~0.8s)
    // and the legacy progressProbe NEVER moves, so if the emitter's output did NOT
    // reach the tracker the run would fall to the legacy idle branch and be KILLED
    // (timedOut). It survives only because the declared events seed an
    // expected-long WORKING operation that disarms the idle timeout. Verified to
    // FAIL when the emitter->store->probe link is severed (see notes_for_review).
    val store = SharedDeclaredProgressStore()
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.8"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 3.seconds,
        progressIdleTimeout = 100.milliseconds,
        operationDeadline = 10.seconds,
        statusHeartbeatInterval = 100.milliseconds,
        // Legacy hint never advances: only the declared stream can keep this alive.
        progressProbe = AgentRunProgressProbe { null },
        progressEmitter = AgentRunProgressEmitter { store.record(it) },
        declaredProgressProbe = AgentRunDeclaredProgressProbe { store.snapshot() },
      ),
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
    // The surviving run reports the declared long-op identity it was classified
    // by, proving the declared (not legacy) branch governed liveness.
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

/**
 * SKILL-64 Subtask 3 (F-NT01): a single durable-store stand-in that BOTH receives
 * supervisor-emitted declared events ([record]) AND serves them back to the same
 * run's declared-progress probe ([snapshot]). This mirrors the production seam
 * where [GoalRunnerProgressEventEmitter] persists via recordProgressEvent and the
 * supervisor reads the latest declared event back through progress(), joining the
 * emit and read/classify sides inside one run. Sequence numbers are minted here
 * (the effect-free emission carries none) so the tracker's monotonic dedup sees a
 * strictly advancing stream.
 */
private class SharedDeclaredProgressStore {
  val recorded: MutableList<AgentRunProgressEmission> = Collections.synchronizedList(mutableListOf())
  private var sequence = 0
  private var latest: GoalProgressEvent? = null

  @Synchronized
  fun record(emission: AgentRunProgressEmission) {
    recorded += emission
    latest = GoalProgressEvent(
      eventKind = emission.eventKind,
      workflowId = "wfl-child",
      workflowPhase = "goal_runner_supervision",
      processAlive = emission.processAlive,
      sequenceNumber = sequence++,
      timestamp = "2026-06-02T10:00:00Z",
      operationName = emission.operationName,
      operationKind = emission.operationKind,
      expectedLong = emission.expectedLong,
      outcome = emission.outcome,
    )
  }

  @Synchronized
  fun snapshot(): AgentRunDeclaredProgressSnapshot? = latest?.let { event ->
    AgentRunDeclaredProgressSnapshot(latestEvent = event, processAlive = event.processAlive)
  }
}

// The supervisor polls declared progress every ~250ms (PROGRESS_POLL_INTERVAL_MILLIS).
// Withholding the first observation for 6 polls delays it to ~1.5s after process
// start — past the 1s operation deadline measured from process start, but leaving
// only ~0.5s of run, well under that deadline measured from first observation.
private const val WITHHELD_POLLS = 6
