package skillbill.launcher.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class JvmAgentRunProcessRunnerTest {
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
