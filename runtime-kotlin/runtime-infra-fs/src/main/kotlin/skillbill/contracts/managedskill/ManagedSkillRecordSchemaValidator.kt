package skillbill.contracts.managedskill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.error.InvalidManagedSkillRecordSchemaError
import skillbill.managedskill.model.MANAGED_SKILL_RECORD_CONTRACT_VERSION

object ManagedSkillRecordSchemaValidator {
  private val mapper = ObjectMapper()
  private val schema by lazy {
    val resource = checkNotNull(javaClass.getResourceAsStream(MANAGED_SKILL_RECORD_SCHEMA_RESOURCE)) {
      "Missing managed-skill record schema resource."
    }
    val yaml = YAMLMapper().readTree(resource)
    assertIdentity(yaml)
    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(yaml)
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

