package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InvokingAgentContextResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvokingAgentContextResolverTest {
  @Test
  fun `claude code marker resolves to claude`() {
    assertEquals(InstallAgent.CLAUDE, InvokingAgentContextResolver.detect(mapOf("CLAUDECODE" to "1")))
  }

  @Test
  fun `codex marker resolves to codex`() {
    assertEquals(InstallAgent.CODEX, InvokingAgentContextResolver.detect(mapOf("CODEX_SANDBOX" to "seatbelt")))
  }

  @Test
  fun `opencode marker resolves to opencode`() {
    assertEquals(InstallAgent.OPENCODE, InvokingAgentContextResolver.detect(mapOf("OPENCODE" to "1")))
  }

  @Test
  fun `no marker returns null so callers use the documented last-resort default`() {
    assertNull(InvokingAgentContextResolver.detect(emptyMap()))
    assertNull(InvokingAgentContextResolver.detect(mapOf("UNRELATED" to "x")))
  }

  @Test
  fun `blank marker value does not trigger detection`() {
    assertNull(InvokingAgentContextResolver.detect(mapOf("CLAUDECODE" to "")))
  }

  @Test
  fun `ordering is deterministic when multiple markers are present`() {
    // Claude appears first in the signal order, so it wins over codex/opencode.
    assertEquals(
      InstallAgent.CLAUDE,
      InvokingAgentContextResolver.detect(
        mapOf("CLAUDECODE" to "1", "CODEX_SANDBOX" to "1", "OPENCODE" to "1"),
      ),
    )
  }
}
