package skillbill.agentaddon

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.contracts.agentaddon.AGENT_ADDON_CONTRACT_VERSION
import skillbill.contracts.agentaddon.AgentAddonSchemaPaths
import skillbill.error.InvalidAgentAddonSchemaError

fun interface AgentAddonSchemaResourceLoader {
  fun read(): String
}

class AgentAddonSchemaValidator(
  private val resourceLoader: AgentAddonSchemaResourceLoader = AgentAddonSchemaResourceLoader {
    AgentAddonSchemaValidator::class.java.classLoader
      .getResourceAsStream(AgentAddonSchemaPaths.CLASSPATH_RESOURCE)
      ?.bufferedReader()
      ?.use { it.readText() }
      ?: throw InvalidAgentAddonSchemaError(
        AgentAddonSchemaPaths.CLASSPATH_RESOURCE,
        "canonical schema resource is missing",
      )
  },
) {
  private val mapper: ObjectMapper = ObjectMapper()
  private val schema: JsonSchema by lazy { loadSchema() }

  fun validate(manifest: Map<String, Any?>, sourceLabel: String) =
    schemaOperation(sourceLabel, "schema validation failed") {
      val errors = schema.validate(mapper.valueToTree(manifest))
      if (errors.isNotEmpty()) {
        val reason = errors.sortedBy { it.instanceLocation.toString() }.joinToString("; ") { it.message }
        invalidSchema(sourceLabel, reason)
      }
    }

  private fun loadSchema(): JsonSchema =
    schemaOperation(AgentAddonSchemaPaths.CLASSPATH_RESOURCE, "canonical schema cannot be loaded") {
      val source = AgentAddonSchemaPaths.CLASSPATH_RESOURCE
      val node: JsonNode = YAMLMapper().readTree(resourceLoader.read())
      if (node.path("\$id").asText() != AgentAddonSchemaPaths.EXPECTED_SCHEMA_ID) {
        invalidSchema(
          source,
          "canonical schema \$id does not match ${AgentAddonSchemaPaths.EXPECTED_SCHEMA_ID}",
        )
      }
      val version = node.path("properties").path("contract_version").path("const").asText()
      if (version != AGENT_ADDON_CONTRACT_VERSION) {
        invalidSchema(
          source,
          "schema contract version '$version' does not match '$AGENT_ADDON_CONTRACT_VERSION'",
        )
      }
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node)
    }
}

private inline fun <T> schemaOperation(sourceLabel: String, fallbackReason: String, operation: () -> T): T =
  runCatching(operation).getOrElse { error ->
    throw error.asAgentAddonSchemaError(sourceLabel, fallbackReason)
  }

private fun Throwable.asAgentAddonSchemaError(sourceLabel: String, fallbackReason: String): Throwable = when (this) {
  is InvalidAgentAddonSchemaError -> this
  is Exception -> InvalidAgentAddonSchemaError(sourceLabel, message ?: fallbackReason, this)
  else -> this
}

private fun invalidSchema(sourceLabel: String, reason: String): Nothing =
  throw InvalidAgentAddonSchemaError(sourceLabel, reason)
