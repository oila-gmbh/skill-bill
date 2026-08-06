package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdeStatusSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches IDE_STATUS_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(IDE_STATUS_CONTRACT_VERSION, contractVersionNode.asText())
  }

  @Test
  fun `schema id matches IdeStatusSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    assertEquals(IdeStatusSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = IdeStatusSchemaValidator::class.java.classLoader
      .getResourceAsStream(IdeStatusSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical IDE status schema is missing from the classpath at " +
        "'${IdeStatusSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
