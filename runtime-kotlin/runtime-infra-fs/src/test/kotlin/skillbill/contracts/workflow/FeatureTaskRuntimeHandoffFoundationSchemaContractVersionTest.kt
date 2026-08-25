package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeHandoffFoundationSchemaContractVersionTest {
  @Test
  fun `phase handoff schema id version and classpath resource match Kotlin pins`() {
    assertSchemaPin(
      FeatureTaskRuntimePhaseHandoffSchemaPaths.CLASSPATH_RESOURCE,
      FeatureTaskRuntimePhaseHandoffSchemaPaths.EXPECTED_SCHEMA_ID,
      FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION,
    )
  }

  @Test
  fun `persistence schema id version and classpath resource match Kotlin pins`() {
    val schema = classpathSchema(FeatureTaskRuntimePersistenceSchemaPaths.CLASSPATH_RESOURCE)
    assertEquals(FeatureTaskRuntimePersistenceSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    val versions = schema.path("\$defs").let { defs ->
      listOf(
        defs.path("private_phase_record"),
        defs.path("delivered_projection"),
      )
    }.map { it.path("properties").path("contract_version").path("const").asText() }
    assertEquals(
      listOf(
        FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
        FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      ),
      versions,
    )
  }

  @Test
  fun `projection measurement schema id version and classpath resource match Kotlin pins`() {
    assertSchemaPin(
      FeatureTaskRuntimeProjectionMeasurementSchemaPaths.CLASSPATH_RESOURCE,
      FeatureTaskRuntimeProjectionMeasurementSchemaPaths.EXPECTED_SCHEMA_ID,
      FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION,
    )
  }

  @Test
  fun `shared evidence projection schema id version and classpath resource match Kotlin pins`() {
    assertSchemaPin(
      FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.CLASSPATH_RESOURCE,
      FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.EXPECTED_SCHEMA_ID,
      FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION,
    )
  }

  private fun assertSchemaPin(resource: String, expectedId: String, expectedVersion: String) {
    val schema = classpathSchema(resource)
    assertEquals(expectedId, schema.path("\$id").asText())
    assertEquals(expectedVersion, schema.path("properties").path("contract_version").path("const").asText())
  }

  private fun classpathSchema(resource: String): JsonNode {
    val stream = javaClass.classLoader.getResourceAsStream(resource)
    assertNotNull(stream, "Canonical SKILL-146 schema is missing from classpath at '$resource'.")
    return YAMLMapper().readTree(stream.use { it.readBytes().toString(Charsets.UTF_8) })
  }
}
