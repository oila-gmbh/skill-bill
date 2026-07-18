package skillbill.launcher.process

import org.junit.jupiter.api.Assertions.assertEquals
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
