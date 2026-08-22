package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidValidationGateDeclarationError
import skillbill.scaffold.platformpack.parseValidationGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShellContentLoaderValidationGateTest {
  @Test
  fun `absent validation_gate parses to null`() {
    assertNull(parseValidationGate(mapOf("platform" to "kotlin"), "kotlin"))
  }

  @Test
  fun `well formed validation_gate parses argv and findings`() {
    val manifest = wellFormedGateManifest()
    val gate = parseValidationGate(manifest, "kotlin")
    requireNotNull(gate)
    assertEquals(listOf("./gradlew", "check"), gate.fullGateCommand)
    assertEquals(listOf("./gradlew", "check", "--rerun-tasks"), gate.cacheBypassingFullGateCommand)
    assertEquals(listOf("./gradlew", "check", "--continue"), gate.collectAllFullGateCommand)
    assertEquals(
      listOf("./gradlew", "check", "--continue", "--rerun-tasks"),
      gate.cacheBypassingCollectAllFullGateCommand,
    )
    assertEquals(listOf("./gradlew", "compileKotlin"), gate.buildCommand)
    assertEquals(listOf("./gradlew", "compileKotlin", "--no-build-cache"), gate.cacheBypassingBuildCommand)
    assertEquals("junit_xml", gate.findings.format.wireValue)
    assertEquals("gradle_kotlin_compiler_stdout", gate.findings.compilerDiagnostics.format.wireValue)
    assertEquals(listOf("@Suppress", "@file:Suppress"), gate.suppressionMarkers)
  }

  @Test
  fun `present validation_gate missing collect_all_full_gate_command loud-fails`() {
    val gate = wellFormedGate().toMutableMap()
    gate.remove("collect_all_full_gate_command")
    val manifest = mapOf("validation_gate" to gate)
    assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
  }

  @Test
  fun `collect_all_full_gate_command blank token loud-fails`() {
    val gate = wellFormedGate().toMutableMap()
    gate["collect_all_full_gate_command"] = listOf("./gradlew", "check", " ")
    val manifest = mapOf("validation_gate" to gate)
    assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
  }

  @Test
  fun `absent suppression_markers parse to empty ungated list`() {
    val gate = wellFormedGate().toMutableMap()
    gate.remove("suppression_markers")
    val manifest = mapOf("validation_gate" to gate)
    val parsed = parseValidationGate(manifest, "kotlin")
    requireNotNull(parsed)
    assertEquals(emptyList(), parsed.suppressionMarkers)
  }

  @Test
  fun `malformed suppression_markers loud-fails`() {
    val gate = wellFormedGate().toMutableMap()
    gate["suppression_markers"] = listOf("  ")
    val manifest = mapOf("validation_gate" to gate)
    assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
  }

  @Test
  fun `blank build_command loud-fails`() {
    val gate = wellFormedGate().toMutableMap()
    gate["build_command"] = listOf("./gradlew", " ")
    val manifest = mapOf("validation_gate" to gate)
    assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
  }

  @Test
  fun `build_command identical to collect_all_full_gate_command loud-fails`() {
    val gate = wellFormedGate().toMutableMap()
    gate["build_command"] = listOf("./gradlew", "check", "--continue")
    val manifest = mapOf("validation_gate" to gate)
    val error = assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
    assertEquals(
      "Platform pack 'kotlin': 'validation_gate.build_command' must not be byte-identical to " +
        "'validation_gate.collect_all_full_gate_command'.",
      error.message,
    )
  }

  @Test
  fun `cache_bypassing_build_command identical to cache_bypassing_collect_all loud-fails`() {
    val gate = wellFormedGate().toMutableMap()
    gate["cache_bypassing_build_command"] = listOf("./gradlew", "check", "--continue", "--rerun-tasks")
    val manifest = mapOf("validation_gate" to gate)
    val error = assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
    assertEquals(
      "Platform pack 'kotlin': 'validation_gate.cache_bypassing_build_command' must not be " +
        "byte-identical to 'validation_gate.cache_bypassing_collect_all_full_gate_command'.",
      error.message,
    )
  }

  @Test
  fun `malformed validation_gate loud-fails`() {
    val manifest = Yaml().load<Map<String, Any?>>(
      """
      validation_gate:
        full_gate_command: []
      """.trimIndent(),
    )
    assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
  }

  private fun wellFormedGateManifest(): Map<String, Any?> = mapOf("validation_gate" to wellFormedGate())

  private fun wellFormedGate(): Map<String, Any?> = mapOf(
    "full_gate_command" to listOf("./gradlew", "check"),
    "cache_bypassing_full_gate_command" to listOf("./gradlew", "check", "--rerun-tasks"),
    "collect_all_full_gate_command" to listOf("./gradlew", "check", "--continue"),
    "cache_bypassing_collect_all_full_gate_command" to listOf("./gradlew", "check", "--continue", "--rerun-tasks"),
    "build_command" to listOf("./gradlew", "compileKotlin"),
    "cache_bypassing_build_command" to listOf("./gradlew", "compileKotlin", "--no-build-cache"),
    "findings" to mapOf(
      "format" to "junit_xml",
      "artifact_globs" to listOf("**/build/test-results/**/*.xml"),
      "compiler_diagnostics" to mapOf("format" to "gradle_kotlin_compiler_stdout"),
      "executed_work" to mapOf("format" to "gradle_actionable_summary"),
    ),
    "suppression_markers" to listOf("@Suppress", "@file:Suppress"),
  )
}
