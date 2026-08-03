package skillbill.launcher

import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.install.model.InstallAgent
import skillbill.install.model.RUNTIME_REFUSED_AGENTS
import skillbill.launcher.agentrun.AgentRunCommand
import skillbill.launcher.agentrun.AgentRunCommandBuilder
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
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.NativeReviewWorkerRequest
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass", "LongMethod", "CyclomaticComplexMethod")
class AgentRunLauncherTest {
  @Test
  fun `supported native reviews keep provider startup and authoritative progress explicit`() {
    listOf("claude", "codex", "cursor").forEach { agentId ->
      val runner = RecordingAgentRunProcessRunner()
      val launcher = FileSystemAgentRunLauncher(runner)
      val broker = Proxy.newProxyInstance(
        ReviewEvidenceBroker::class.java.classLoader,
        arrayOf(ReviewEvidenceBroker::class.java),
      ) { _, method, _ ->
        when (method.returnType) {
          java.lang.Boolean.TYPE -> false
          java.lang.Integer.TYPE -> 0
          java.lang.Long.TYPE -> 0L
          else -> null
        }
      } as ReviewEvidenceBroker

      val outcome = launcher.launchNativeReview(
        NativeReviewWorkerRequest(
          agentId = agentId,
          logicalWorkerName = null,
          issueKey = "SKILL-146",
          repoRoot = Files.createTempDirectory("native-review-source"),
          timeout = 1.seconds,
          prompt = """{"kind":"launch"}""",
          modelOverride = null,
          isolation = if (agentId == "codex") {
            skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE
          } else {
            skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.FRESH_PROCESS
          },
          broker = broker,
          operations = BrokerBackedNativeReviewOperationProtocol(broker),
        ),
      )

      assertFalse(outcome is UnsupportedAgentRunLaunch)
      assertEquals(1, runner.requests.size)
      assertNotNull(runner.requests.single().nativeReviewOperations)
      assertFalse(runner.requests.single().command.any { it.startsWith("agent=") || it == "--agent" })
      val captured = runner.requests.single()
      assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, captured.idlePolicy)
      assertEquals(1.seconds, captured.operationDeadline)
      if (agentId == "claude") {
        assertContains(captured.command, "stream-json")
        assertFalse(captured.command.contains("json"))
      }
      if (agentId == "cursor") {
        assertContains(captured.command, "--stream-partial-output")
      }
      val providerCallbacks = assertNotNull(captured.nativeReviewLifecycleCallbacks)
      val operations = assertNotNull(captured.nativeReviewOperations)
      providerCallbacks.observeProviderOutput(
        operations,
        """{"type":"assistant","mcp_servers":["skill-bill"],"message":{"text":"ordinary output"}}""",
        captured.progressEmitter,
      )
      assertFalse(captured.mcpStartupProbe.startupObserved())
      captured.progressEmitter.emit(
        AgentRunProgressEmission(
          eventKind = GoalProgressEventKind.OPERATION_STARTED,
          processAlive = true,
          operationName = "child_agent_run",
          operationKind = "long_child_run",
        ),
      )
      assertNull(captured.progressProbe.progressToken())
      assertNull(captured.declaredProgressProbe.latestDeclaredProgress())
      captured.progressEmitter.emit(
        AgentRunProgressEmission(
          eventKind = GoalProgressEventKind.OPERATION_STARTED,
          processAlive = true,
          operationName = "delegated-review",
          operationKind = "review",
          authoritative = true,
        ),
      )
      assertNotNull(captured.progressProbe.progressToken())
      assertNotNull(captured.declaredProgressProbe.latestDeclaredProgress())
      val providerLifecycleEvents = when (agentId) {
        "claude" -> listOf(
          """{"type":"system","subtype":"init","session_id":"s-1"}""",
          """{"type":"assistant","message":{"model":"claude-opus-4-8"}}""",
          """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}""",
          """{"type":"result","subtype":"success","result":"finding"}""",
        )
        "codex" -> listOf(
          """{"type":"thread.started","thread_id":"t-1"}""",
          """{"type":"item.started","item":{"type":"agent_message"}}""",
          """{"type":"item.completed","item":{"type":"agent_message","text":"finding"}}""",
          """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        else -> listOf(
          """{"type":"partial","delta":"finding"}""",
          """{"type":"result","result":"finding"}""",
        )
      }
      providerLifecycleEvents.forEachIndexed { index, event ->
        providerCallbacks.observeProviderOutput(operations, "$event\n", captured.progressEmitter)
        if (agentId == "codex" && index == 1) {
          assertTrue(
            captured.declaredProgressProbe.latestDeclaredProgress()?.latestEvent?.expectedLong == true,
            "Codex turn lifecycle must opt into long-operation liveness",
          )
        }
        if (agentId != "codex" && index == 0) {
          assertFalse(
            captured.declaredProgressProbe.latestDeclaredProgress()?.latestEvent?.expectedLong == true,
            "$agentId must retain the unchanged non-long provider classification",
          )
        }
      }
      assertEquals(
        GoalProgressEventKind.OPERATION_COMPLETED,
        captured.declaredProgressProbe.latestDeclaredProgress()?.latestEvent?.eventKind,
      )
      providerCallbacks.observeProviderOutput(
        operations,
        """{"type":"mcp_startup","server":"skill-bill"}""",
        captured.progressEmitter,
      )
      assertTrue(captured.mcpStartupProbe.startupObserved())
    }
  }

  @Test
  fun `provider startup envelope does not suppress the progress idle watchdog`() {
    val broker = Proxy.newProxyInstance(
      ReviewEvidenceBroker::class.java.classLoader,
      arrayOf(ReviewEvidenceBroker::class.java),
    ) { _, method, _ ->
      when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
      }
    } as ReviewEvidenceBroker
    val runner = object : AgentRunProcessRunner {
      override fun run(request: AgentRunProcessRequest): AgentRunProcessResult = JvmAgentRunProcessRunner().run(
        request.copy(
          command = listOf(
            "sh",
            "-c",
            """printf '%s\n' '{"type":"thread.started"}'; while :; do :; done""",
          ),
        ),
      )
    }

    val outcome = FileSystemAgentRunLauncher(runner).launchNativeReview(
      NativeReviewWorkerRequest(
        agentId = "codex",
        logicalWorkerName = "bill-code-review-architecture",
        issueKey = "SKILL-145",
        repoRoot = Files.createTempDirectory("native-review-stall-source"),
        timeout = 5.seconds,
        progressIdleTimeout = 100.milliseconds,
        prompt = """{"kind":"stall"}""",
        modelOverride = null,
        isolation = skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE,
        broker = broker,
        operations = BrokerBackedNativeReviewOperationProtocol(broker),
      ),
    )

    val facts = assertIs<AgentRunLaunchFacts>(outcome)
    assertTrue(facts.timedOut, "a provider that only emits startup must be idle-killed")
    assertEquals("progress_idle_timeout", facts.liveness?.reason)
    assertEquals(GoalRunnerLivenessState.IDLE, facts.liveness?.livenessState)
    assertFalse(facts.liveness?.activeOperationExpectedLong ?: true)
  }

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

    // SKILL-95: opencode is no longer a runtime adapter; junie is the other argv-delivered agent
    // (the prompt rides as a trailing argv token, never via stdin).
    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.JUNIE]).launch(request)

    val captured = runner.requests.single()
    assertEquals("junie", captured.command.first())
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
  fun `opencode returns the unsupported headless launch outcome with the actionable prose message`() {
    val launcher = FileSystemAgentRunLauncher(JvmAgentRunProcessRunner())

    val outcome = launcher.launch(
      AgentRunLaunchRequest(
        agentId = "opencode",
        skillRunRequest = skillRunRequest(),
      ),
    )

    assertIs<UnsupportedAgentRunLaunch>(outcome)
    assertEquals(InstallAgent.OPENCODE, outcome.agent)
    assertContains(outcome.reason, "Runtime mode is not supported on opencode")
    assertContains(outcome.reason, "bill-feature-task-prose")
  }

  @Test
  fun `zcode returns the unsupported headless launch outcome with the actionable prose message`() {
    val launcher = FileSystemAgentRunLauncher(JvmAgentRunProcessRunner())

    val outcome = launcher.launch(
      AgentRunLaunchRequest(
        agentId = "zcode",
        skillRunRequest = skillRunRequest(),
      ),
    )

    assertIs<UnsupportedAgentRunLaunch>(outcome)
    assertEquals(InstallAgent.ZCODE, outcome.agent)
    assertContains(outcome.reason, "Runtime mode is not supported on opencode or zcode")
    assertContains(outcome.reason, "zcode's foreground runtime exceeds the Bash execution ceiling")
    assertContains(outcome.reason, "bill-feature-task-prose")
    assertContains(outcome.reason, "bill-feature-goal mode:prose")
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
  fun `plain launch facts retain decoded body bytes`() {
    val rawBytes = byteArrayOf(0, 13, 10, -1, 42)
    val runner = RecordingAgentRunProcessRunner(
      result = AgentRunProcessResult(
        exitStatus = 0,
        stdout = "\u0000\r\n�*",
        stdoutBytes = rawBytes,
        stderr = "",
        timedOut = false,
        interrupted = false,
        spawnFailed = false,
      ),
    )

    val facts = requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CODEX]).launch(skillRunRequest())

    assertContentEquals(rawBytes, facts.stdoutBytes)
  }

  @Test
  fun `structured provider launch facts expose the decoded response body bytes not envelope bytes`() {
    val envelope = """{"type":"result","result":"{\"status\":\"blocked\"}","usage":{}}"""
    val runner = RecordingAgentRunProcessRunner(
      result = AgentRunProcessResult(
        exitStatus = 0,
        stdout = envelope,
        stderr = "",
        timedOut = false,
        interrupted = false,
        spawnFailed = false,
      ),
    )

    val facts = requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CLAUDE]).launch(skillRunRequest())

    assertEquals("""{"status":"blocked"}""", facts.stdout)
    assertContentEquals(facts.stdout.encodeToByteArray(), facts.stdoutBytes)
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
        outputSink = { stream, text -> synchronized(events) { events += stream to text } },
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
  fun `incremental output alone keeps a db-silent process alive past the idle window`() {
    val emitting = listOf("sh", "-c", "i=0; while [ \$i -lt 8 ]; do echo tick; sleep 0.15; i=\$((i+1)); done")

    val streamed = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = emitting,
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        progressIdleTimeout = 600.milliseconds,
        progressProbe = AgentRunProgressProbe { null },
        idlePolicy = AgentRunIdlePolicy.OUTPUT_EXTENDED,
      ),
    )

    assertFalse(streamed.timedOut, "output arriving inside the idle window must extend it")
    assertEquals(0, streamed.exitStatus)

    val unstreamed = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = emitting,
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        progressIdleTimeout = 600.milliseconds,
        progressProbe = AgentRunProgressProbe { null },
        idlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
      ),
    )

    assertTrue(unstreamed.timedOut, "the same output must not rescue a db-progress-only launch")
  }

  @Test
  fun `a silent process still dies at the idle deadline under output-extended liveness`() {
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        progressIdleTimeout = 300.milliseconds,
        progressProbe = AgentRunProgressProbe { null },
        idlePolicy = AgentRunIdlePolicy.OUTPUT_EXTENDED,
      ),
    )

    assertTrue(result.timedOut)
    assertEquals(null, result.exitStatus)
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
  fun `process wrapper heartbeats cannot keep a delegated worker past progress idle`() {
    val providerProgress = SharedDeclaredProgressStore()
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        progressIdleTimeout = 100.milliseconds,
        declaredProgressProbe = providerProgress::snapshot,
        progressEmitter = AgentRunProgressEmitter { emission ->
          if (emission.authoritative) providerProgress.record(emission)
        },
      ),
    )

    assertTrue(result.timedOut)
    assertEquals("progress_idle_timeout", result.liveness?.reason)
    assertTrue(
      providerProgress.recorded.isEmpty(),
      "Only provider-owned lifecycle envelopes may arm declared progress.",
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
    // SKILL-95: opencode is prose-only and excluded from the headless runtime adapters.
    listOf(InstallAgent.CODEX, InstallAgent.CLAUDE, InstallAgent.JUNIE).forEach { agent ->
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
  fun `opencode is not registered as a headless runtime adapter`() {
    // SKILL-95 AC5 / SKILL-103 AC6: opencode and zcode are prose-only. Neither may appear in the
    // headless adapter registry, so no code path can spawn either for a runtime phase even if a CLI
    // guard is bypassed.
    val adapters = headlessAgentRunAdapters(RecordingAgentRunProcessRunner())

    // Every runtime-refused agent is absent (the AC), while the known runtime agents stay registered.
    // Asserting a subset rather than exact-set equality keeps this robust to unrelated future agents.
    RUNTIME_REFUSED_AGENTS.forEach { refused -> assertFalse(adapters.keys.contains(refused)) }
    assertFalse(adapters.keys.contains(InstallAgent.OPENCODE), "opencode must not be a headless adapter")
    assertFalse(adapters.keys.contains(InstallAgent.ZCODE), "zcode must not be a headless adapter")
    val expectedAgents = setOf(InstallAgent.CLAUDE, InstallAgent.CODEX, InstallAgent.JUNIE, InstallAgent.CURSOR)
    assertTrue(adapters.keys.containsAll(expectedAgents))
  }

  @Test
  fun `process adapter threads usePtyStdio from the built command rather than a constant`() {
    // After opencode (the only PTY-backed builder) was removed, no real builder sets usePtyStdio=true,
    // so prove threading directly: a builder requesting PTY stdio must surface usePtyStdio=true in the
    // process request. Guards against the flag being hardcoded to false.
    val runner = RecordingAgentRunProcessRunner()
    val ptyBuilder = object : AgentRunCommandBuilder {
      override val agent: InstallAgent = InstallAgent.CLAUDE
      override fun build(request: SkillRunRequest): AgentRunCommand = AgentRunCommand(
        command = listOf("true"),
        workingDirectory = request.repoRoot,
        timeout = request.timeout,
        usePtyStdio = true,
      )
    }

    ProcessAgentRunAdapter(InstallAgent.CLAUDE, ptyBuilder, runner).launch(phaseRunRequest())

    assertEquals(1, runner.requests.size)
    assertTrue(runner.requests.single().usePtyStdio, "adapter must thread usePtyStdio=true from the builder")
  }

  @Test
  fun `process adapter carries codex fork turns none to the worker start request`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = phaseRunRequest().copy(conversationIsolation = ConversationIsolation.NONE)
    val builder = CodexAgentRunCommandBuilder()

    ProcessAgentRunAdapter(InstallAgent.CODEX, builder, runner).launch(request)

    assertEquals(ConversationIsolation.NONE, runner.requests.single().conversationIsolation)
    assertEquals("none", runner.requests.single().conversationIsolation?.forkTurns)
  }

  @Test
  fun `claude codex and junie builders emit usePtyStdio=false`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = phaseRunRequest()
    val adapters = headlessAgentRunAdapters(runner)

    listOf(InstallAgent.CLAUDE, InstallAgent.CODEX, InstallAgent.JUNIE).forEach { agent ->
      requireNotNull(adapters[agent]).launch(request)
    }

    val otherRequests = runner.requests
    assertTrue(otherRequests.size == 3)
    otherRequests.forEach { req ->
      assertFalse(req.usePtyStdio, "non-opencode agent must not request PTY-backed stdio")
    }
  }

  @Test
  fun `process adapter threads usePtyStdio=false into the process request for supported agents`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = phaseRunRequest()
    val adapters = headlessAgentRunAdapters(runner)

    requireNotNull(adapters[InstallAgent.CLAUDE]).launch(request)
    requireNotNull(adapters[InstallAgent.CODEX]).launch(request)

    runner.requests.forEach { req ->
      assertEquals(false, req.usePtyStdio, "supported adapter must thread usePtyStdio=false")
    }
  }

  @Test
  fun `cursor is registered as a headless adapter with correct builder and decoder`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner)

    val cursorAdapter = adapters[InstallAgent.CURSOR]
    assertNotNull(cursorAdapter, "cursor must be registered as a headless adapter")
    assertTrue(cursorAdapter is ProcessAgentRunAdapter, "cursor adapter must be ProcessAgentRunAdapter")

    val request = phaseRunRequest()
    cursorAdapter.launch(request)

    val captured = runner.requests.single()
    assertEquals("agent", captured.command.first())
    assertTrue(captured.command.contains("--workspace"))
    assertEquals(false, captured.usePtyStdio)
  }

  @Test
  fun `cursor launch is not refused and succeeds`() {
    val launcher = FileSystemAgentRunLauncher(JvmAgentRunProcessRunner())

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
    val adapters = headlessAgentRunAdapters(runner)
    val request = skillRunRequest().copy(
      promptOverride = "Run timeout test",
      timeout = 100.milliseconds,
    )

    requireNotNull(adapters[InstallAgent.CURSOR]).launch(request)

    val captured = runner.requests.single()
    assertEquals(100.milliseconds, captured.timeout)
    assertTrue(captured.command.contains("--stream-partial-output"))
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
    val adapters = headlessAgentRunAdapters(runner)

    val outcome = requireNotNull(adapters[InstallAgent.CURSOR]).launch(skillRunRequest())

    assertTrue(outcome.interrupted)
    assertFalse(outcome.timedOut)
    assertEquals("interrupted", outcome.stderr)
  }

  @Test
  fun `cursor durable-progress policies remain in force with streamed output`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner)
    val request = skillRunRequest().copy(
      promptOverride = "Test progress policies",
      streamOutputForLiveness = true,
    )

    requireNotNull(adapters[InstallAgent.CURSOR]).launch(request)

    val captured = runner.requests.single()
    assertEquals(false, captured.usePtyStdio, "cursor must use separate stdout/stderr, not PTY")
  }
}

class PtyStdioLaunchTest {
  private fun assumePtyAvailable() {
    // PTY-backed stdio is a Linux-only production feature (JvmAgentRunProcessRunner
    // hard-guards `os.name startsWith "linux"`). macOS ships /dev/ptmx too, so gate
    // on the OS — not just the device — or these tests run into that guard and the
    // spawn fails with a null exit status on non-Linux CI hosts.
    org.junit.jupiter.api.Assumptions.assumeTrue(
      System.getProperty("os.name").lowercase().startsWith("linux"),
      "PTY-backed stdio is only supported on Linux; skipping on ${System.getProperty("os.name")}",
    )
    org.junit.jupiter.api.Assumptions.assumeTrue(
      java.io.File("/dev/ptmx").exists(),
      "PTY device /dev/ptmx not available; skipping PTY integration test",
    )
  }

  @Test
  fun `jvm process runner spawns child over pty and captures stdout via outputSink`() {
    assumePtyAvailable()
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "printf hello-pty"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        usePtyStdio = true,
        outputSink = { stream, text -> synchronized(events) { events += stream to text } },
      ),
    )

    assertEquals(0, result.exitStatus)
    assertContains(result.stdout, "hello-pty")
    assertTrue(
      events.any { it.first == AgentRunOutputStream.STDOUT && "hello-pty" in it.second },
      "outputSink must receive PTY stdout chunk",
    )
    assertFalse(result.timedOut)
    assertFalse(result.spawnFailed)
  }

  @Test
  fun `jvm process runner pty path does not false-kill a live child under idle watchdog`() {
    assumePtyAvailable()
    val sequence = java.util.concurrent.atomic.AtomicInteger(0)
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        progressIdleTimeout = 100.milliseconds,
        operationDeadline = 10.seconds,
        usePtyStdio = true,
        declaredProgressProbe = AgentRunDeclaredProgressProbe {
          val seq = sequence.getAndIncrement()
          AgentRunDeclaredProgressSnapshot(
            latestEvent = GoalProgressEvent(
              eventKind = GoalProgressEventKind.OPERATION_STARTED,
              workflowId = "wfl-child",
              workflowPhase = "preplan",
              processAlive = true,
              sequenceNumber = seq,
              timestamp = "2026-06-22T10:0$seq:00Z",
              operationName = "opencode-preplan",
              expectedLong = true,
            ),
            processAlive = true,
          )
        },
      ),
    )

    assertFalse(result.timedOut, "PTY-backed live child must not be false-killed by idle watchdog")
    assertEquals(0, result.exitStatus)
  }

  @Test
  fun `jvm process runner pty path captures multiline stdout and forwards to outputSink`() {
    assumePtyAvailable()
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "printf 'line1\nline2\nline3'"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        usePtyStdio = true,
        outputSink = { stream, text -> synchronized(events) { events += stream to text } },
      ),
    )

    assertEquals(0, result.exitStatus)
    assertContains(result.stdout, "line1")
    assertContains(result.stdout, "line2")
    assertContains(result.stdout, "line3")
    assertTrue(
      events.any { it.first == AgentRunOutputStream.STDOUT && ("line1" in it.second || "line2" in it.second) },
      "PTY outputSink must receive STDOUT stream events containing expected output lines",
    )
  }
}

/**
 * SKILL-142 Subtask 1 (Task 4): confirms that a read-only phase (HEARTBEAT_EXTENDED policy) is not
 * idle-killed while alive, and that a non-live process is still killed by the idle timeout under
 * DB_PROGRESS_ONLY — proving the policy selection matters and is not a no-op.
 */
class ReadOnlyPhaseLivenessTest {
  @Test
  fun `read-only phase alive process is not idle-killed when it emits no durable progress`() {
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 0.4"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 5.seconds,
        progressIdleTimeout = 100.milliseconds,
        progressProbe = AgentRunProgressProbe { null },
        idlePolicy = AgentRunIdlePolicy.HEARTBEAT_EXTENDED,
      ),
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
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "sleep 5"),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        timeout = 10.seconds,
        progressIdleTimeout = 100.milliseconds,
        progressProbe = AgentRunProgressProbe { null },
        idlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
      ),
    )

    assertTrue(
      result.timedOut,
      "a process with no durable progress must be killed under DB_PROGRESS_ONLY",
    )
  }
}
