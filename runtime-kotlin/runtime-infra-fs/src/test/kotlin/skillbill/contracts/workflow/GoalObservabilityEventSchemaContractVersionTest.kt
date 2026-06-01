package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalObservabilityEventSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION, contractVersionNode.asText())
  }

  @Test
  fun `schema id matches GoalObservabilityEventSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    assertEquals(GoalObservabilityEventSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = GoalObservabilityEventSchemaValidator::class.java.classLoader
      .getResourceAsStream(GoalObservabilityEventSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical goal-observability event schema is missing from the classpath at " +
        "'${GoalObservabilityEventSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
