package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherTest {
  @Test
  fun `claude adapter builds a fresh inherited-config process command`() {
    val runner = RecordingAgentRunProcessRunner()
    val outcome = requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CLAUDE]).launch(skillRunRequest())

    assertEquals(InstallAgent.CLAUDE, outcome.agent)
    val request = runner.requests.single()
    assertEquals("claude", request.command[0])
    assertContains(request.command, "--print")
    assertContains(request.command, "--dangerously-skip-permissions")
    assertContains(request.command, "--add-dir")
    assertContains(request.command.last(), "bill-feature-implement")
    assertContains(
      request.command.last(),
      "skill-bill --db /tmp/skillbill-agent-run/metrics.db workflow continue SKILL-56 --subtask-id 2 --format json",
    )
    assertTrue(request.inheritEnvironment)
    assertEquals(emptyMap(), request.environment)
  }

  @Test
  fun `codex adapter builds a non-interactive command with inherited shell environment`() {
    val runner = RecordingAgentRunProcessRunner()
    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CODEX]).launch(skillRunRequest())

    val request = runner.requests.single()
    assertEquals(listOf("codex", "exec"), request.command.take(2))
    assertContains(request.command, "--cd")
    assertContains(request.command, "--dangerously-bypass-approvals-and-sandbox")
    assertFalse("--ask-for-approval" in request.command)
    assertContains(request.command, "shell_environment_policy.inherit=all")
    assertFalse(request.command.any { value -> "First execute this exact command" in value })
    assertContains(requireNotNull(request.stdinText), "First execute this exact command")
    assertContains(requireNotNull(request.stdinText), "Return exactly the `RESULT:` block")
    assertContains(
      requireNotNull(request.stdinText),
      "Never call `skill-bill workflow update` just to mark blocked.",
    )
    assertTrue(request.inheritEnvironment)
  }

  @Test
  fun `opencode adapter builds a headless run command without disabling user config`() {
    val runner = RecordingAgentRunProcessRunner()
    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.OPENCODE]).launch(skillRunRequest())

    val request = runner.requests.single()
    assertEquals(listOf("opencode", "run"), request.command.take(2))
    assertContains(request.command, "--dir")
    assertContains(request.command, "--dangerously-skip-permissions")
    assertFalse("--pure" in request.command)
    assertContains(request.command.last(), "Goal-continuation: enabled.")
  }

  @Test
  fun `junie adapter builds a current headless command`() {
    val runner = RecordingAgentRunProcessRunner()
    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.JUNIE]).launch(skillRunRequest())

    val request = runner.requests.single()
    assertEquals("junie", request.command[0])
    assertContains(request.command, "--project")
    assertContains(request.command, "/tmp/skillbill-agent-run")
    assertContains(request.command, "--output-format")
    assertContains(request.command, "text")
    assertContains(request.command, "--skip-update-check")
    assertContains(request.command, "--timeout")
    assertContains(request.command, "3000")
    assertContains(request.command.last(), "First execute this exact command")
    assertContains(
      request.command.last(),
      "skill-bill --db /tmp/skillbill-agent-run/metrics.db workflow continue SKILL-56 --subtask-id 2 --format json",
    )
    assertTrue(request.inheritEnvironment)
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

    adapter.launch(skillRunRequest(issueKey = "SKILL-56"))
    adapter.launch(skillRunRequest(issueKey = "SKILL-57"))

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

  private fun skillRunRequest(issueKey: String = "SKILL-56"): SkillRunRequest = SkillRunRequest(
    issueKey = issueKey,
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 2,
    dbPathOverride = "/tmp/skillbill-agent-run/metrics.db",
    timeout = 3.seconds,
  )
}

private class RecordingAgentRunProcessRunner(
  private val result: AgentRunProcessResult = AgentRunProcessResult(
    exitStatus = 0,
    stdout = "ok",
    stderr = "",
    timedOut = false,
    spawnFailed = false,
  ),
) : AgentRunProcessRunner {
  val requests: MutableList<AgentRunProcessRequest> = mutableListOf()

  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    requests += request
    return result
  }
}
