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
  fun `zcode app version marker resolves to zcode`() {
    // SKILL-103 AC3: ZCODE_APP_VERSION is the primary zcode marker.
    assertEquals(InstallAgent.ZCODE, InvokingAgentContextResolver.detect(mapOf("ZCODE_APP_VERSION" to "1.2.3")))
  }

  @Test
  fun `zcode base url marker resolves to zcode as secondary signal`() {
    // SKILL-103 AC3: ZCODE_BASE_URL is the secondary zcode marker.
    val environment = mapOf("ZCODE_BASE_URL" to "https://zcode.example")
    assertEquals(InstallAgent.ZCODE, InvokingAgentContextResolver.detect(environment))
  }

  @Test
  fun `zcode marker does not shadow claude codex or opencode detection`() {
    // SKILL-103 AC3/non-goal: existing claude/codex/opencode detection precedence is unchanged —
    // each still wins when its own marker is present alongside zcode's.
    assertEquals(
      InstallAgent.CLAUDE,
      InvokingAgentContextResolver.detect(mapOf("ZCODE_APP_VERSION" to "1", "CLAUDECODE" to "1")),
    )
    assertEquals(
      InstallAgent.CODEX,
      InvokingAgentContextResolver.detect(mapOf("ZCODE_APP_VERSION" to "1", "CODEX_SANDBOX" to "1")),
    )
    assertEquals(
      InstallAgent.OPENCODE,
      InvokingAgentContextResolver.detect(mapOf("ZCODE_BASE_URL" to "https://zcode", "OPENCODE" to "1")),
    )
  }

  @Test
  fun `blank zcode marker value does not trigger detection`() {
    assertNull(InvokingAgentContextResolver.detect(mapOf("ZCODE_APP_VERSION" to "")))
    assertNull(InvokingAgentContextResolver.detect(mapOf("ZCODE_BASE_URL" to "")))
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
    // Claude appears first in the signal order, so it wins over codex/opencode/zcode.
    assertEquals(
      InstallAgent.CLAUDE,
      InvokingAgentContextResolver.detect(
        mapOf(
          "CLAUDECODE" to "1",
          "CODEX_SANDBOX" to "1",
          "OPENCODE" to "1",
          "ZCODE_APP_VERSION" to "1",
        ),
      ),
    )
  }
}
