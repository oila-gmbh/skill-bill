package skillbill.contracts.managedskill

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.managedskill.model.MANAGED_SKILL_RECORD_CONTRACT_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagedSkillRecordSchemaContractVersionTest {
  @Test
  fun `canonical schema version matches Kotlin contract`() {
    val stream = checkNotNull(javaClass.getResourceAsStream(MANAGED_SKILL_RECORD_SCHEMA_RESOURCE))
    val schema = stream.use { YAMLMapper().readTree(it) }
    assertEquals(
      MANAGED_SKILL_RECORD_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    ManagedSkillRecordSchemaValidator.assertIdentity(schema)
  }
}
