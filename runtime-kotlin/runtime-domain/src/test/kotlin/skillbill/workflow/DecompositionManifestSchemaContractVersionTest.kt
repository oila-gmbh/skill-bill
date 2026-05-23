package skillbill.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecompositionManifestSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches DECOMPOSITION_MANIFEST_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      DECOMPOSITION_MANIFEST_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal DECOMPOSITION_MANIFEST_CONTRACT_VERSION " +
        "($DECOMPOSITION_MANIFEST_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches DecompositionManifestSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    val idNode = schema.path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      DecompositionManifestSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal DecompositionManifestSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = DecompositionManifestSchemaValidator::class.java.classLoader
      .getResourceAsStream(DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical decomposition manifest schema is missing from the classpath at " +
        "'${DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyDecompositionManifestSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
