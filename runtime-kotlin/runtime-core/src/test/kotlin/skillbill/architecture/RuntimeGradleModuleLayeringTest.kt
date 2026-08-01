package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeGradleModuleLayeringTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `settings declares runtime modules`() {
    val expectedModules =
      setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-domain",
        "runtime-infra-fs",
        "runtime-infra-http",
        "runtime-infra-sqlite",
        "runtime-cli",
        "runtime-mcp",
        "runtime-ports",
      )

    assertEquals(expectedModules, declaredSettingsModules())
  }

  @Test
  fun `top level runtime modules do not depend upward`() {
    assertNoProjectDependencies("runtime-contracts")
    assertNoProjectDependencies(
      "runtime-domain",
      "runtime-ports",
      "runtime-application",
      "runtime-core",
    )
    assertNoProjectDependencies("runtime-ports", "runtime-application", "runtime-core")
    assertNoProjectDependencies(
      "runtime-application",
      "runtime-infra-fs",
      "runtime-infra-http",
      "runtime-infra-sqlite",
    )

    listOf("runtime-infra-fs", "runtime-infra-http", "runtime-infra-sqlite").forEach { moduleName ->
      assertNoProjectDependencies(
        moduleName,
        "runtime-application",
        "runtime-core",
        "runtime-cli",
        "runtime-mcp",
      )
    }
  }

  private fun declaredSettingsModules(): Set<String> {
    val settings = Files.readString(runtimeRoot.resolve("settings.gradle.kts"))
    val includeBlock =
      Regex("include\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
        .find(settings)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    return Regex("\"([A-Za-z0-9:-]+)\"")
      .findAll(includeBlock)
      .map { match -> match.groupValues[1] }
      .toSet()
  }

  /**
   * Layering constrains the shipped dependency graph, so only main-source configurations are
   * checked. Test and test-fixture code binds real adapters on purpose; that crossing is pinned
   * per module by [RuntimeAdapterDependencyAllowlistTest].
   */
  private fun assertNoProjectDependencies(moduleName: String, vararg bannedDependencies: String) {
    val modulePath = moduleName.replace(':', '/')
    val buildFile = runtimeRoot.resolve("$modulePath/build.gradle.kts")
    val source = Files.readString(buildFile)
    val projectDependencies =
      source.lineSequence()
        .filterNot { line -> TEST_CONFIGURATIONS.any { it in line } }
        .flatMap { line -> Regex("project\\(\":([A-Za-z0-9:-]+)\"\\)").findAll(line) }
        .map { match -> match.groupValues[1] }
        .toSet()
    val violations =
      if (bannedDependencies.isEmpty()) {
        projectDependencies
      } else {
        projectDependencies.intersect(bannedDependencies.toSet())
      }
    assertTrue(
      violations.isEmpty(),
      "$moduleName has banned project dependencies: ${violations.joinToString()}",
    )
  }

  private companion object {
    val TEST_CONFIGURATIONS: List<String> = listOf(
      "testImplementation",
      "testFixturesImplementation",
      "testFixturesApi",
      "testRuntimeOnly",
      "testCompileOnly",
    )
  }
}
