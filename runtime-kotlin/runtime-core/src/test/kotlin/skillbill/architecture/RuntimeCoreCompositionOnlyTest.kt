package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeCoreCompositionOnlyTest {
  private val runtimeKotlinRoot: Path =
    ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin")

  @Test
  fun `every declared module has a Gradle edge expectation`() {
    val covered = MODULE_EDGE_EXPECTATIONS.keys
    assertEquals(
      RuntimeModuleCatalog.declaredGradleModules.toSet(),
      covered,
      "Every module in declaredGradleModules must have an edge expectation entry.",
    )
  }

  @Test
  fun `module api edges match the recorded expectation`() {
    MODULE_EDGE_EXPECTATIONS.forEach { (moduleName, expectation) ->
      val source = Files.readString(runtimeKotlinRoot.resolve("$moduleName/build.gradle.kts"))
      val apiEdges = ArchitectureScanSupport.projectEdgesForConfiguration(source, "api")
      assertEquals(
        expectation.api,
        apiEdges,
        "$moduleName api(project(...)) edges drifted from the recorded expectation.",
      )
    }
  }

  @Test
  fun `module implementation edges match the recorded expectation`() {
    MODULE_EDGE_EXPECTATIONS.forEach { (moduleName, expectation) ->
      val source = Files.readString(runtimeKotlinRoot.resolve("$moduleName/build.gradle.kts"))
      val implementationEdges = ArchitectureScanSupport.projectEdgesForConfiguration(source, "implementation")
      assertEquals(
        expectation.implementation,
        implementationEdges,
        "$moduleName implementation(project(...)) edges drifted from the recorded expectation.",
      )
    }
  }

  @Test
  fun `runtime-core does not publish infrastructure or entrypoint modules as api`() {
    val source = Files.readString(runtimeKotlinRoot.resolve("runtime-core/build.gradle.kts"))
    val apiEdges = ArchitectureScanSupport.projectEdgesForConfiguration(source, "api")
    val banned =
      apiEdges.filter { edge ->
        edge.startsWith("runtime-infra-") ||
          edge == "runtime-cli" ||
          edge == "runtime-mcp"
      }
    assertTrue(
      banned.isEmpty(),
      "runtime-core must not publish infrastructure or entrypoint modules as api(...). " +
        "Offenders: $banned",
    )
  }

  @Test
  fun `project edge reader fails when an edge is added`() {
    val source = """
    dependencies {
      api(project(":runtime-application"))
      api(project(":runtime-ports"))
      implementation(project(":runtime-domain"))
      implementation(project(":runtime-contracts"))
      implementation(project(":runtime-infra-fs"))
      implementation(project(":runtime-infra-http"))
      implementation(project(":runtime-infra-sqlite"))
      implementation(project(":runtime-extra"))
    }
    """.trimIndent()
    val edges = ArchitectureScanSupport.projectEdgesForConfiguration(source, "implementation")
    assertTrue(
      edges != MODULE_EDGE_EXPECTATIONS.getValue("runtime-core").implementation,
      "Added edge must change the implementation set.",
    )
  }

  @Test
  fun `project edge reader fails when an edge is removed`() {
    val source = """
      dependencies {
        api(project(":runtime-application"))
        api(project(":runtime-ports"))
        implementation(project(":runtime-domain"))
        implementation(project(":runtime-contracts"))
        implementation(project(":runtime-infra-fs"))
        implementation(project(":runtime-infra-http"))
      }
    """.trimIndent()
    val edges = ArchitectureScanSupport.projectEdgesForConfiguration(source, "implementation")
    assertTrue(
      edges != MODULE_EDGE_EXPECTATIONS.getValue("runtime-core").implementation,
      "Removed edge must change the implementation set.",
    )
  }

  @Test
  fun `project edge reader fails when an edge changes configuration`() {
    val source = """
      dependencies {
        api(project(":runtime-application"))
        api(project(":runtime-ports"))
        api(project(":runtime-domain"))
        implementation(project(":runtime-contracts"))
        implementation(project(":runtime-infra-fs"))
        implementation(project(":runtime-infra-http"))
        implementation(project(":runtime-infra-sqlite"))
      }
    """.trimIndent()
    val apiEdges = ArchitectureScanSupport.projectEdgesForConfiguration(source, "api")
    val implementationEdges = ArchitectureScanSupport.projectEdgesForConfiguration(source, "implementation")
    val expectation = MODULE_EDGE_EXPECTATIONS.getValue("runtime-core")
    assertTrue(
      apiEdges != expectation.api || implementationEdges != expectation.implementation,
      "Reclassified edge must change at least one configuration set.",
    )
  }

  private data class ModuleEdgeExpectation(
    val api: Set<String>,
    val implementation: Set<String>,
  )

  private companion object {
    val MODULE_EDGE_EXPECTATIONS: Map<String, ModuleEdgeExpectation> = mapOf(
      "runtime-application" to ModuleEdgeExpectation(
        api = setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
        implementation = emptySet(),
      ),
      "runtime-contracts" to ModuleEdgeExpectation(
        api = emptySet(),
        implementation = emptySet(),
      ),
      "runtime-core" to ModuleEdgeExpectation(
        api = setOf("runtime-application", "runtime-ports"),
        implementation = setOf(
          "runtime-domain",
          "runtime-contracts",
          "runtime-infra-fs",
          "runtime-infra-http",
          "runtime-infra-sqlite",
        ),
      ),
      "runtime-domain" to ModuleEdgeExpectation(
        api = emptySet(),
        implementation = setOf("runtime-contracts"),
      ),
      "runtime-infra-fs" to ModuleEdgeExpectation(
        api = setOf("runtime-ports", "runtime-domain"),
        implementation = setOf("runtime-contracts"),
      ),
      "runtime-infra-http" to ModuleEdgeExpectation(
        api = setOf("runtime-domain", "runtime-ports"),
        implementation = setOf("runtime-contracts"),
      ),
      "runtime-infra-sqlite" to ModuleEdgeExpectation(
        api = setOf("runtime-domain", "runtime-ports"),
        implementation = setOf("runtime-contracts"),
      ),
      "runtime-cli" to ModuleEdgeExpectation(
        api = emptySet(),
        implementation = setOf(
          "runtime-application",
          "runtime-contracts",
          "runtime-core",
          "runtime-domain",
          "runtime-ports",
        ),
      ),
      "runtime-mcp" to ModuleEdgeExpectation(
        api = emptySet(),
        implementation = setOf(
          "runtime-application",
          "runtime-contracts",
          "runtime-core",
          "runtime-domain",
          "runtime-ports",
        ),
      ),
      "runtime-ports" to ModuleEdgeExpectation(
        api = setOf("runtime-contracts", "runtime-domain"),
        implementation = emptySet(),
      ),
    )
  }
}
