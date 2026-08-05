package skillbill.install.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentLauncherCliTest {
  private val nothingInstalled: (String) -> Boolean = { false }
  private val everythingInstalled: (String) -> Boolean = { true }

  @Test
  fun `every runtime-capable agent declares a launcher CLI`() {
    val runtimeAgents = InstallAgent.entries.filterNot(RUNTIME_REFUSED_AGENTS::contains) - InstallAgent.COPILOT

    assertEquals(runtimeAgents.toSet(), AGENT_LAUNCHER_CLIS.keys)
  }

  @Test
  fun `an agent whose CLI is absent yields an actionable reason`() {
    val reason = assertNotNull(unavailableAgentLauncherReason("cursor", nothingInstalled))

    assertContains(reason, "'agent' is not on PATH")
    assertContains(reason, "curl https://cursor.com/install")
    assertContains(reason, "relaunch with a different --agent")
  }

  @Test
  fun `any declared executable satisfies availability`() {
    assertNull(unavailableAgentLauncherReason("cursor", { it == "cursor-agent" }))
    assertNull(unavailableAgentLauncherReason("cursor", { it == "agent" }))
  }

  @Test
  fun `agent ids are normalized and unknown or launcherless ids are ignored`() {
    assertNotNull(unavailableAgentLauncherReason("  CURSOR ", nothingInstalled))
    assertNull(unavailableAgentLauncherReason(null, nothingInstalled))
    assertNull(unavailableAgentLauncherReason("", nothingInstalled))
    assertNull(unavailableAgentLauncherReason("not-an-agent", nothingInstalled))
    // copilot has no runtime launch path, so availability is not its gate to answer.
    assertNull(unavailableAgentLauncherReason("copilot", nothingInstalled))
  }

  @Test
  fun `an installed CLI produces no reason`() {
    InstallAgent.entries.forEach { agent ->
      assertNull(unavailableAgentLauncherReason(agent.id, everythingInstalled))
    }
  }
}
