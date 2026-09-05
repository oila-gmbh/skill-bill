package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private val SETTLEMENT_PHASE_IDS: Set<String> = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
)

const val PHASE_COMPLETE_SETTLEMENT_TOOL: String = "mcp__skill-bill__feature_task_phase_complete"
const val PHASE_BLOCK_SETTLEMENT_TOOL: String = "mcp__skill-bill__feature_task_phase_block"

fun settlesThroughMcp(phaseId: String): Boolean = phaseId in SETTLEMENT_PHASE_IDS

fun settlementDirective(phaseId: String, target: FeatureTaskRuntimePhaseSettlementTarget?): String {
  if (target == null || !settlesThroughMcp(phaseId)) return ""
  return """
    ## Required final output (durable settlement)
    Finish this phase through the skill-bill MCP settlement tools, not by printing a JSON envelope.
    The tools may be deferred in this session: load them first with ToolSearch
    `select:$PHASE_COMPLETE_SETTLEMENT_TOOL,$PHASE_BLOCK_SETTLEMENT_TOOL`.
    - Finished: call `$PHASE_COMPLETE_SETTLEMENT_TOOL` with workflow_id "${target.workflowId}",
      phase_id "$phaseId", attempt ${target.attempt}, and value: one prose string carrying everything
      the next phase needs (what this phase produced, deviations from the briefing, what was
      deliberately left to later phases). The fallback section below describes the content the next
      phase expects inside value; carry that content as prose or as JSON text, whichever is clearer.
      The runtime does not validate the shape of value. Optional summary: one sentence.
    - Cannot finish: call `$PHASE_BLOCK_SETTLEMENT_TOOL` with the same workflow_id, phase_id, and
      attempt, a reason string naming the obstacle, and failure_disposition one of "retryable",
      "non_retryable_policy_conflict", "needs_user_action", "process_failure", or "invalid_output".
    Copy workflow_id, phase_id, and attempt exactly as written here. After the tool returns status
    "ok", end your response with a short prose recap and nothing structured. Print the fallback
    JSON envelope below only if both tools stay unavailable after loading them.
  """.trimIndent()
}
