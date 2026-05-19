package skillbill.nativeagent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKILL-48 Subtask 2c AC2: pins `contract_version` parity between the
 * canonical schema file
 * (`orchestration/contracts/native-agent-composition-schema.yaml`)
 * loaded from the classpath bundle and the runtime constant
 * [NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION]. Bumping one without the
 * other is a build break, by design.
 *
 * Mirrors `InstallPlanSchemaContractVersionTest` (runtime-domain) and
 * the workflow-state variant; lives in runtime-core because the
 * runtime-core build owns the canonical classpath bundle for this
 * schema.
 */
class NativeAgentCompositionSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION`() {
    val resourceStream = NativeAgentCompositionSchemaValidator::class.java.classLoader
      .getResourceAsStream(NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical native-agent composition schema is missing from the classpath at " +
        "'${NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyNativeAgentCompositionSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val schema: JsonNode = YAMLMapper().readTree(yamlText)
    val contractVersionNode = schema.path("\$defs").path("contractVersion").path("const")
    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin \$defs.contractVersion.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION " +
        "($NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches NativeAgentCompositionSchemaPaths EXPECTED_SCHEMA_ID`() {
    val resourceStream = NativeAgentCompositionSchemaValidator::class.java.classLoader
      .getResourceAsStream(NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical native-agent composition schema is missing from the classpath at " +
        "'${NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val schema: JsonNode = YAMLMapper().readTree(yamlText)
    val idNode = schema.path("\$id")
    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }
}
