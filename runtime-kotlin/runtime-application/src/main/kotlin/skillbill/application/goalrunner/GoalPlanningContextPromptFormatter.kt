package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.ports.goalrunner.model.GoalPlanningContext
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.model.DecompositionSubtask

/**
 * Appends the shared planning context to a composed phase prompt. `boundary_memory` inside the packet
 * is a heading catalog only; entry bodies are never serialized with it. The plan phase receives bodies
 * separately, for exactly the heading ids the caller resolved, and outside the digested packet so the
 * packet's integrity_sha256 is unaffected. The runtime computes no relevance of its own — it emits the
 * ids it was handed.
 */
internal object GoalPlanningContextPromptFormatter {
  fun append(
    prompt: String,
    packet: Map<String, Any?>,
    subtask: DecompositionSubtask?,
    phaseId: String,
    resolvedBodies: GoalPlanningResolvedBoundaryBodies = GoalPlanningResolvedBoundaryBodies(),
  ): String = buildString {
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
    append(
      "\nThis is child-only planning context. Do not copy its payload, implementation summary, " +
        "audit, review, diagnostic, or raw child output into the parent conversation or parent projection.",
    )
    append(" The parent retains manifest metadata, the current subtask index, and terminal outcomes only: ")
    append("{status, commit_sha, workflow_id}.")
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
      appendSelectedBoundaryMemory(resolvedBodies)
    } else {
      append(
        "\nboundary_memory is a heading catalog: heading text and stable heading_id only, no entry bodies. " +
          "Walk the headings, stop once they are no longer relevant to this goal's scope, and weave the " +
          "relevant context into produced_outputs.value as prose for the plan phase. Recommended headings " +
          "may guide your prose; selected_boundary_headings is not required.",
      )
    }
  }

  private fun StringBuilder.appendSelectedBoundaryMemory(resolved: GoalPlanningResolvedBoundaryBodies) {
    if (resolved.bodies.isEmpty() && resolved.unresolvedHeadingIds.isEmpty()) return
    append("\n\n## Selected boundary memory\n")
    for (body in resolved.bodies) {
      append("\n### ")
      append(body.headingId)
      append("\n")
      append(body.heading)
      append("\n")
      append(body.body)
      append("\n")
    }
    if (resolved.unresolvedHeadingIds.isNotEmpty()) {
      append("\nUnresolved selections (no body delivered): ")
      append(
        resolved.unresolvedHeadingIds
          .take(GoalPlanningContext.MAX_REPORTED_UNRESOLVED_IDS)
          .joinToString(", ", transform = ::singleLineId),
      )
      val omitted = resolved.unresolvedHeadingIds.size - GoalPlanningContext.MAX_REPORTED_UNRESOLVED_IDS
      if (omitted > 0) append(" (+$omitted more)")
      append("\n")
    }
    if (resolved.truncated) append("\nSelected boundary memory was truncated at its resolved-body cap.\n")
  }

  /**
   * Heading ids are model-authored output, and an unresolved one is echoed back verbatim. Collapsing
   * whitespace and capping the length stops a selection from carrying newlines that reproduce the
   * `### <heading_id>` delimiters above and forge a delivered body the resolver never returned.
   */
  private fun singleLineId(headingId: String): String = headingId
    .replace(WHITESPACE_RUN, " ")
    .trim()
    .take(GoalPlanningContext.MAX_REPORTED_UNRESOLVED_ID_CHARS)

  private val WHITESPACE_RUN = Regex("\\s+")
}
