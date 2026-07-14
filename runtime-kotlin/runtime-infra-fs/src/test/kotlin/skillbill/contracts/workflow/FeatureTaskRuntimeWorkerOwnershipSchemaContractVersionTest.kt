package skillbill.contracts.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeWorkerOwnershipSchemaContractVersionTest {
  @Test
  fun `worker ownership schema version and id match runtime constants`() {
    val stream = assertNotNull(
      javaClass.classLoader.getResourceAsStream(FeatureTaskRuntimeWorkerOwnershipSchemaPaths.CLASSPATH_RESOURCE),
    )
    val schema = stream.use { YAMLMapper().readTree(it) }
    assertEquals(
      FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(FeatureTaskRuntimeWorkerOwnershipSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }
}
