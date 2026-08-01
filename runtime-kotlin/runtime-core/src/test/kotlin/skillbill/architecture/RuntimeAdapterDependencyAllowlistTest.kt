package skillbill.architecture

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
      RuntimeModuleCatalog.declaredGradleModules.toSet(),
      ModuleAllowlists.MAIN_PROJECT_DEPENDENCIES.keys,
      "RuntimeAdapterDependencyAllowlistTest must classify every declared Gradle module.",
    )

    val drift = RuntimeModuleCatalog.declaredGradleModules.mapNotNull { moduleName ->
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
      RuntimeModuleCatalog.declaredGradleModules.toSet(),
      ModuleAllowlists.TEST_FIXTURES_PROJECT_DEPENDENCIES.keys,
      "RuntimeAdapterDependencyAllowlistTest must classify every declared Gradle module.",
    )

    val drift = RuntimeModuleCatalog.declaredGradleModules.mapNotNull { moduleName ->
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

  @Test
  fun `runtime-application declares no production dependency on runtime-infra-fs (SKILL-140 AC-005)`() {
    // The real planning-projection validator wiring lives in runtime-application testFixtures (which may
    // reach infra-fs); production application code depends on the domain port alone. This pins the module
    // direction the real-validator integration suites rely on: infra-fs stays out of main source.
    assertEquals(
      false,
      "runtime-infra-fs" in mainProjectDependencies("runtime-application"),
      "runtime-application must not gain a production dependency on runtime-infra-fs.",
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
      "runtime-mcp" to emptySet(),
      "runtime-ports" to emptySet(),
    )
  }
}
