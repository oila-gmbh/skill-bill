package skillbill.db

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabasePathsTest {
  @Test
  fun `cli path overrides environment and default path`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = "./custom/metrics.db",
        environment = mapOf(DbConstants.DB_ENVIRONMENT_KEY to "/tmp/env-metrics.db"),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("./custom/metrics.db").toAbsolutePath().normalize(), resolved)
  }

  @Test
  fun `environment path overrides default path`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = null,
        environment = mapOf(DbConstants.DB_ENVIRONMENT_KEY to "~/metrics.db"),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("/tmp/home/metrics.db"), resolved)
  }

  @Test
  fun `default path resolves under skill bill home`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = null,
        environment = emptyMap(),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("/tmp/home/.skill-bill/review-metrics.db"), resolved)
  }
}
