package skillbill.application

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Planning-projections schema is reject-all; [producedProjectionKindFor] is null for every phase, so
 * the producing-phase corpus is empty and every canned phase is named in the exemption list.
 */
class PhaseOutputFixtureParityTest {
  private data class PhaseOutputFixture(
    val id: String,
    val phaseId: String,
    val producedOutputs: String,
  )

  private val planningProjectionFixtures: List<PhaseOutputFixture> = emptyList()

  private val planningProjectionExemptPhases: Set<String> =
    setOf(
      "preplan",
      "plan",
      "implement",
      "review",
      "audit",
      "verify_findings",
      "implement_fix",
      "commit_push",
    )

  @Test
  fun `every producing-phase fixture validates cleanly against the planning-projections schema`() {
    planningProjectionFixtures.forEach { fixture ->
      try {
        realPlanningProjectionValidator.validatePlanningProjection(parsedOutputs(fixture.producedOutputs), fixture.id)
      } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
        fail("fixture '${fixture.id}' (${fixture.phaseId}) must validate cleanly but failed: ${error.message}")
      }
    }
  }

  @Test
  fun `the enumerated corpus and the exemption list together cover every canned phase`() {
    val validated = planningProjectionFixtures.map { it.phaseId }.toSet()
    assertEquals(
      emptySet(),
      validated,
      "the producing-phase corpus must stay empty while planning projections are reject-all",
    )
    assertEquals(
      setOf(
        "preplan",
        "plan",
        "implement",
        "review",
        "audit",
        "verify_findings",
        "implement_fix",
        "commit_push",
      ),
      planningProjectionExemptPhases,
      "the exemption list drifted; every non-producing phase must be named and justified, never skipped",
    )
  }
  private fun parsedOutputs(producedOutputs: String): Map<String, Any?> {
    val json = requireNotNull(JsonSupport.parseObjectOrNull(producedOutputs)) {
      "fixture produced_outputs must be a JSON object"
    }
    return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(json)))
  }
}
