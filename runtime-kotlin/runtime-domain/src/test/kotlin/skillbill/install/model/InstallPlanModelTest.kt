package skillbill.install.model

import kotlin.test.Test
import kotlin.test.assertEquals

class InstallPlanModelTest {
  @Test
  fun `supported install agents are exactly the install contract set`() {
    assertEquals(
      listOf("copilot", "claude", "codex", "opencode", "junie"),
      InstallAgent.supportedIds,
    )
  }

  @Test
  fun `telemetry levels match cli values`() {
    assertEquals(
      listOf("anonymous", "full", "off"),
      InstallTelemetryLevel.entries.map(InstallTelemetryLevel::id),
    )
  }
}
