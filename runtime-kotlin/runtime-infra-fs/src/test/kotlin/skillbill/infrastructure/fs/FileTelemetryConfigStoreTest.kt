package skillbill.infrastructure.fs

import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTelemetryConfigStoreTest {
  @Test
  fun `telemetry state dir expands user home at filesystem adapter seam`() {
    val home = Path.of("build/tmp/skill-bill-home").toAbsolutePath().normalize()

    assertEquals(
      home,
      resolveTelemetryStateDir(mapOf(STATE_DIR_ENVIRONMENT_KEY to "~"), home),
    )
    assertEquals(
      home.resolve("state").toAbsolutePath().normalize(),
      resolveTelemetryStateDir(mapOf(STATE_DIR_ENVIRONMENT_KEY to "~/state"), home),
    )
  }

  @Test
  fun `telemetry config path expands user home at filesystem adapter seam`() {
    val home = Path.of("build/tmp/skill-bill-home").toAbsolutePath().normalize()

    assertEquals(
      home,
      resolveTelemetryConfigPath(mapOf(CONFIG_ENVIRONMENT_KEY to "~"), home),
    )
    assertEquals(
      home.resolve("config.json").toAbsolutePath().normalize(),
      resolveTelemetryConfigPath(mapOf(CONFIG_ENVIRONMENT_KEY to "~/config.json"), home),
    )
  }
}
