package skillbill.architecture

import skillbill.RuntimeModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeAdapterDependencyAllowlistTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `every declared module has only the curated main-source runtime project dependencies`() {
    assertEquals(
      RuntimeModule.declaredGradleModules.toSet(),
      ModuleAllowlists.MAIN_PROJECT_DEPENDENCIES.keys,
      "RuntimeAdapterDependencyAllowlistTest must classify every declared Gradle module.",
    )

    val drift = RuntimeModule.declaredGradleModules.mapNotNull { moduleName ->
      val expected = ModuleAllowlists.MAIN_PROJECT_DEPENDENCIES.getValue(moduleName)
      val actual = mainProjectDependencies(moduleName)
      val missing = expected - actual
      val extra = actual - expected
      if (missing.isEmpty() && extra.isEmpty()) {
        null
      } else {
        buildString {
          append(moduleName)
          if (missing.isNotEmpty()) {
            append("\n  Missing: ")
            append(missing.sorted().joinToString())
          }
          if (extra.isNotEmpty()) {
            append("\n  Extra: ")
            append(extra.sorted().joinToString())
          }
        }
      }
    }

    assertEquals(
      emptyList(),
      drift,
      "Main-source project dependencies drifted from the curated per-module allow-list.",
    )
  }

  @Test
  fun `every declared module has only the curated test-fixtures runtime project dependencies`() {
    assertEquals(
      RuntimeModule.declaredGradleModules.toSet(),
      ModuleAllowlists.TEST_FIXTURES_PROJECT_DEPENDENCIES.keys,
      "RuntimeAdapterDependencyAllowlistTest must classify every declared Gradle module.",
    )

    val drift = RuntimeModule.declaredGradleModules.mapNotNull { moduleName ->
      val expected = ModuleAllowlists.TEST_FIXTURES_PROJECT_DEPENDENCIES.getValue(moduleName)
      val actual = testFixturesProjectDependencies(moduleName)
      val missing = expected - actual
      val extra = actual - expected
      if (missing.isEmpty() && extra.isEmpty()) {
        null
      } else {
        buildString {
          append(moduleName)
          if (missing.isNotEmpty()) {
            append("\n  Missing: ")
            append(missing.sorted().joinToString())
          }
          if (extra.isNotEmpty()) {
            append("\n  Extra: ")
            append(extra.sorted().joinToString())
          }
        }
      }
    }

    assertEquals(
      emptyList(),
      drift,
      "Test-fixtures-source project dependencies drifted from the curated per-module allow-list.",
    )
  }

  private fun testFixturesProjectDependencies(moduleName: String): Set<String> {
    val buildFile = runtimeRoot.resolve("${moduleName.replace(':', '/')}/build.gradle.kts")
    val source = Files.readString(buildFile)
    val testFixturesConfigurations = listOf("testFixturesImplementation", "testFixturesApi")
    val projectDependencies = mutableSetOf<String>()
    source.lineSequence().forEach { line ->
      if (testFixturesConfigurations.any { configName -> line.contains(configName) }) {
        Regex("project\\(\":([A-Za-z0-9:-]+)\"\\)")
          .findAll(line)
          .forEach { match -> projectDependencies += match.groupValues[1] }
      }
    }
    return projectDependencies
  }

  private fun mainProjectDependencies(moduleName: String): Set<String> {
    val buildFile = runtimeRoot.resolve("${moduleName.replace(':', '/')}/build.gradle.kts")
    val source = Files.readString(buildFile)
    val testConfigurations =
      listOf(
        "testImplementation",
        "testFixturesImplementation",
        "testFixturesApi",
        "testRuntimeOnly",
        "testCompileOnly",
        "androidTestImplementation",
        "jvmTestImplementation",
        "commonTestImplementation",
      )
    val testBlockOpen =
      Regex("^\\s*(jvmTest|androidTest|commonTest)\\.dependencies\\s*\\{")
    val projectDependencies = mutableSetOf<String>()
    var depth = 0
    var testBlockDepth = -1
    source.lineSequence().forEach { line ->
      val openMatch = testBlockOpen.find(line)
      if (openMatch != null && testBlockDepth < 0) {
        testBlockDepth = depth
      }
      val inTestBlock = testBlockDepth in 0..depth
      val isTestConfig = testConfigurations.any { configName -> line.contains(configName) }
      if (!inTestBlock && !isTestConfig) {
        Regex("project\\(\":([A-Za-z0-9:-]+)\"\\)")
          .findAll(line)
          .forEach { match -> projectDependencies += match.groupValues[1] }
      }
      depth += line.count { it == '{' }
      depth -= line.count { it == '}' }
      if (depth <= testBlockDepth) {
        testBlockDepth = -1
      }
    }
    return projectDependencies
  }

  private object ModuleAllowlists {
    val MAIN_PROJECT_DEPENDENCIES: Map<String, Set<String>> = mapOf(
      "runtime-application" to setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
      "runtime-contracts" to emptySet(),
      "runtime-core" to setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-domain",
        "runtime-infra-fs",
        "runtime-infra-http",
        "runtime-infra-sqlite",
        "runtime-ports",
      ),
      "runtime-domain" to setOf("runtime-contracts"),
      "runtime-infra-fs" to setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
      "runtime-infra-http" to setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
      "runtime-infra-sqlite" to setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
      "runtime-cli" to setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-domain",
        "runtime-ports",
      ),
      "runtime-desktop" to setOf(
        "runtime-desktop:core:common",
        "runtime-desktop:core:data",
        "runtime-desktop:core:database",
        "runtime-desktop:core:datastore",
        "runtime-desktop:core:designsystem",
        "runtime-desktop:core:domain",
        "runtime-desktop:core:navigation",
        "runtime-desktop:core:ui",
        "runtime-desktop:feature:skillbill",
      ),
      "runtime-desktop:core:common" to emptySet(),
      "runtime-desktop:core:data" to setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-desktop:core:common",
        "runtime-desktop:core:database",
        "runtime-desktop:core:domain",
        "runtime-domain",
        "runtime-ports",
      ),
      "runtime-desktop:core:database" to setOf("runtime-desktop:core:common"),
      "runtime-desktop:core:datastore" to setOf("runtime-desktop:core:common"),
      "runtime-desktop:core:designsystem" to emptySet(),
      "runtime-desktop:core:domain" to setOf("runtime-desktop:core:common"),
      "runtime-desktop:core:navigation" to setOf("runtime-desktop:core:common"),
      "runtime-desktop:core:testing" to setOf(
        "runtime-desktop:core:datastore",
        "runtime-desktop:core:domain",
        "runtime-desktop:core:navigation",
      ),
      "runtime-desktop:core:ui" to setOf(
        "runtime-desktop:core:designsystem",
        "runtime-desktop:core:domain",
      ),
      "runtime-desktop:feature:skillbill" to setOf(
        "runtime-desktop:core:common",
        "runtime-desktop:core:datastore",
        "runtime-desktop:core:designsystem",
        "runtime-desktop:core:domain",
        "runtime-desktop:core:ui",
      ),
      "runtime-mcp" to setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-domain",
        "runtime-ports",
      ),
      "runtime-ports" to setOf("runtime-contracts", "runtime-domain"),
    )

    val TEST_FIXTURES_PROJECT_DEPENDENCIES: Map<String, Set<String>> = mapOf(
      "runtime-application" to setOf("runtime-infra-fs"),
      "runtime-contracts" to emptySet(),
      "runtime-core" to emptySet(),
      "runtime-domain" to emptySet(),
      "runtime-infra-fs" to emptySet(),
      "runtime-infra-http" to emptySet(),
      "runtime-infra-sqlite" to emptySet(),
      "runtime-cli" to emptySet(),
      "runtime-desktop" to emptySet(),
      "runtime-desktop:core:common" to emptySet(),
      "runtime-desktop:core:data" to emptySet(),
      "runtime-desktop:core:database" to emptySet(),
      "runtime-desktop:core:datastore" to emptySet(),
      "runtime-desktop:core:designsystem" to emptySet(),
      "runtime-desktop:core:domain" to emptySet(),
      "runtime-desktop:core:navigation" to emptySet(),
      "runtime-desktop:core:testing" to emptySet(),
      "runtime-desktop:core:ui" to emptySet(),
      "runtime-desktop:feature:skillbill" to emptySet(),
      "runtime-mcp" to emptySet(),
      "runtime-ports" to emptySet(),
    )
  }
}
