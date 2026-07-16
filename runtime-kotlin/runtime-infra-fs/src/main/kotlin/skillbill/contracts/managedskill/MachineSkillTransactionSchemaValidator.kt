package skillbill.contracts.managedskill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.error.InvalidMachineSkillTransactionSchemaError

object MachineSkillTransactionSchemaValidator {
  private val mapper = YAMLMapper()
  private val schema by lazy {
    val stream = javaClass.getResourceAsStream(MACHINE_SKILL_TRANSACTION_SCHEMA_RESOURCE)
      ?: throw InvalidMachineSkillTransactionSchemaError("Transaction journal schema resource is missing")
    stream.use { JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(mapper.readTree(it)) }
  }

  fun validate(node: JsonNode, source: String) {
    val errors = schema.validate(node)
    if (errors.isNotEmpty()) {
      throw InvalidMachineSkillTransactionSchemaError(
        "Invalid transaction journal at $source: ${errors.joinToString()}",
      )
    }
  }
}
