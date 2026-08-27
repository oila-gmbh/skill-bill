package skillbill.application

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * SKILL-140 Subtask 3 (AC-002): every canned planning-projection fixture is validated against the
 * canonical planning-projections schema with the real infra-fs validator, so fixture drift fails the
 * build instead of hiding behind the Noop stand-in used by most runner suites. The two mutation-check
 * tests below permanently encode the manual mutation check the plan describes: an undeclared key and a
 * task_id pattern violation each fail with a message naming the fixture and the violation.
 *
 * Scope: the planning-projections schema governs implement produced_outputs bodies.
 * preplan and plan own PhaseOutput prose and are named in [PLANNING_PROJECTION_EXEMPT_PHASES] with
 * review, audit, and commit_push rather than silently skipped.
 */
class PhaseOutputFixtureParityTest {

  @Test
  fun `every producing-phase fixture validates cleanly against the planning-projections schema`() {
    PLANNING_PROJECTION_FIXTURES.forEach { fixture ->
      try {
        realPlanningProjectionValidator.validatePlanningProjection(parsedOutputs(fixture.producedOutputs), fixture.id)
      } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
        fail("fixture '${fixture.id}' (${fixture.phaseId}) must validate cleanly but failed: ${error.message}")
      }
    }
  }

  @Test
  fun `the enumerated corpus and the exemption list together cover every canned phase`() {
    val validated = PLANNING_PROJECTION_FIXTURES.map { it.phaseId }.toSet()
    assertEquals(
      setOf("implement"),
      validated,
      "the producing-phase corpus drifted from the fixtures the planning-projections schema governs",
    )
    assertEquals(
      setOf("preplan", "plan", "review", "audit", "verify_findings", "implement_fix", "commit_push"),
      PLANNING_PROJECTION_EXEMPT_PHASES,
      "the exemption list drifted; every non-producing phase must be named and justified, never skipped",
    )
  }

  @Test
  fun `an undeclared key injected into an implement fixture fails naming the fixture and the violation`() {
    val fixtureId = "implement-mutation-check"
    val corrupted = validProducedOutputs("implement").replaceFirst(
      "\"projection_kind\":\"implementation_receipt\",",
      "\"projection_kind\":\"implementation_receipt\",\"undeclared_key\":\"drift\",",
    )

    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      realPlanningProjectionValidator.validatePlanningProjection(parsedOutputs(corrupted), fixtureId)
    }

    assertContains(error.message.orEmpty(), fixtureId)
    assertContains(error.message.orEmpty(), "undeclared_key")
  }

  @Test
  fun `a completed_task_ids pattern violation in an implement fixture names fixture and field`() {
    val fixtureId = "implement-task-id-mutation-check"
    val corrupted = validProducedOutputs("implement").replaceFirst(
      "\"completed_task_ids\":[\"task-1\"]",
      "\"completed_task_ids\":[\"Task_1\"]",
    )

    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      realPlanningProjectionValidator.validatePlanningProjection(parsedOutputs(corrupted), fixtureId)
    }

    assertContains(error.message.orEmpty(), fixtureId)
    assertContains(error.message.orEmpty(), "completed_task_ids")
  }

  @Suppress("UNCHECKED_CAST")
  private fun parsedOutputs(producedOutputs: String): Map<String, Any?> {
    val json = requireNotNull(JsonSupport.parseObjectOrNull(producedOutputs)) {
      "fixture produced_outputs must be a JSON object"
    }
    return JsonSupport.jsonElementToValue(json) as Map<String, Any?>
  }
}
