package skillbill.contracts.team

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeamBundleSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches TEAM_BUNDLE_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(TEAM_BUNDLE_CONTRACT_VERSION, contractVersionNode.asText())
  }

  @Test
  fun `schema id matches TeamBundleSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()

    assertEquals(TeamBundleSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  @Test
  fun `schema is present on the classpath`() {
    assertNotNull(
      TeamBundleSchemaValidator::class.java.classLoader
        .getResourceAsStream(TeamBundleSchemaPaths.CLASSPATH_RESOURCE),
      "Canonical team-bundle schema is missing from the classpath.",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = TeamBundleSchemaValidator::class.java.classLoader
      .getResourceAsStream(TeamBundleSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(resourceStream, "Schema missing from classpath at '${TeamBundleSchemaPaths.CLASSPATH_RESOURCE}'.")
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
