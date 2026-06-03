package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches FEATURE_TASK_RUNTIME_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal FEATURE_TASK_RUNTIME_CONTRACT_VERSION " +
        "($FEATURE_TASK_RUNTIME_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches FeatureTaskRuntimePhaseOutputSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    val idNode = schema.path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
      .getResourceAsStream(FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical feature-task-runtime phase output schema is missing from the classpath at " +
        "'${FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyFeatureTaskRuntimePhaseOutputSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
