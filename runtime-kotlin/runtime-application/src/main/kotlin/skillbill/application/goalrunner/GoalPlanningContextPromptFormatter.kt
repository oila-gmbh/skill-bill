package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.workflow.model.DecompositionSubtask

internal object GoalPlanningContextPromptFormatter {
  fun append(prompt: String, packet: Map<String, Any?>, subtask: DecompositionSubtask?, phaseId: String): String =
    buildString {
      append(prompt)
      append("\n\n## Goal planning session context\n")
      append(
        if (phaseId == "plan") {
          "Reuse this immutable shared context for this sub-spec: "
        } else {
          "Use this immutable shared context for the parent goal: "
        },
      )
      append(JsonSupport.mapToJsonString(packet))
      if (phaseId == "plan") {
        val currentSubtask = requireNotNull(subtask) { "plan context requires a governed subtask" }
        append("\nCurrent governed sub-spec: ")
        append(currentSubtask.specPath)
        append("\nCurrent subtask dependency context: ")
        append(
          JsonSupport.mapToJsonString(
            mapOf(
              "subtask_id" to currentSubtask.id,
              "dependencies" to currentSubtask.dependencies.map { dependency ->
                mapOf(
                  "subtask_id" to dependency.subtaskId,
                  "optional" to dependency.optional,
                  "skipped" to dependency.skipped,
                )
              },
            ),
          ),
        )
        append("\nDependency metadata is planning context only. ")
        append("Do not execute, simulate, edit, or mutate dependency work.")
      }
    }
}
