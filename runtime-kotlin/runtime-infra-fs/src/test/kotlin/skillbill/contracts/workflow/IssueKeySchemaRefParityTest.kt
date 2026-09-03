package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.issuekey.ISSUE_KEY_SCHEMA_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IssueKeySchemaRefParityTest {
  @Test
  fun `consumer schemas reuse the canonical issue-key schema by ref`() {
    assertEquals(ISSUE_KEY_SCHEMA_ID, refAt(executionIdentity(), "properties", "normalized_issue_key"))
    assertEquals(
      ISSUE_KEY_SCHEMA_ID,
      refAt(planningPreparation(), "\$defs", "identity", "properties", "normalized_issue_key"),
    )
    assertEquals(
      ISSUE_KEY_SCHEMA_ID,
      refAt(checkpointIdentity(), "\$defs", "checkpoint_identity", "properties", "issue_key"),
    )
  }

  private fun executionIdentity() = load(FeatureTaskExecutionIdentitySchemaPaths.CLASSPATH_RESOURCE)

  private fun planningPreparation() = load(GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE)

  private fun checkpointIdentity() = load(FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE)

  private fun load(resource: String) = assertNotNull(
    javaClass.classLoader.getResourceAsStream(resource),
    resource,
  ).use { YAMLMapper().readTree(it) }

  private fun refAt(root: JsonNode, vararg path: String): String {
    var node = root
    path.forEach { segment -> node = node.path(segment) }
    return node.path("\$ref").asText()
  }
}
