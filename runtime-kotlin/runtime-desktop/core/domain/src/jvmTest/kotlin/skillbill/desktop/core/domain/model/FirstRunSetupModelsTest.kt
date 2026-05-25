package skillbill.desktop.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunSetupModelsTest {
  @Test
  fun `supported agents and telemetry ids match install contract ids`() {
    assertEquals(listOf("copilot", "claude", "codex", "opencode", "junie"), FirstRunSetupAgent.supportedIds)
    assertEquals(listOf("anonymous", "full", "off"), FirstRunTelemetryLevel.entries.map(FirstRunTelemetryLevel::id))
  }

  @Test
  fun `agent step requires at least one selected agent`() {
    assertFalse(FirstRunSetupState(selectedAgentIds = emptySet()).canContinue)
    assertTrue(FirstRunSetupState(selectedAgentIds = setOf("codex")).canContinue)
  }

  @Test
  fun `setup request preserves explicit platform selection mode`() {
    val request = FirstRunSetupState(
      selectedAgentIds = setOf("codex"),
      selectedPlatformSlugs = setOf("kotlin", "kmp"),
      platformSelectionMode = FirstRunPlatformSelectionMode.ALL,
    ).request()

    assertEquals(FirstRunPlatformSelectionMode.ALL, request.platformSelectionMode)
    assertEquals(setOf("kotlin", "kmp"), request.selectedPlatformSlugs)
  }
}
