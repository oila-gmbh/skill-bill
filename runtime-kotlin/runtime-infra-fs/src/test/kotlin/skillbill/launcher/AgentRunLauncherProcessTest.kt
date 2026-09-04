package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.launcher.agentrun.headlessAgentRunAdapters
import skillbill.launcher.process.AgentRunProcessResult
import skillbill.launcher.process.JvmAgentRunProcessRunner
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.time.JvmSystemClock
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherProcessTest {
  @Test
  fun `a phase-briefing prompt override drives the per-agent CLI for stdin-delivered agents`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = skillRunRequest(goalContinuation = null).copy(promptOverride = AGENT_RUN_LAUNCHER_PHASE_PROMPT)

    requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[InstallAgent.CLAUDE]).launch(request)

    val captured = runner.requests.single()
    assertEquals("claude", captured.command[0])
    assertEquals(AGENT_RUN_LAUNCHER_PHASE_PROMPT, captured.stdinText)
    // Delivery mechanics are unchanged: stdin for claude, never a trailing argv token.
    assertEquals("--add-dir", captured.command[captured.command.size - 2])
  }

  @Test
  fun `a phase-briefing prompt override drives the per-agent CLI for argv-delivered agents`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = skillRunRequest(goalContinuation = null).copy(promptOverride = AGENT_RUN_LAUNCHER_PHASE_PROMPT)

    // Junie is the argv-delivered agent (the prompt rides as a trailing argv token, never via stdin).
    requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[InstallAgent.JUNIE]).launch(request)

    val captured = runner.requests.single()
    assertEquals("junie", captured.command.first())
    assertEquals(AGENT_RUN_LAUNCHER_PHASE_PROMPT, captured.command.last())
  }

  @Test
  fun `unknown agent id fails before launch`() {
    val launcher = FileSystemAgentRunLauncher(JvmAgentRunProcessRunner(JvmSystemClock), ALL_EXECUTABLES_AVAILABLE)

    assertFailsWith<IllegalArgumentException> {
      launcher.launch(
        AgentRunLaunchRequest(
          agentId = "not-an-agent",
          skillRunRequest = skillRunRequest(),
        ),
      )
    }
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
      headlessAgentRunAdapters(timeoutRunner, ALL_EXECUTABLES_AVAILABLE)[InstallAgent.CODEX],
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
      headlessAgentRunAdapters(spawnRunner, ALL_EXECUTABLES_AVAILABLE)[InstallAgent.CODEX],
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

    val facts = requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[InstallAgent.CODEX])
      .launch(skillRunRequest())

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

    val facts = requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[InstallAgent.CLAUDE])
      .launch(skillRunRequest())

    assertEquals("""{"status":"blocked"}""", facts.stdout)
    assertContentEquals(facts.stdout.encodeToByteArray(), facts.stdoutBytes)
  }

  @Test
  fun `adapter invokes process runner once per launch`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapter = requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[InstallAgent.CODEX])

    adapter.launch(
      skillRunRequest(issueKey = "SKILL-56", goalContinuation = null)
        .copy(promptOverride = "$AGENT_RUN_LAUNCHER_PHASE_PROMPT\nIssue key: SKILL-56"),
    )
    adapter.launch(
      skillRunRequest(issueKey = "SKILL-57", goalContinuation = null)
        .copy(promptOverride = "$AGENT_RUN_LAUNCHER_PHASE_PROMPT\nIssue key: SKILL-57"),
    )

    assertEquals(2, runner.requests.size)
    assertContains(requireNotNull(runner.requests[0].stdinText), "SKILL-56")
    assertContains(requireNotNull(runner.requests[1].stdinText), "SKILL-57")
  }

  @Test
  fun `jvm process runner tees live output while preserving captured output`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    val result = JvmAgentRunProcessRunner(JvmSystemClock).run(
      testAgentRunProcessRequest(
        listOf("sh", "-c", "printf stdout-line; printf stderr-line >&2"),
        Path.of(".").toAbsolutePath().normalize(),
      ) {
        timeout = 3.seconds
        outputSink = { stream, text -> synchronized(events) { events += stream to text } }
      },
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
    val result = JvmAgentRunProcessRunner(JvmSystemClock).run(
      testAgentRunProcessRequest(
        listOf("sh", "-c", "if read line; then printf got; else printf eof; fi"),
        Path.of(".").toAbsolutePath().normalize(),
      ) {
        timeout = 3.seconds
      },
    )

    assertEquals(0, result.exitStatus)
    assertEquals("eof", result.stdout)
  }

  @Test
  fun `jvm process runner writes configured stdin text before closing child stdin`() {
    val result = JvmAgentRunProcessRunner(JvmSystemClock).run(
      testAgentRunProcessRequest(
        listOf("sh", "-c", "cat"),
        Path.of(".").toAbsolutePath().normalize(),
      ) {
        timeout = 3.seconds
        stdinText = "prompt over stdin"
      },
    )

    assertEquals(0, result.exitStatus)
    assertEquals("prompt over stdin", result.stdout)
  }
}
