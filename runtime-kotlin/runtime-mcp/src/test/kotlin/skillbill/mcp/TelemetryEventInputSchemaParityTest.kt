package skillbill.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SKILL-48 Subtask 2d AC2 + AC4: walks every name in
 * `McpToolRegistry.tools` (the public source of truth for event names
 * — the underlying `toolNames` list is private) and asserts that the
 * corresponding entry in `telemetry-event-schema.yaml` is structurally
 * equivalent.
 *
 * Two-direction enforcement:
 *
 * 1. Every event in `McpToolRegistry.tools` has a `$defs.<event>Event`
 *    branch under the canonical schema, with matching `event_name`
 *    const and `additionalProperties` flag (true for open-object
 *    fallback events, false for strict events).
 * 2. Every `$defs.<event>Event` branch's `event_name.const` appears in
 *    `McpToolRegistry.tools`. A new branch in the YAML without a
 *    corresponding Kotlin event fails the build.
 *
 * For strict events (`additionalProperties: false`), the test also
 * walks the typed property keys (minus `event_name` / `contract_version`)
 * and asserts they line up with the Kotlin `inputSchema.properties`
 * keys. This is intentionally a structural keyset comparison and does
 * not assert per-property type equivalence — drift in a single field's
 * shape is caught by `TelemetryEventSchemaValidatesAllEventsTest`,
 * which validates a representative payload built from the Kotlin
 * shapes against the YAML schema.
 */
class TelemetryEventInputSchemaParityTest {

  private val schemaNode: JsonNode by lazy {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")
    YAMLMapper().readTree(Files.readString(schemaFile))
  }

  @Test
  fun `every McpToolRegistry tool has a matching schema branch`() {
    val defs = schemaNode.path("\$defs")
    assertTrue(defs.isObject, "Schema \$defs must be an object.")

    McpToolRegistry.tools.forEach { tool ->
      val branchName = branchNameFor(tool.name)
      val branch = defs.path(branchName)
      assertTrue(
        !branch.isMissingNode,
        "Telemetry parity: event '${tool.name}' has no branch '\$defs/$branchName' in the canonical schema. " +
          "Add a branch keyed on event_name='${tool.name}'.",
      )

      val branchEventName = branch.path("properties").path("event_name").path("const").asText("")
      assertEquals(
        tool.name,
        branchEventName,
        "Telemetry parity: branch '$branchName' must pin event_name.const to '${tool.name}', " +
          "found '$branchEventName'.",
      )

      val expectedAdditionalProps = expectedAdditionalPropertiesFor(tool.inputSchema)
      val actualAdditionalProps = additionalPropertiesFlag(branch)
      assertEquals(
        expectedAdditionalProps,
        actualAdditionalProps,
        "Telemetry parity: branch '$branchName' additionalProperties=$actualAdditionalProps " +
          "but McpToolRegistry inputSchema for event '${tool.name}' implies additionalProperties=" +
          "$expectedAdditionalProps.",
      )

      if (!expectedAdditionalProps) {
        // For strict events, the YAML branch MUST list the same
        // property keys as the Kotlin inputSchema (plus the envelope
        // keys `event_name` and `contract_version`).
        val kotlinKeys = inputSchemaPropertyKeys(tool.inputSchema)
        val yamlKeys = branchPropertyKeys(branch) - setOf("event_name", "contract_version")
        assertEquals(
          kotlinKeys,
          yamlKeys,
          "Telemetry parity: branch '$branchName' property keys do not match McpToolRegistry inputSchema. " +
            "kotlin=$kotlinKeys yaml=$yamlKeys",
        )

        // Required arrays MUST also line up (envelope keys excluded
        // because those are wire-envelope concerns, not payload
        // concerns).
        val kotlinRequired = inputSchemaRequiredKeys(tool.inputSchema)
        val yamlRequired = branchRequiredKeys(branch) - setOf("event_name", "contract_version")
        assertEquals(
          kotlinRequired,
          yamlRequired,
          "Telemetry parity: branch '$branchName' required[] does not match McpToolRegistry inputSchema. " +
            "kotlin=$kotlinRequired yaml=$yamlRequired",
        )
      }
    }
  }

  @Test
  fun `every schema event branch has a matching McpToolRegistry tool`() {
    val defs = schemaNode.path("\$defs")
    val knownEvents = McpToolRegistry.tools.map { it.name }.toSet()

    defs.fields().forEach { (defName, defNode) ->
      if (!defName.endsWith("Event")) {
        return@forEach
      }
      val branchEventName = defNode.path("properties").path("event_name").path("const").asText("")
      assertTrue(
        branchEventName in knownEvents,
        "Telemetry parity: branch '$defName' pins event_name='$branchEventName' which is not in " +
          "McpToolRegistry.tools. Remove the branch or add the event name to McpToolRegistry.toolNames.",
      )
    }
  }

  private fun branchNameFor(eventName: String): String {
    val parts = eventName.split('_')
    return parts.first() + parts.drop(1).joinToString("") { segment ->
      segment.replaceFirstChar { it.uppercase() }
    } + "Event"
  }

  @Suppress("UNCHECKED_CAST")
  private fun expectedAdditionalPropertiesFor(inputSchema: Map<String, Any?>): Boolean {
    // `McpToolSpec.openObjectSchema()` and `passthroughObjectSchema(...)`
    // both set `additionalProperties: true`. `McpToolSpec.strictObjectSchema(...)`
    // sets `additionalProperties: false`. Anything else is a programming error
    // we want to fail loudly on.
    val raw = inputSchema["additionalProperties"]
    return when (raw) {
      true -> true
      false -> false
      null -> {
        // `McpToolSpec.openObjectSchema()` historically returns
        // `additionalProperties=true`; missing is treated as open by
        // the spec. Anything that lands here is by construction the
        // open-object fallback (no `inputSchemas` entry).
        true
      }
      else -> fail(
        "Telemetry parity: inputSchema has non-boolean additionalProperties=$raw " +
          "(${raw::class.qualifiedName}); fix McpToolSpec.openObjectSchema/strictObjectSchema.",
      )
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun inputSchemaPropertyKeys(inputSchema: Map<String, Any?>): Set<String> {
    val properties = inputSchema["properties"] as? Map<String, Any?> ?: return emptySet()
    return properties.keys.toSet()
  }

  @Suppress("UNCHECKED_CAST")
  private fun inputSchemaRequiredKeys(inputSchema: Map<String, Any?>): Set<String> {
    val required = inputSchema["required"] as? List<String> ?: return emptySet()
    return required.toSet()
  }

  private fun additionalPropertiesFlag(branch: JsonNode): Boolean {
    val node = branch.path("additionalProperties")
    return when {
      node.isMissingNode -> true
      node.isBoolean -> node.asBoolean()
      else -> fail("Telemetry parity: branch additionalProperties is not a boolean (got $node).")
    }
  }

  private fun branchPropertyKeys(branch: JsonNode): Set<String> {
    val properties = branch.path("properties")
    if (properties.isMissingNode || !properties.isObject) return emptySet()
    return properties.fieldNames().asSequence().toSet()
  }

  private fun branchRequiredKeys(branch: JsonNode): Set<String> {
    val required = branch.path("required")
    if (required.isMissingNode || !required.isArray) return emptySet()
    return required.elements().asSequence().map { it.asText() }.toSet()
  }
}
