package skillbill.telemetry

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SKILL-52.3: guards the effect-purity of the runtime-domain telemetry rules.
 *
 *  - [defaultLocalTelemetryConfig] no longer mints a random `UUID`; the install id is injected by
 *    the caller (the random fallback now lives in the infra-fs adapter). The function is therefore
 *    deterministic: the same `installId` always yields a byte-equivalent payload.
 *  - [parseRemoteStatsWindow] no longer reads the system clock; `today` is supplied by the caller,
 *    so the window math is exercised here against a fixed reference date.
 */
class TelemetryEffectPurityTest {
  @Test
  fun `defaultLocalTelemetryConfig is deterministic for a fixed install id`() {
    val installId = "fixed-install-id"

    val first = defaultLocalTelemetryConfig(installId)
    val second = defaultLocalTelemetryConfig(installId)

    assertEquals(first, second, "Same install id must produce an equal config payload.")
    assertEquals(installId, first.payload["install_id"], "install_id must be the injected value verbatim.")
    assertEquals(
      mapOf(
        "level" to "anonymous",
        "proxy_url" to "",
        "batch_size" to DEFAULT_TELEMETRY_BATCH_SIZE,
      ),
      first.payload["telemetry"],
      "Telemetry defaults must be unchanged.",
    )
  }

  @Test
  fun `defaultLocalTelemetryConfig reflects distinct install ids`() {
    assertEquals(
      "alpha",
      defaultLocalTelemetryConfig("alpha").payload["install_id"],
    )
    assertEquals(
      "beta",
      defaultLocalTelemetryConfig("beta").payload["install_id"],
    )
  }

  @Test
  fun `parseRemoteStatsWindow defaults to a 30 day window ending at the supplied today`() {
    val today = LocalDate.parse("2026-05-29")

    val (from, to) = parseRemoteStatsWindow(today = today)

    // 30d window is inclusive of `today`, so the start is today - 29 days.
    assertEquals("2026-04-30" to "2026-05-29", from to to)
  }

  @Test
  fun `parseRemoteStatsWindow honors an explicit since against the supplied today`() {
    val today = LocalDate.parse("2026-05-29")

    val (from, to) = parseRemoteStatsWindow(since = "7d", today = today)

    assertEquals("2026-05-23" to "2026-05-29", from to to)
  }

  @Test
  fun `parseRemoteStatsWindow uses explicit date bounds and ignores today`() {
    val today = LocalDate.parse("2026-05-29")

    val (from, to) =
      parseRemoteStatsWindow(dateFrom = "2026-01-01", dateTo = "2026-01-31", today = today)

    assertEquals("2026-01-01" to "2026-01-31", from to to)
  }
}
