package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliConfigResolveExternalAddonsRuntimeTest {
  @Test
  fun `config resolve-external-addons is registered and shows help`() {
    val result = CliRuntime.run(listOf("config", "resolve-external-addons", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "machine-global")
  }

  @Test
  fun `absent machine-global config resolves to empty`() {
    val home = Files.createTempDirectory("ext-addon-no-config")

    val result = CliRuntime.run(
      listOf("config", "resolve-external-addons"),
      CliRuntimeContext(
        userHome = home,
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to home.resolve("config.json").toString()),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(result.stdout.isBlank())
  }

  @Test
  fun `valid config lists sources in declared order`() {
    val home = Files.createTempDirectory("ext-addon-valid")
    val ios = Files.createDirectories(home.resolve("private/ios"))
    val kotlin = Files.createDirectories(home.resolve("private/kotlin"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("path" to kotlin.toString(), "platform" to "kotlin"),
            mapOf("path" to ios.toString(), "platform" to "ios"),
          ),
      ),
    )

    val result = CliRuntime.run(
      listOf("config", "resolve-external-addons"),
      CliRuntimeContext(userHome = home, environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "kotlin\t$kotlin")
    assertContains(result.stdout, "ios\t$ios")
  }

  @Test
  fun `platform resolver ignores shared config agent add-on entries`() {
    val home = Files.createTempDirectory("ext-addon-agent-entry")
    val ios = Files.createDirectories(home.resolve("private/ios"))
    val agentAddons = Files.createDirectories(home.resolve("private/agent-addons"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("kind" to "agent-addon", "path" to agentAddons.toString()),
            mapOf("kind" to "platform-pack", "path" to ios.toString(), "platform" to "ios"),
          ),
      ),
    )

    val result = CliRuntime.run(
      listOf("config", "resolve-external-addons"),
      CliRuntimeContext(userHome = home, environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ios\t$ios\n", result.stdout)
  }

  @Test
  fun `config resolve-external-agent-addons lists agent add-on source roots`() {
    val home = Files.createTempDirectory("ext-agent-addon-valid")
    val ios = Files.createDirectories(home.resolve("private/ios"))
    val agentAddons = Files.createDirectories(home.resolve("private/agent-addons"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("path" to ios.toString(), "platform" to "ios"),
            mapOf("kind" to "agent-addon", "path" to agentAddons.toString()),
          ),
      ),
    )

    val result = CliRuntime.run(
      listOf("config", "resolve-external-agent-addons"),
      CliRuntimeContext(userHome = home, environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("$agentAddons\n", result.stdout)
  }

  @Test
  fun `malformed machine-global config exits non-zero`() {
    val home = Files.createTempDirectory("ext-addon-malformed")
    Files.createDirectories(home.resolve(".skill-bill"))
    Files.writeString(configPath(home), "{ broken json")

    val result = CliRuntime.run(
      listOf("config", "resolve-external-addons"),
      CliRuntimeContext(userHome = home, environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "config")
  }

  private fun configPath(home: Path): Path = home.resolve(".skill-bill").resolve("config.json")

  private fun writeConfig(home: Path, payload: Map<String, Any?>) {
    Files.createDirectories(home.resolve(".skill-bill"))
    Files.writeString(configPath(home), JsonSupport.mapToJsonString(payload) + "\n")
  }
}
