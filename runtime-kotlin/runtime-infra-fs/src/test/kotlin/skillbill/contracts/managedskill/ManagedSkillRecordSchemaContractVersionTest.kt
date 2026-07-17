package skillbill.contracts.managedskill

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.InvalidManagedSkillRecordSchemaError
import skillbill.managedskill.model.MANAGED_SKILL_RECORD_CONTRACT_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

  @Test
  fun `date time formats are assertions`() {
    val record = linkedMapOf<String, Any?>(
      "contract_version" to "0.1",
      "name" to "sample-skill",
      "source_kind" to "directory",
      "source_path" to "/tmp/source",
      "active_content_hash" to "a".repeat(64),
      "selected_targets" to listOf(mapOf("provider" to "codex", "skills_path" to "/tmp/codex")),
      "imported_at" to "not-a-date-time",
      "updated_at" to "2026-07-16T12:00:00Z",
    )

    assertFailsWith<InvalidManagedSkillRecordSchemaError> {
      ManagedSkillRecordSchemaValidator.validate(record, "test record")
    }
  }
}
