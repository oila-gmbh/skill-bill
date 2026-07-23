package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeHandoffEnvelopeSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION`() {
    val contractVersionNode = classpathSchema().path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION.",
    )
  }

  @Test
  fun `schema id matches FeatureTaskRuntimeHandoffEnvelopeSchemaPaths EXPECTED_SCHEMA_ID`() {
    val idNode = classpathSchema().path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = FeatureTaskRuntimeHandoffEnvelopeSchemaValidator::class.java.classLoader
      .getResourceAsStream(FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical feature-task-runtime handoff envelope schema is missing from the classpath at " +
        "'${FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyFeatureTaskRuntimeHandoffEnvelopeSchema` ran before this test.",
    )
    return YAMLMapper().readTree(resourceStream.use { it.readBytes().toString(Charsets.UTF_8) })
  }
}
