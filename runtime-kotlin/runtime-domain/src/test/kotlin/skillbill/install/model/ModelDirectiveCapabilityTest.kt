package skillbill.install.model

import skillbill.agent.model.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDirectiveCapabilityTest {
  @Test
  fun `cursor joins claude and codex as model-directive capable agents`() {
    assertEquals(
      setOf(InstallAgent.CLAUDE, InstallAgent.CODEX, InstallAgent.CURSOR),
      MODEL_DIRECTIVE_CAPABLE_AGENTS,
    )
  }

  @Test
  fun `supportsModelDirective accepts cursor and rejects junie`() {
    assertTrue(supportsModelDirective(AgentId("cursor")))
    assertTrue(supportsModelDirective(AgentId("CURSOR")))
    assertTrue(supportsModelDirective(AgentId("claude")))
    assertTrue(supportsModelDirective(AgentId("codex")))
    assertFalse(supportsModelDirective(AgentId("junie")))
    assertFalse(supportsModelDirective(AgentId("copilot")))
    assertFalse(supportsModelDirective(null))
    assertFalse(supportsModelDirective(AgentId(" ")))
  }
}
