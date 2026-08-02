package skillbill.launcher.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class JvmAgentRunProcessRunnerTest {
  @Test
  fun `foreground process result is returned once as the bounded terminal result`() {
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "printf terminal-result"),
        workingDirectory = Path.of("."),
      ),
    )

    assertEquals(0, result.exitStatus)
    assertEquals("terminal-result", result.stdout)
    assertEquals(false, result.timedOut)
    assertEquals(false, result.interrupted)
    assertEquals(false, result.spawnFailed)
    assertEquals(true, result.processStarted)
  }

  @Test
  fun `MCP startup is counted only when an explicit launcher probe observes it`() {
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "printf terminal-result"),
        workingDirectory = Path.of("."),
        mcpStartupProbe = AgentRunMcpStartupProbe { true },
      ),
    )

    assertTrue(result.mcpStartupObserved)
  }

  @Test
  fun `spawn authorization surrounds process creation and not terminal waiting`() {
    var authorizationEntered = false
    var authorizationExited = false
    val result = JvmAgentRunProcessRunner().run(
      AgentRunProcessRequest(
        command = listOf("sh", "-c", "printf terminal-result"),
        workingDirectory = Path.of("."),
        spawnAuthorization = object : AgentRunSpawnAuthorization {
          override fun <T> withAuthorization(spawn: () -> T): T {
            authorizationEntered = true
            return spawn().also { authorizationExited = true }
          }
        },
      ),
    )

    assertEquals(0, result.exitStatus)
    assertEquals("terminal-result", result.stdout)
    assertEquals(true, authorizationEntered)
    assertEquals(true, authorizationExited)
  }

  @Test
  fun `reap destroys a process that exits and does not forcibly kill it`() {
    val process = FakeReapableProcess(staysAlive = false)

    JvmAgentRunProcessRunner.reapLiveProcesses(listOf(process))

    assertEquals(1, process.destroyCount)
    assertEquals(0, process.forcibleCount)
  }

  @Test
  fun `reap forcibly kills a process that stays alive after destroy`() {
    val process = FakeReapableProcess(staysAlive = true)

    JvmAgentRunProcessRunner.reapLiveProcesses(listOf(process))

    assertEquals(1, process.destroyCount)
    assertEquals(1, process.forcibleCount)
  }

  /**
   * SKILL-141: a delegated review lane launches with inheritEnvironment=false. Clearing the whole
   * environment left the worker with no PATH to exec from and no home under which its registered
   * native agents live, so preflight reported every review worker as uninstalled.
   */
  @Test
  fun `isolated launch keeps the agent locatable and its user installation resolvable`() {
    val parent = mapOf(
      "HOME" to "/home/dev",
      "PATH" to "/usr/bin",
      "CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work",
      "XDG_CONFIG_HOME" to "/home/dev/.config",
      "ANTHROPIC_SESSION_SECRET" to "ambient",
      "SOME_CALLER_STATE" to "ambient",
    )

    val isolated = isolatedLaunchEnvironment(parent, mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1"))

    assertEquals("/home/dev", isolated["HOME"])
    assertEquals("/usr/bin", isolated["PATH"])
    assertEquals("/home/dev/.claude-work", isolated["CLAUDE_CONFIG_DIR"])
    assertEquals("/home/dev/.config", isolated["XDG_CONFIG_HOME"])
    assertEquals("1", isolated["SKILL_BILL_GOAL_CONTINUATION"])
    assertNull(isolated["ANTHROPIC_SESSION_SECRET"])
    assertNull(isolated["SOME_CALLER_STATE"])
  }

  @Test
  fun `isolated launch overrides win over inherited passthrough values`() {
    val isolated = isolatedLaunchEnvironment(
      mapOf("HOME" to "/home/dev", "PATH" to "/usr/bin"),
      mapOf("HOME" to "/tmp/sandbox-home"),
    )

    assertEquals("/tmp/sandbox-home", isolated["HOME"])
    assertEquals("/usr/bin", isolated["PATH"])
  }

  @Test
  fun `isolated launch passes through additional keys declared by the command builder`() {
    val parent = mapOf(
      "HOME" to "/home/dev",
      "PATH" to "/usr/bin",
      "ANTHROPIC_API_KEY" to "sk-ant-ambient",
      "SOME_AMBIENT_SECRET" to "should-be-stripped",
    )

    val isolated = isolatedLaunchEnvironment(
      parent,
      overrides = mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1"),
      additionalPassthroughKeys = setOf("ANTHROPIC_API_KEY"),
    )

    assertEquals("sk-ant-ambient", isolated["ANTHROPIC_API_KEY"])
    assertEquals("/home/dev", isolated["HOME"])
    assertNull(isolated["SOME_AMBIENT_SECRET"])
  }

  /**
   * SKILL-141: configureLaunchEnvironment must apply isolation against the ProcessBuilder's own
   * environment map — the actual seam that broke when the block was untested. This test exercises
   * the ProcessBuilder seam directly rather than the pure-helper overload, so reverting the .apply
   * block would cause this test to fail while keeping the helper tests green.
   */
  @Test
  fun `configureLaunchEnvironment applies isolation to a real ProcessBuilder environment map`() {
    val builder = ProcessBuilder("echo", "test")
    builder.environment().clear()
    builder.environment()["HOME"] = "/home/dev"
    builder.environment()["PATH"] = "/usr/bin"
    builder.environment()["ANTHROPIC_API_KEY"] = "sk-ant-ambient"
    builder.environment()["SOME_AMBIENT_SECRET"] = "should-be-stripped"

    configureLaunchEnvironment(
      builder,
      AgentRunProcessRequest(
        command = listOf("echo"),
        workingDirectory = Path.of("."),
        environment = mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1"),
        inheritEnvironment = false,
        environmentPassthroughKeys = setOf("ANTHROPIC_API_KEY"),
      ),
    )

    assertEquals("/home/dev", builder.environment()["HOME"])
    assertEquals("/usr/bin", builder.environment()["PATH"])
    assertEquals("sk-ant-ambient", builder.environment()["ANTHROPIC_API_KEY"])
    assertEquals("1", builder.environment()["SKILL_BILL_GOAL_CONTINUATION"])
    assertNull(builder.environment()["SOME_AMBIENT_SECRET"])
  }
}

private class FakeReapableProcess(private val staysAlive: Boolean) : Process() {
  var destroyCount = 0
    private set
  var forcibleCount = 0
    private set

  override fun getOutputStream() = error("unused")
  override fun getInputStream() = error("unused")
  override fun getErrorStream() = error("unused")
  override fun waitFor(): Int = error("unused")
  override fun exitValue(): Int = error("unused")
  override fun destroy() {
    destroyCount++
  }
  override fun destroyForcibly(): Process {
    forcibleCount++
    return this
  }
  override fun isAlive(): Boolean = staysAlive
  override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !staysAlive
}
