package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalProgressEventSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches GOAL_PROGRESS_EVENT_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(GOAL_PROGRESS_EVENT_CONTRACT_VERSION, contractVersionNode.asText())
  }

  @Test
  fun `schema id matches GoalProgressEventSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    assertEquals(GoalProgressEventSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = GoalProgressEventSchemaValidator::class.java.classLoader
      .getResourceAsStream(GoalProgressEventSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical goal progress event schema is missing from the classpath at " +
        "'${GoalProgressEventSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
