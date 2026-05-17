package skillbill.install.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstallPlanModelTest {
  @Test
  fun `supported install agents are exactly the install contract set`() {
    assertEquals(
      listOf("copilot", "claude", "codex", "opencode", "junie"),
      InstallAgent.supportedIds,
    )
  }

  @Test
  fun `agent ids parse only governed install targets`() {
    InstallAgent.supportedIds.forEach { id ->
      assertEquals(id, InstallAgent.fromId(id).id)
    }

    val error = assertFailsWith<IllegalArgumentException> {
      InstallAgent.fromId("cursor")
    }

    assertContains(error.message.orEmpty(), "Unknown agent 'cursor'")
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
