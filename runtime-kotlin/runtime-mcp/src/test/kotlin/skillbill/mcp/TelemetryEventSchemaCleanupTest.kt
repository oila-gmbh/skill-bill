package skillbill.mcp

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.InvalidTelemetryEventSchemaError
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * SKILL-48 Subtask 2d: classpath-shadow guard for the canonical
 * telemetry-event schema. Mirrors the install-plan / workflow-state /
 * native-agent-composition classpath-shadow guards that live in
 * `runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaCleanupTest.kt`.
 *
 * NOTE on placement: the original plan called for EXTENDING the
 * `PlatformPackSchemaCleanupTest` file with these cases, but that file
 * lives in `runtime-core` which cannot depend on `runtime-mcp` (the
 * dependency direction is `runtime-mcp -> runtime-core`). The
 * telemetry validator + paths live in `runtime-mcp` alongside
 * `McpToolRegistry` so the only way to drive the assertions is from
 * a runtime-mcp test class. This file is the sibling that keeps the
 * loud-fail surface intact without inverting the module graph.
 */
class TelemetryEventSchemaCleanupTest {

  @Test
  fun `telemetry-event schema classpath shadow with mismatched id loud-fails`() {
    val mismatchedIdYaml = """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-telemetry-event.yaml"
      type: object
      properties:
        contract_version:
          const: "$TELEMETRY_EVENT_CONTRACT_VERSION"
    """.trimIndent()

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.assertIdentity(mismatchedIdYaml)
    }
    val reason = error.reason
    assertContains(reason, "https://malicious.example/shadow-telemetry-event.yaml")
    assertContains(reason, TelemetryEventSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `telemetry-event schema classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml = """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${TelemetryEventSchemaPaths.EXPECTED_SCHEMA_ID}"
      type: object
      properties:
        contract_version:
          const: "9.99"
    """.trimIndent()

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.assertIdentity(mismatchedConstYaml)
    }
    val reason = error.reason
    assertContains(reason, "9.99")
    assertContains(reason, TELEMETRY_EVENT_CONTRACT_VERSION)
  }

  @Test
  fun `telemetry-event canonical schema on disk passes identity assertion`() {
    val schemaPath: Path = repoRootFromTest()
      .resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    val yamlText = Files.readString(schemaPath)
    val node = YAMLMapper().readTree(yamlText)
    // Must not throw.
    TelemetryEventSchemaValidator.assertIdentity(node)
  }
}
