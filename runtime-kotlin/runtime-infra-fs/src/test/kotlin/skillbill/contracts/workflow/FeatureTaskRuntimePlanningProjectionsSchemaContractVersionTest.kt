package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionsSchemaContractVersionTest {
  @Test
  fun `schema contractVersion const matches FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION`() {
    val contractVersionNode = classpathSchema().path("\$defs").path("contractVersion").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin \$defs.contractVersion.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema \$defs.contractVersion.const must equal " +
        "FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION.",
    )
  }

  @Test
  fun `schema id matches FeatureTaskRuntimePlanningProjectionsSchemaPaths EXPECTED_SCHEMA_ID`() {
    val idNode = classpathSchema().path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      FeatureTaskRuntimePlanningProjectionsSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal FeatureTaskRuntimePlanningProjectionsSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  @Test
  fun `schema declares oneOf over the four concrete projection kinds`() {
    val oneOf = classpathSchema().path("oneOf")

    assertTrue(oneOf.isArray && oneOf.size() == 4, "Schema must declare exactly four oneOf variants; found: $oneOf")
    val refs = oneOf.map { it.path("\$ref").asText() }
    assertEquals(
      listOf(
        "#/\$defs/preplanning_digest",
        "#/\$defs/executable_plan",
        "#/\$defs/plan_commitment",
        "#/\$defs/implementation_receipt",
      ),
      refs,
      "Schema oneOf variants must reference the four concrete projection kinds in order.",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream = FeatureTaskRuntimePlanningProjectionSchemaValidator::class.java.classLoader
      .getResourceAsStream(FeatureTaskRuntimePlanningProjectionsSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical feature-task-runtime planning-projections schema is missing from the classpath at " +
        "'${FeatureTaskRuntimePlanningProjectionsSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyFeatureTaskRuntimePlanningProjectionsSchema` ran before this test.",
    )
    return YAMLMapper().readTree(resourceStream.use { it.readBytes().toString(Charsets.UTF_8) })
  }
}
