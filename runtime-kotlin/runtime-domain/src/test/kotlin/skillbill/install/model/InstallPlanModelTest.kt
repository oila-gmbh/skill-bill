package skillbill.install.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstallPlanModelTest {
  @Test
  fun `supported install agents are exactly the install contract set`() {
    assertEquals(
      listOf("claude", "codex", "junie", "cursor"),
      InstallAgent.supportedIds,
    )
  }

  @Test
  fun `cursor is a governed install agent and stays runtime eligible`() {
    assertEquals(InstallAgent.CURSOR, InstallAgent.fromId("cursor"))
    assertEquals(InstallAgent.CURSOR, InstallAgent.fromNormalizedId(" CURSOR "))
  }

  @Test
  fun `cursor invoking-agent markers are session identity not api credentials`() {
    val cursor = InvokingAgentContextResolver.INVOKING_AGENT_CONTEXT_SIGNALS.single { signal ->
      signal.agent == InstallAgent.CURSOR
    }
    assertEquals(listOf("CURSOR_AGENT", "CURSOR_INVOKED_AS"), cursor.markerKeys)
  }

  @Test
  fun `native agent provider ids mirror the install agent order`() {
    assertEquals(
      listOf("claude", "codex", "junie", "cursor"),
      NativeAgentProviderId.entries.map(NativeAgentProviderId::id),
    )
  }

  @Test
  fun `agent ids parse only governed install targets`() {
    InstallAgent.supportedIds.forEach { id ->
      assertEquals(id, InstallAgent.fromId(id).id)
    }

    val error = assertFailsWith<IllegalArgumentException> {
      InstallAgent.fromId("not-an-agent")
    }

    assertContains(error.message.orEmpty(), "Unknown agent 'not-an-agent'")
    InstallAgent.supportedIds.forEach { id ->
      assertContains(error.message.orEmpty(), id)
    }
  }

  @Test
  fun `telemetry levels match cli values`() {
    assertEquals(
      listOf("anonymous", "full", "off"),
      InstallTelemetryLevel.entries.map(InstallTelemetryLevel::id),
    )
  }
}
