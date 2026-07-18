package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.workflow.model.DecompositionSubtask

internal object GoalPlanningContextPromptFormatter {
  fun append(prompt: String, packet: Map<String, Any?>, subtask: DecompositionSubtask): String = buildString {
    append(prompt)
    append("\n\n## Goal planning session context\n")
    append("Reuse this immutable shared context for this sub-spec: ")
    append(JsonSupport.mapToJsonString(packet))
    append("\nCurrent governed sub-spec: ")
    append(subtask.specPath)
    append("\nCurrent subtask dependency context: ")
    append(
      JsonSupport.mapToJsonString(
        mapOf(
          "subtask_id" to subtask.id,
          "dependencies" to subtask.dependencies.map { dependency ->
            mapOf(
              "subtask_id" to dependency.subtaskId,
              "optional" to dependency.optional,
              "skipped" to dependency.skipped,
            )
          },
        ),
      ),
    )
    append("\nDependency metadata is planning context only. Do not execute, simulate, edit, or mutate dependency work.")
  }
}
