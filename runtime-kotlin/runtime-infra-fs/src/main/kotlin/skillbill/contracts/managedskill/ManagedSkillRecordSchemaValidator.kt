package skillbill.contracts.managedskill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import skillbill.error.InvalidManagedSkillRecordSchemaError
import skillbill.managedskill.model.MANAGED_SKILL_RECORD_CONTRACT_VERSION

object ManagedSkillRecordSchemaValidator {
  private val mapper = ObjectMapper()
  private val schema by lazy {
    try {
      val resource = javaClass.getResourceAsStream(MANAGED_SKILL_RECORD_SCHEMA_RESOURCE)
        ?: throw InvalidManagedSkillRecordSchemaError("classpath schema", "schema resource is missing")
      val yaml = resource.use { YAMLMapper().readTree(it) }
      assertIdentity(yaml)
      val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(yaml, config)
    } catch (error: InvalidManagedSkillRecordSchemaError) {
      throw error
    } catch (error: Exception) {
      throw InvalidManagedSkillRecordSchemaError("classpath schema", "schema cannot be loaded or compiled", error)
    }
  }

  fun validate(record: Map<String, Any?>, sourceLabel: String) {
    val violations = schema.validate(mapper.valueToTree(record) as JsonNode)
    if (violations.isNotEmpty()) {
      throw InvalidManagedSkillRecordSchemaError(
        sourceLabel,
        violations.map { it.message }.sorted().joinToString("; "),
      )
    }
  }

  internal fun assertIdentity(schemaNode: JsonNode) {
    val id = schemaNode.path("\$id").asText()
    val version = schemaNode.path("properties").path("contract_version").path("const").asText()
    if (id != MANAGED_SKILL_RECORD_SCHEMA_ID || version != MANAGED_SKILL_RECORD_CONTRACT_VERSION) {
      throw InvalidManagedSkillRecordSchemaError(
        "classpath schema",
        "identity/version mismatch: id='$id', version='$version'",
      )
    }
  }
}
