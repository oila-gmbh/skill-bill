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
    val manifest = mapOf(
      "validation_gate" to mapOf(
        "full_gate_command" to listOf("./gradlew", "check"),
        "cache_bypassing_full_gate_command" to listOf("./gradlew", "check", "--rerun-tasks"),
        "build_only_command" to listOf("./gradlew", "classes"),
        "findings" to mapOf(
          "format" to "junit_xml",
          "artifact_globs" to listOf("**/build/test-results/**/*.xml"),
          "executed_work" to mapOf("format" to "gradle_actionable_summary"),
        ),
        "suppression_markers" to listOf("@Suppress", "@file:Suppress"),
      ),
    )
    val gate = parseValidationGate(manifest, "kotlin")
    requireNotNull(gate)
    assertEquals(listOf("./gradlew", "check"), gate.fullGateCommand)
    assertEquals(listOf("./gradlew", "check", "--rerun-tasks"), gate.cacheBypassingFullGateCommand)
    assertEquals("junit_xml", gate.findings.format.wireValue)
    assertEquals(listOf("@Suppress", "@file:Suppress"), gate.suppressionMarkers)
  }

  @Test
  fun `absent suppression_markers parse to empty ungated list`() {
    val manifest = mapOf(
      "validation_gate" to mapOf(
        "full_gate_command" to listOf("./gradlew", "check"),
        "cache_bypassing_full_gate_command" to listOf("./gradlew", "check", "--rerun-tasks"),
        "build_only_command" to listOf("./gradlew", "classes"),
        "findings" to mapOf(
          "format" to "junit_xml",
          "artifact_globs" to listOf("**/build/test-results/**/*.xml"),
        ),
      ),
    )
    val gate = parseValidationGate(manifest, "kotlin")
    requireNotNull(gate)
    assertEquals(emptyList(), gate.suppressionMarkers)
  }

  @Test
  fun `malformed suppression_markers loud-fails`() {
    val manifest = mapOf(
      "validation_gate" to mapOf(
        "full_gate_command" to listOf("./gradlew", "check"),
        "cache_bypassing_full_gate_command" to listOf("./gradlew", "check", "--rerun-tasks"),
        "build_only_command" to listOf("./gradlew", "classes"),
        "findings" to mapOf(
          "format" to "junit_xml",
          "artifact_globs" to listOf("**/build/test-results/**/*.xml"),
        ),
        "suppression_markers" to listOf("  "),
      ),
    )
    assertFailsWith<InvalidValidationGateDeclarationError> {
      parseValidationGate(manifest, "kotlin")
    }
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
}
