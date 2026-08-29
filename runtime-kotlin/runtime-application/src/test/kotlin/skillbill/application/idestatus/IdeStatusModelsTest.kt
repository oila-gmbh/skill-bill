package skillbill.application.idestatus

import skillbill.application.idestatus.model.IdeStatusCurrentModel
import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.application.idestatus.model.IdeStatusFreshness
import skillbill.application.idestatus.model.IdeStatusLifecycleState
import skillbill.application.idestatus.model.IdeStatusPlanning
import skillbill.application.idestatus.model.IdeStatusSnapshot
import skillbill.application.idestatus.model.IdeStatusStep
import skillbill.application.idestatus.model.IdeStatusWorkflowFamily
import skillbill.goalrunner.model.GoalPlanningStatusState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-165 Subtask 1: the emitted `planning` block must stay byte-identical to the
 * snake_case property names in orchestration/contracts/ide-status-schema.yaml.
 */
class IdeStatusModelsTest {
  @Test
  fun `toStatusWireMap emits planning with snake_case keys matching the schema`() {
    val wire = snapshot(
      IdeStatusPlanning(
        state = GoalPlanningStatusState.PARTIALLY_PLANNED,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 2,
        totalSubtaskCount = 5,
        currentPlanningSubtaskId = "3",
        reason = "Planning subtask 3.",
      ),
    ).toStatusWireMap()

    val planning = wire["planning"] as Map<*, *>
    assertEquals(
      listOf(
        "state",
        "shared_preplan_prepared",
        "planned_subtask_count",
        "total_subtask_count",
        "current_planning_subtask_id",
        "reason",
      ),
      planning.keys.map { it as String },
    )
    assertEquals("partially_planned", planning["state"])
    assertEquals(true, planning["shared_preplan_prepared"])
    assertEquals(2, planning["planned_subtask_count"])
    assertEquals(5, planning["total_subtask_count"])
    assertEquals("3", planning["current_planning_subtask_id"])
    assertEquals("Planning subtask 3.", planning["reason"])
  }

  @Test
  fun `toStatusWireMap omits optional planning sub-keys when they are null`() {
    val wire = snapshot(
      IdeStatusPlanning(
        state = GoalPlanningStatusState.NOT_STARTED,
        sharedPreplanPrepared = false,
        plannedSubtaskCount = 0,
        totalSubtaskCount = 0,
      ),
    ).toStatusWireMap()

    val planning = wire["planning"] as Map<*, *>
    assertEquals(
      listOf("state", "shared_preplan_prepared", "planned_subtask_count", "total_subtask_count"),
      planning.keys.map { it as String },
    )
    assertFalse(planning.containsKey("current_planning_subtask_id"))
    assertFalse(planning.containsKey("reason"))
  }

  @Test
  fun `toStatusWireMap omits the planning key entirely when planning is null`() {
    val wire = snapshot(planning = null).toStatusWireMap()

    // A present-but-null entry would fail schema validation differently; assert true absence.
    assertFalse(wire.containsKey("planning"))
    assertTrue(wire.containsKey("current_step"))
  }

  @Test
  fun `toStatusWireMap omits current_phase_execution when unset so older snapshots stay identical`() {
    val wire = snapshot(planning = null).toStatusWireMap()

    assertFalse(wire.containsKey("current_phase_execution"))
  }

  @Test
  fun `toStatusWireMap emits current_phase_execution with snake_case keys and omits total when unset`() {
    val withTotal = snapshot(planning = null)
      .copy(
        currentPhaseExecution = IdeStatusCurrentPhaseExecution(
          phaseId = "plan",
          kind = IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE,
          count = 1,
          total = 2,
        ),
      )
      .toStatusWireMap()
    val withoutTotal = snapshot(planning = null)
      .copy(
        currentPhaseExecution = IdeStatusCurrentPhaseExecution(
          phaseId = "audit",
          kind = IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP,
          count = 2,
        ),
      )
      .toStatusWireMap()

    assertEquals(
      mapOf(
        "phase_id" to "plan",
        "kind" to "bounded_edge",
        "count" to 1,
        "total" to 2,
      ),
      withTotal["current_phase_execution"],
    )
    assertEquals(
      mapOf(
        "phase_id" to "audit",
        "kind" to "semantic_loop",
        "count" to 2,
      ),
      withoutTotal["current_phase_execution"],
    )
    assertFalse((withoutTotal["current_phase_execution"] as Map<*, *>).containsKey("total"))
  }

  @Test
  fun `currentPhaseExecution rejects total unless kind is bounded_edge`() {
    assertFailsWith<IllegalArgumentException> {
      IdeStatusCurrentPhaseExecution(
        phaseId = "audit",
        kind = IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP,
        count = 1,
        total = 2,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      IdeStatusCurrentPhaseExecution(
        phaseId = "review",
        kind = IdeStatusCurrentPhaseExecutionKind.PASS,
        count = 1,
        total = 3,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      IdeStatusCurrentPhaseExecution(
        phaseId = "validate",
        kind = IdeStatusCurrentPhaseExecutionKind.GATE_RUN,
        count = 1,
        total = 1,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      IdeStatusCurrentPhaseExecution(
        phaseId = "implement",
        kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
        count = 1,
        total = 1,
      )
    }
    // bounded_edge may carry a meaningful cap.
    IdeStatusCurrentPhaseExecution(
      phaseId = "plan",
      kind = IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE,
      count = 1,
      total = 2,
    )
  }

  @Test
  fun `toStatusWireMap omits both pause signals when they are unset`() {
    val wire = snapshot(planning = null).toStatusWireMap()

    assertFalse(wire.containsKey("pause_requested"))
    assertFalse(wire.containsKey("paused_at"))
  }

  @Test
  fun `toStatusWireMap never emits pause_requested as false`() {
    // A false emission changes the bytes an existing consumer sees for every running goal.
    val wire = snapshot(planning = null).copy(pauseRequested = false).toStatusWireMap()

    assertFalse(wire.containsKey("pause_requested"))
  }

  @Test
  fun `toStatusWireMap emits the pause signals ahead of updated_at when set`() {
    val wire = snapshot(planning = null).copy(
      pauseRequested = true,
      pausedAt = Instant.parse("2026-08-06T09:30:00Z"),
    ).toStatusWireMap()

    assertEquals(true, wire["pause_requested"])
    assertEquals("2026-08-06T09:30:00Z", wire["paused_at"])
    val keys = wire.keys.toList()
    assertTrue(keys.indexOf("pause_requested") < keys.indexOf("updated_at"))
    assertTrue(keys.indexOf("paused_at") < keys.indexOf("updated_at"))
  }

  @Test
  fun `toStatusWireMap emits current_model as a nested object and omits effort when unset`() {
    val withEffort = snapshot(planning = null)
      .copy(currentModel = IdeStatusCurrentModel(model = "claude-opus-4-8", effort = "high"))
      .toStatusWireMap()
    val withoutEffort = snapshot(planning = null)
      .copy(currentModel = IdeStatusCurrentModel(model = "claude-opus-4-8[effort=high]"))
      .toStatusWireMap()

    assertEquals(
      linkedMapOf("model" to "claude-opus-4-8", "effort" to "high"),
      withEffort["current_model"],
    )
    // Never a null effort value: the schema pins effort as a non-empty string when present.
    assertEquals(linkedMapOf("model" to "claude-opus-4-8[effort=high]"), withoutEffort["current_model"])
  }

  @Test
  fun `toStatusWireMap omits the current_model key entirely when no model is recorded`() {
    val wire = snapshot(planning = null).toStatusWireMap()

    // A present-but-empty object would fail the schema's required model; assert true absence.
    assertFalse(wire.containsKey("current_model"))
  }

  private fun snapshot(planning: IdeStatusPlanning?): IdeStatusSnapshot = IdeStatusSnapshot(
    repositoryIdentity = "repo-root-realpath-v1:/repo",
    issueKey = "SKILL-165",
    workflowId = "goal-1",
    workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
    lifecycleState = IdeStatusLifecycleState.ACTIVE,
    currentStep = IdeStatusStep(id = "planning", label = "Planning"),
    planning = planning,
    updatedAt = Instant.parse("2026-08-06T10:00:00Z"),
    freshness = IdeStatusFreshness.FRESH,
    summary = "Goal SKILL-165 is planning subtasks (2/5 planned).",
  )
}
