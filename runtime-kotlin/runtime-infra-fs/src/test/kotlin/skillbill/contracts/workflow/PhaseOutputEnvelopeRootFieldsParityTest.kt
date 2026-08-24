package skillbill.contracts.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The demote pass treats any root key outside `ENVELOPE_ROOT_FIELDS` as a `produced_outputs` member
 * placed one level too high. That inference is only safe while the set names every root field the
 * schema declares: a new envelope field missing from it would be demoted into `produced_outputs`
 * instead of read, so the drift has to fail here rather than in a run.
 */
class PhaseOutputEnvelopeRootFieldsParityTest {
  @Test
  fun `ENVELOPE_ROOT_FIELDS names exactly the schema's declared root properties`() {
    val resourceStream = FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
      .getResourceAsStream(FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical phase output schema is missing from the classpath at " +
        "'${FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val schema = YAMLMapper().readTree(resourceStream.use { it.readBytes().toString(Charsets.UTF_8) })

    val declared = schema.path("properties").fieldNames().asSequence().toSortedSet()

    assertEquals(
      declared.toList(),
      PhaseOutputExpectedShape.ENVELOPE_ROOT_FIELDS.toSortedSet().toList(),
      "The schema's root properties and ENVELOPE_ROOT_FIELDS have drifted. Add the new root field " +
        "to ENVELOPE_ROOT_FIELDS, or it will be demoted into produced_outputs as a stray key.",
    )
  }

  @Test
  fun `the closed root is what makes a stray key unambiguous`() {
    val schema = FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
      .getResourceAsStream(FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE)
      .let { stream -> YAMLMapper().readTree(requireNotNull(stream).use { it.readBytes().toString(Charsets.UTF_8) }) }

    assertEquals(
      false,
      schema.path("additionalProperties").asBoolean(true),
      "If the envelope root ever accepts additional properties, a stray key is no longer " +
        "necessarily misplaced and the demote pass must be reconsidered.",
    )
  }
}
