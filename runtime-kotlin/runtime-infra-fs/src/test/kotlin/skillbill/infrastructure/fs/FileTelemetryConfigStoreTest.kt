package skillbill.infrastructure.fs

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonSupport
import skillbill.model.EnvironmentContext
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

  @Test
  fun `config path defaults to durable XDG location for fresh installs`(@TempDir tempDir: Path) {
    val resolved = resolveTelemetryConfigPath(emptyMap(), tempDir)

    assertEquals(
      tempDir.resolve(".config/skill-bill/config.json").toAbsolutePath().normalize(),
      resolved,
    )
  }

  @Test
  fun `config path prefers existing XDG config over legacy`(@TempDir tempDir: Path) {
    val xdg = tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(xdg.parent)
    Files.writeString(xdg, "{}\n")
    Files.createDirectories(tempDir.resolve(".skill-bill"))
    Files.writeString(tempDir.resolve(".skill-bill/config.json"), "{}\n")

    assertEquals(xdg.toAbsolutePath().normalize(), resolveTelemetryConfigPath(emptyMap(), tempDir))
  }

  @Test
  fun `config path falls back to legacy path when only legacy exists`(@TempDir tempDir: Path) {
    val legacy = tempDir.resolve(".skill-bill/config.json")
    Files.createDirectories(legacy.parent)
    Files.writeString(legacy, "{}\n")

    assertEquals(legacy.toAbsolutePath().normalize(), resolveTelemetryConfigPath(emptyMap(), tempDir))
  }

  @Test
  fun `config path honors XDG_CONFIG_HOME override`(@TempDir tempDir: Path) {
    val xdgHome = tempDir.resolve("custom-xdg")

    assertEquals(
      xdgHome.resolve("skill-bill/config.json").toAbsolutePath().normalize(),
      resolveTelemetryConfigPath(mapOf(XDG_CONFIG_HOME_KEY to xdgHome.toString()), tempDir),
    )
  }

  @Test
  fun `explicit config env override wins over durable and legacy paths`(@TempDir tempDir: Path) {
    Files.createDirectories(tempDir.resolve(".config/skill-bill"))
    Files.writeString(tempDir.resolve(".config/skill-bill/config.json"), "{}\n")
    val pinned = tempDir.resolve("pinned/config.json")

    assertEquals(
      pinned.toAbsolutePath().normalize(),
      resolveTelemetryConfigPath(mapOf(CONFIG_ENVIRONMENT_KEY to pinned.toString()), tempDir),
    )
  }

  // SKILL-52.3: the random install-id seed moved out of the pure domain into this adapter.
  @Test
  fun `ensure prefers the injected install id env value when none is persisted`(@TempDir tempDir: Path) {
    val configPath = tempDir.resolve("config.json")

    val document = ensureTelemetryConfigFile(
      configPath,
      mapOf(INSTALL_ID_ENVIRONMENT_KEY to "  env-install-id  "),
    )

    assertEquals("env-install-id", document.payload["install_id"])
  }

  @Test
  fun `ensure mints a fresh uuid when neither env nor persisted install id exist`(@TempDir tempDir: Path) {
    val first = ensureTelemetryConfigFile(tempDir.resolve("first.json"), emptyMap())
      .payload["install_id"] as String
    val second = ensureTelemetryConfigFile(tempDir.resolve("second.json"), emptyMap())
      .payload["install_id"] as String

    assertTrue(first.isNotBlank(), "Minted install id must be non-blank.")
    assertNotEquals(first, second, "Each fresh install id must be distinct.")
  }

  @Test
  fun `ensure prefers an existing persisted install id over the fallback`(@TempDir tempDir: Path) {
    val configPath = tempDir.resolve("config.json")
    Files.writeString(
      configPath,
      JsonSupport.mapToJsonString(mapOf("install_id" to "persisted-id")) + "\n",
    )
    val store = storeFor(tempDir, configPath, installIdEnv = "ignored-because-persisted-wins")

    val document = store.ensure()

    assertEquals("persisted-id", document.payload["install_id"])
  }

  @Test
  fun `ensure uses the env fallback install id when none is persisted`(@TempDir tempDir: Path) {
    val configPath = tempDir.resolve("config.json")
    val store = storeFor(tempDir, configPath, installIdEnv = "env-fallback-id")

    val document = store.ensure()

    assertEquals("env-fallback-id", document.payload["install_id"])
    assertEquals(
      mapOf(
        "level" to "anonymous",
        "proxy_url" to "",
        "batch_size" to 50,
      ),
      document.payload["telemetry"],
    )
  }

  private fun storeFor(tempDir: Path, configPath: Path, installIdEnv: String): FileTelemetryConfigStore =
    FileTelemetryConfigStore(
      EnvironmentContext(
        environment = mapOf(
          CONFIG_ENVIRONMENT_KEY to configPath.toString(),
          STATE_DIR_ENVIRONMENT_KEY to tempDir.toString(),
          INSTALL_ID_ENVIRONMENT_KEY to installIdEnv,
        ),
        userHome = tempDir,
      ),
    )
}
