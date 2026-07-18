package skillbill.contracts.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeAuditRepairPlanSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches audit repair runtime constant`() {
    val stream = checkNotNull(
      javaClass.classLoader.getResourceAsStream(
        FeatureTaskRuntimeAuditRepairPlanSchemaPaths.CLASSPATH_RESOURCE,
      ),
    )
    val version = stream.use { YAMLMapper().readTree(it) }
      .path("properties").path("contract_version").path("const").asText()

    assertEquals(FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION, version)
    assertEquals(AUDIT_REPAIR_CONTRACT_VERSION, version)
  }
}
