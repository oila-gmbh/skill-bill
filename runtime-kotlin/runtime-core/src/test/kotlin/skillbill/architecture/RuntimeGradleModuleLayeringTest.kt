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
  fun `settings declares runtime modules including desktop starter graph`() {
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
        "runtime-desktop",
        "runtime-desktop:core:common",
        "runtime-desktop:core:data",
        "runtime-desktop:core:database",
        "runtime-desktop:core:datastore",
        "runtime-desktop:core:designsystem",
        "runtime-desktop:core:domain",
        "runtime-desktop:core:navigation",
        "runtime-desktop:core:testing",
        "runtime-desktop:core:ui",
        "runtime-desktop:feature:skillbill",
        "runtime-mcp",
        "runtime-ports",
      )

    assertEquals(expectedModules, declaredSettingsModules())
  }

  @Test
  fun `top level runtime modules do not depend upward or on desktop`() {
    assertNoProjectDependencies("runtime-contracts")
    assertNoProjectDependencies(
      "runtime-domain",
      "runtime-ports",
      "runtime-application",
      "runtime-core",
      "runtime-desktop",
    )
    assertNoProjectDependencies("runtime-ports", "runtime-application", "runtime-core", "runtime-desktop")
    assertNoProjectDependencies(
      "runtime-application",
      "runtime-infra-fs",
      "runtime-infra-http",
      "runtime-infra-sqlite",
      "runtime-desktop",
    )
    assertNoProjectDependencies("runtime-core", "runtime-desktop")
    assertNoProjectDependencies("runtime-cli", "runtime-desktop")
    assertNoProjectDependencies("runtime-mcp", "runtime-desktop")

    listOf("runtime-infra-fs", "runtime-infra-http", "runtime-infra-sqlite").forEach { moduleName ->
      assertNoProjectDependencies(
        moduleName,
        "runtime-application",
        "runtime-core",
        "runtime-cli",
        "runtime-desktop",
        "runtime-mcp",
      )
    }
  }

  @Test
  fun `non desktop runtime modules do not import desktop packages`() {
    val violations = declaredSettingsModules()
      .filter { moduleName -> moduleName.startsWith("runtime-") && !moduleName.startsWith("runtime-desktop") }
      .flatMap { moduleName ->
        val sourceRoot = runtimeRoot.resolve("${moduleName.replace(':', '/')}/src/main/kotlin")
        if (!Files.isDirectory(sourceRoot)) {
          emptyList()
        } else {
          Files.walk(sourceRoot).use { paths ->
            paths
              .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
              .flatMap { path ->
                val text = Files.readString(path)
                importPattern.findAll(text)
                  .map { match -> match.groupValues[1].substringBefore(" as ") }
                  .filter { importedName -> importedName.startsWith("skillbill.desktop") }
                  .map { importedName -> "${runtimeRoot.relativize(path)} imports $importedName" }
                  .toList()
                  .stream()
              }
              .toList()
          }
        }
      }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `desktop starter modules keep app core and feature dependency direction`() {
    assertNoProjectDependencies(
      "runtime-desktop",
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
    assertNoProjectDependencies("runtime-desktop:core:common")
    assertNoProjectDependencies(
      "runtime-desktop:core:domain",
      "runtime-desktop:core:data",
      "runtime-desktop:core:ui",
      "runtime-desktop:feature:skillbill",
    )
    assertNoProjectDependencies(
      "runtime-desktop:core:data",
      "runtime-desktop:core:ui",
      "runtime-desktop:feature:skillbill",
    )
    assertNoProjectDependencies(
      "runtime-desktop:core:database",
      "runtime-desktop:core:data",
      "runtime-desktop:core:ui",
      "runtime-desktop:feature:skillbill",
    )
    assertNoProjectDependencies(
      "runtime-desktop:core:datastore",
      "runtime-desktop:core:data",
      "runtime-desktop:core:ui",
      "runtime-desktop:feature:skillbill",
    )
    assertNoProjectDependencies(
      "runtime-desktop:core:designsystem",
      "runtime-desktop:core:data",
      "runtime-desktop:feature:skillbill",
    )
    assertNoProjectDependencies(
      "runtime-desktop:core:ui",
      "runtime-desktop:core:data",
      "runtime-desktop:feature:skillbill",
    )
    assertNoProjectDependencies("runtime-desktop:core:navigation", "runtime-desktop:feature:skillbill")
    assertNoProjectDependencies("runtime-desktop:core:testing", "runtime-desktop:feature:skillbill")
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

  private fun assertNoProjectDependencies(moduleName: String, vararg bannedDependencies: String) {
    val modulePath = moduleName.replace(':', '/')
    val buildFile = runtimeRoot.resolve("$modulePath/build.gradle.kts")
    val source = Files.readString(buildFile)
    val projectDependencies =
      Regex("project\\(\":([A-Za-z0-9:-]+)\"\\)")
        .findAll(source)
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
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
  }
}
