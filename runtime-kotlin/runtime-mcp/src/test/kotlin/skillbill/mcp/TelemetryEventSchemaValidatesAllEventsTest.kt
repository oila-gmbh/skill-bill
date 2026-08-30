package skillbill.mcp

import skillbill.mcp.core.McpToolRegistry
import skillbill.mcp.telemetry.TELEMETRY_EVENT_CONTRACT_VERSION
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SKILL-48 Subtask 2d AC5: for every event in `McpToolRegistry.tools`,
 * builds a representative envelope (event_name + contract_version +
 * minimal valid payload synthesized from the Kotlin `inputSchema`) and
 * asserts it validates clean against the canonical schema. The set of
 * events is discovered dynamically — no hard-coded count — so adding a
 * new event in Kotlin without authoring the YAML branch fails the
 * build automatically.
 *
 * Mirrors `InstallPlanSchemaValidatesExistingPlansTest` (Subtask 2b)
 * and `WorkflowStateSchemaValidatesExistingWorkflowsTest` (Subtask 2a).
 */
class TelemetryEventSchemaValidatesAllEventsTest {

  @Test
  fun `every McpToolRegistry tool emits a schema-clean representative envelope`() {
    // Discover dynamically so adding a new event to McpToolRegistry
    // surfaces here without a hard-coded count change.
    val events = McpToolRegistry.tools
    assertTrue(events.isNotEmpty(), "McpToolRegistry.tools must not be empty.")

    events.forEach { tool ->
      val envelope = buildRepresentativeEnvelope(tool.name, tool.inputSchema)
      // `validate` throws on any violation; if it returns, the
      // representative payload is clean. The `eventName` arg is passed
      // a-priori so a future bug that drops `event_name` from the
      // envelope is still surfaced with the right name.
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = tool.name)
    }
  }

  /**
   * Builds a minimal valid envelope for `eventName`. The envelope
   * always carries `event_name` (the discriminator) and
   * `contract_version` (pinned). For strict events
   * (`additionalProperties: false`), every required Kotlin
   * `inputSchema.required[]` field is supplied with a type-correct
   * default (string `""`, integer `0`, boolean `false`, array `[]`,
   * etc.); enum-typed fields use the first allowed value. For open
   * events (`additionalProperties: true`), only the envelope keys are
   * required.
   */
  private fun buildRepresentativeEnvelope(eventName: String, inputSchema: Map<String, Any?>): Map<String, Any?> {
    val envelope = linkedMapOf<String, Any?>(
      "event_name" to eventName,
      "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
    )

    val required = (inputSchema["required"] as? List<*>)
      ?.mapNotNull { entry -> entry as? String }
      .orEmpty()
    val properties = (inputSchema["properties"] as? Map<*, *>)
      ?.mapNotNull { (key, value) ->
        val stringKey = key as? String ?: return@mapNotNull null
        val fieldSchema = value as? Map<*, *> ?: return@mapNotNull null
        stringKey to fieldSchema.entries
          .mapNotNull { (fieldKey, fieldValue) ->
            (fieldKey as? String)?.let { typedKey -> typedKey to fieldValue }
          }
          .toMap()
      }
      ?.toMap()
      .orEmpty()
    required.forEach { fieldName ->
      val fieldSchema = properties[fieldName] ?: mapOf("type" to "string")
      envelope[fieldName] = representativeValue(fieldSchema)
    }
    return envelope
  }

  private fun representativeValue(fieldSchema: Map<String, Any?>): Any? {
    val type = fieldSchema["type"] as? String ?: "object"
    return when (type) {
      "string" -> representativeString(fieldSchema)
      "integer" -> representativeInteger(fieldSchema)
      "number" -> 0
      "boolean" -> false
      "array" -> emptyList<Any?>()
      "object" -> emptyMap<String, Any?>()
      else -> ""
    }
  }
  private fun representativeString(fieldSchema: Map<String, Any?>): String {
    val enum = fieldSchema["enum"] as? List<*>
    if (enum != null && enum.isNotEmpty()) return enum.first().toString()
    val minLength = (fieldSchema["minLength"] as? Number)?.toInt()
    return if (minLength != null && minLength > 0) "x" else ""
  }

  private fun representativeInteger(fieldSchema: Map<String, Any?>): Int {
    val minimum = (fieldSchema["minimum"] as? Number)?.toInt()
    return if (minimum != null && minimum > 0) minimum else 0
  }
}
