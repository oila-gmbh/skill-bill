package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
      "skill-bill --db /tmp/skillbill-agent-run/metrics.db workflow continue SKILL-56 --subtask-id 2",
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
    assertContains(request.command, "--sandbox")
    assertContains(request.command, "danger-full-access")
    assertContains(request.command, "--ask-for-approval")
    assertContains(request.command, "never")
    assertContains(request.command, "shell_environment_policy.inherit=\"all\"")
    assertContains(request.command.last(), "Return exactly the `RESULT:` block")
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
    assertContains(runner.requests[0].command.last(), "SKILL-56")
    assertContains(runner.requests[1].command.last(), "SKILL-57")
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
    assertTrue(events.any { it.first == AgentRunOutputStream.STDOUT && it.second == "stdout-line" })
    assertTrue(events.any { it.first == AgentRunOutputStream.STDERR && it.second == "stderr-line" })
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
