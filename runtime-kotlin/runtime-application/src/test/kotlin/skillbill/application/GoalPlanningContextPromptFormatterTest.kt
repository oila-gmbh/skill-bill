package skillbill.application

import skillbill.application.goalrunner.GoalPlanningContextPromptFormatter
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalPlanningContextPromptFormatterTest {
  private val subtask = DecompositionSubtask(id = 1, name = "heading-walk", specPath = "spec_subtask_1.md")

  private val packet: Map<String, Any?> = mapOf(
    "packet_version" to "0.4",
    "boundary_memory" to mapOf(
      "catalog" to listOf(
        mapOf(
          "heading_id" to FIRST_ID,
          "source_path" to "modules/a/agent/history.md",
          "kind" to "history",
          "heading" to "## [2026-08-01] first-entry",
        ),
        mapOf(
          "heading_id" to SECOND_ID,
          "source_path" to "modules/a/agent/history.md",
          "kind" to "history",
          "heading" to "## [2026-07-01] second-entry",
        ),
      ),
      "truncated" to false,
    ),
  )

  @Test
  fun `preplan prompt carries every catalog heading and no entry body`() {
    val composed = GoalPlanningContextPromptFormatter.append("base", packet, null, "preplan")

    assertContains(composed, "first-entry")
    assertContains(composed, "second-entry")
    assertContains(composed, "selected_boundary_headings")
    assertFalse(FIRST_BODY in composed)
    assertFalse(SECOND_BODY in composed)
  }

  @Test
  fun `only the selected entry body reaches the plan prompt`() {
    val composed = GoalPlanningContextPromptFormatter.append(
      "base",
      packet,
      subtask,
      "plan",
      GoalPlanningResolvedBoundaryBodies(
        bodies = listOf(
          GoalPlanningBoundaryBody(FIRST_ID, "modules/a/agent/history.md", "## [2026-08-01] first-entry", FIRST_BODY),
        ),
      ),
    )

    assertContains(composed, "## Selected boundary memory")
    assertContains(composed, FIRST_BODY)
    assertFalse(SECOND_BODY in composed, "an unselected entry's body never enters the composed plan prompt")
  }

  @Test
  fun `an empty selection produces no selected boundary memory section`() {
    val composed = GoalPlanningContextPromptFormatter.append("base", packet, subtask, "plan")

    assertFalse("## Selected boundary memory" in composed)
  }

  @Test
  fun `the formatter emits exactly the ids it was handed`() {
    val composed = GoalPlanningContextPromptFormatter.append(
      "base",
      packet,
      subtask,
      "plan",
      GoalPlanningResolvedBoundaryBodies(unresolvedHeadingIds = listOf(SECOND_ID)),
    )

    assertTrue(composed.contains("Unresolved selections"))
    assertContains(composed, SECOND_ID)
    assertFalse(SECOND_BODY in composed)
  }

  private companion object {
    const val FIRST_ID = "modules/a/agent/history.md#0-aaaaaaaaaaaa"
    const val SECOND_ID = "modules/a/agent/history.md#1-bbbbbbbbbbbb"
    const val FIRST_BODY = "distinctive sentence belonging to the first entry"
    const val SECOND_BODY = "distinctive sentence belonging to the second entry"
  }
}
