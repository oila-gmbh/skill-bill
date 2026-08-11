package skillbill.application.goalrunner

import skillbill.workflow.model.DecompositionSubtask

/**
 * Shared cascade eligibility for every path that discards sibling plans because the shared preplan
 * was discarded or replaced. A stored plan id is cascade-eligible only when its manifest subtask is
 * not complete with a non-blank commit_sha (SKILL-181 / WE-4719).
 */
fun isTerminalWithCommitPlan(subtask: DecompositionSubtask): Boolean =
  subtask.status == "complete" && !subtask.commitSha.isNullOrBlank()

fun isTerminalWithCommitPlan(status: String, commitSha: String?): Boolean =
  status == "complete" && !commitSha.isNullOrBlank()

/**
 * Returns [plannedIds] that may be discarded, in ascending id order. Ids with no matching manifest
 * subtask are treated as eligible (fail closed: unknown rows must not survive a cascade).
 */
fun cascadeEligiblePlanSubtaskIds(plannedIds: Collection<Int>, subtasks: Collection<DecompositionSubtask>): List<Int> {
  val byId = subtasks.associateBy { it.id }
  return plannedIds.filter { id ->
    val subtask = byId[id]
    subtask == null || !isTerminalWithCommitPlan(subtask)
  }.distinct().sorted()
}
