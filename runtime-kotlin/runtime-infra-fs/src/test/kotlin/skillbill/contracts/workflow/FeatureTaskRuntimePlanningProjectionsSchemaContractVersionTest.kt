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
  fun `schema rejects every payload via empty oneOf`() {
    val oneOf = classpathSchema().path("oneOf")

    assertTrue(oneOf.isArray && oneOf.isEmpty, "Schema must declare an empty oneOf reject-all root; found: $oneOf")
  }

  @Test
  fun `legacy implementation_receipt payload fails the reject-all root`() {
    val validator = FeatureTaskRuntimePlanningProjectionSchemaValidator
    val error = runCatching {
      validator.validate(
        mapOf(
          "projection_kind" to "implementation_receipt",
          "contract_version" to "0.2",
          "completed_task_ids" to listOf("task-1"),
          "changed_paths" to listOf("path/X.kt"),
          "tests_executed" to emptyList<Any>(),
          "reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "ok"),
        ),
        "fixture#produced_outputs",
      )
    }.exceptionOrNull()
    assertNotNull(error, "legacy implementation_receipt must fail the quarantined schema")
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
