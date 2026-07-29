package skillbill.contracts.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeAdaptivePolicySchemaContractVersionTest {
  @Test
  fun `adaptive policy schema version and id match runtime constants`() {
    val stream = javaClass.classLoader.getResourceAsStream(
      FeatureTaskRuntimeAdaptivePolicySchemaPaths.CLASSPATH_RESOURCE,
    )
    assertNotNull(stream)
    val schema = YAMLMapper().readTree(stream)

    assertEquals(
      FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(FeatureTaskRuntimeAdaptivePolicySchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }
}
