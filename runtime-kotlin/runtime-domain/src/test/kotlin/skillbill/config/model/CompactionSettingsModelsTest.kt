package skillbill.config.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CompactionSettingsModelsTest {
  @Test
  fun `absent configuration compacts every phase on the validated default`() {
    val directive = CompactionSettings.DEFAULT.directiveFor("implement")

    assertEquals(PhaseCompactionDirective(DEFAULT_COMPACTION_WINDOW_TOKENS, DEFAULT_COMPACTION_TRIGGER_PCT), directive)
    assertEquals(280_000, directive?.triggerTokens)
  }

  @Test
  fun `parses a full settings block with a phase override`() {
    val parsed = assertIs<CompactionSettingsParse.Valid>(
      parseCompactionSettings(
        mapOf(
          "enabled" to true,
          "window_tokens" to 400_000,
          "trigger_pct" to 70,
          "phases" to mapOf("implement" to mapOf("window_tokens" to 600_000, "trigger_pct" to 80)),
        ),
      ),
    )

    assertEquals(PhaseCompactionDirective(600_000, 80), parsed.settings.directiveFor("implement"))
    assertEquals(PhaseCompactionDirective(400_000, 70), parsed.settings.directiveFor("audit"))
  }

  @Test
  fun `a phase override inherits unspecified fields from the top level`() {
    val parsed = assertIs<CompactionSettingsParse.Valid>(
      parseCompactionSettings(
        mapOf("window_tokens" to 500_000, "phases" to mapOf("audit" to mapOf("trigger_pct" to 90))),
      ),
    )

    assertEquals(PhaseCompactionDirective(500_000, 90), parsed.settings.directiveFor("audit"))
  }

  @Test
  fun `disabling yields no directive for any phase`() {
    val parsed = assertIs<CompactionSettingsParse.Valid>(parseCompactionSettings(mapOf("enabled" to false)))

    assertNull(parsed.settings.directiveFor("implement"))
    assertNull(parsed.settings.directiveFor("audit"))
  }

  @Test
  fun `a trigger below the thrash floor is rejected`() {
    // The 150k window at 60% that the provider aborted mid-run as thrashing.
    val parsed = assertIs<CompactionSettingsParse.Invalid>(
      parseCompactionSettings(mapOf("window_tokens" to 150_000, "trigger_pct" to 60)),
    )

    assertEquals("compaction.window_tokens", parsed.keyPath)
    assertEquals(true, parsed.reason.contains("thrashing"))
  }

  @Test
  fun `a large window cannot smuggle a thrashing trigger through a small percentage`() {
    val parsed = assertIs<CompactionSettingsParse.Invalid>(
      parseCompactionSettings(mapOf("window_tokens" to 1_000_000, "trigger_pct" to 10)),
    )

    assertEquals("compaction.window_tokens", parsed.keyPath)
  }

  @Test
  fun `a thrashing phase override is rejected even when the top level is sane`() {
    val parsed = assertIs<CompactionSettingsParse.Invalid>(
      parseCompactionSettings(
        mapOf("window_tokens" to 400_000, "phases" to mapOf("implement" to mapOf("window_tokens" to 100_000))),
      ),
    )

    assertEquals("compaction.phases.implement.window_tokens", parsed.keyPath)
  }

  @Test
  fun `unknown fields and non-phase keys are rejected`() {
    assertEquals(
      "compaction.windowTokens",
      assertIs<CompactionSettingsParse.Invalid>(parseCompactionSettings(mapOf("windowTokens" to 400_000))).keyPath,
    )
    assertEquals(
      "compaction.phases.not_a_phase",
      assertIs<CompactionSettingsParse.Invalid>(
        parseCompactionSettings(mapOf("phases" to mapOf("not_a_phase" to mapOf("trigger_pct" to 70)))),
      ).keyPath,
    )
  }

  @Test
  fun `malformed scalars are rejected with their key path`() {
    assertEquals(
      "compaction.enabled",
      assertIs<CompactionSettingsParse.Invalid>(parseCompactionSettings(mapOf("enabled" to "yes"))).keyPath,
    )
    assertEquals(
      "compaction.window_tokens",
      assertIs<CompactionSettingsParse.Invalid>(parseCompactionSettings(mapOf("window_tokens" to "400000"))).keyPath,
    )
    assertEquals(
      "compaction.trigger_pct",
      assertIs<CompactionSettingsParse.Invalid>(
        parseCompactionSettings(mapOf("window_tokens" to 400_000, "trigger_pct" to 120)),
      ).keyPath,
    )
    assertEquals(
      "compaction",
      assertIs<CompactionSettingsParse.Invalid>(parseCompactionSettings("on")).keyPath,
    )
  }
}
