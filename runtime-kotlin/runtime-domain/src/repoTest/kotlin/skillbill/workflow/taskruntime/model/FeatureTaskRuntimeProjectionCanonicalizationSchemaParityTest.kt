package skillbill.workflow.taskruntime.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-152 subtask 1 (AC-005): the canonicalizer discards unknown keys on the nested fully-enumerated
 * closed projection objects, which is safe only while its declared key sets match the schema exactly. A
 * property added to the schema but not mirrored here would be silently discarded as "unknown" — the schema
 * would then never see the field it requires. This test makes that drift a build failure instead.
 */
class FeatureTaskRuntimeProjectionCanonicalizationSchemaParityTest {
  private val schema: JsonNode by lazy {
    YAMLMapper().readTree(Files.readString(planningProjectionsSchemaPath()))
  }

  @Test
  fun `every declared key set equals its schema properties`() {
    FEATURE_TASK_RUNTIME_CLOSED_PROJECTION_OBJECT_KEYS.forEach { (schemaLocation, declaredKeys) ->
      val node = resolveSchemaObject(schemaLocation)
      val schemaKeys = node.path("properties").fieldNames().asSequence().toSet()

      assertEquals(
        schemaKeys,
        declaredKeys,
        "Declared key set for '$schemaLocation' drifted from the schema. Mirror the schema's properties " +
          "in FEATURE_TASK_RUNTIME_CLOSED_PROJECTION_OBJECT_KEYS, or the canonicalizer will discard a " +
          "governed field as unknown.",
      )
    }
  }

  @Test
  fun `every pruned object is closed in the schema`() {
    // Discarding unknown keys is only justified where the schema itself forbids them: on an open object an
    // unknown key may be legitimate and must survive.
    FEATURE_TASK_RUNTIME_CLOSED_PROJECTION_OBJECT_KEYS.keys.forEach { schemaLocation ->
      val node = resolveSchemaObject(schemaLocation)
      assertTrue(
        node.path("additionalProperties").isBoolean && !node.path("additionalProperties").asBoolean(),
        "'$schemaLocation' must declare additionalProperties:false to be eligible for the unknown-key discard",
      )
    }
  }

  // A key is either a `$defs` name or a `<variant>.<property>` path for an object declared inline.
  private fun resolveSchemaObject(schemaLocation: String): JsonNode {
    val defs = schema.path("\$defs")
    val segments = schemaLocation.split('.')
    val root = defs.path(segments.first())
    assertTrue(!root.isMissingNode, "schema \$defs has no entry named '${segments.first()}'")
    val resolved = if (segments.size == 1) root else root.path("properties").path(segments[1])
    assertTrue(!resolved.isMissingNode, "schema has no object at '$schemaLocation'")
    return resolved
  }
}

private fun planningProjectionsSchemaPath(): Path {
  val relative = "orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml"
  var current: Path? = Path.of("").toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(relative)
    if (Files.isRegularFile(candidate)) return candidate
    current = current.parent
  }
  error("Could not locate '$relative' from ${Path.of("").toAbsolutePath().normalize()}")
}
