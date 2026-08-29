package skillbill.mcp.featuretask

import skillbill.mcp.core.McpRuntimeContext
import skillbill.mcp.core.optionalInt
import skillbill.mcp.core.optionalString
import skillbill.mcp.core.services
import skillbill.mcp.core.string

internal fun featureTaskPhaseComplete(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  services(context).featureTaskPhaseSettlementService.complete(
    workflowId = arguments.string("workflow_id"),
    phaseId = arguments.string("phase_id"),
    attempt = requireNotNull(arguments.optionalInt("attempt")) { "attempt is required." },
    value = arguments.string("value"),
    prompt = arguments.optionalString("prompt"),
    summary = arguments.optionalString("summary"),
  )

internal fun featureTaskPhaseBlock(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  services(context).featureTaskPhaseSettlementService.block(
    workflowId = arguments.string("workflow_id"),
    phaseId = arguments.string("phase_id"),
    attempt = requireNotNull(arguments.optionalInt("attempt")) { "attempt is required." },
    reason = arguments.string("reason"),
    failureDisposition = arguments.optionalString("failure_disposition") ?: "needs_user_action",
  )

internal fun featureTaskAuditSettle(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  services(context).featureTaskPhaseSettlementService.auditSettle(
    workflowId = arguments.string("workflow_id"),
    phaseId = arguments.optionalString("phase_id") ?: "audit",
    attempt = requireNotNull(arguments.optionalInt("attempt")) { "attempt is required." },
    verdict = arguments.string("verdict"),
    value = arguments.string("value"),
    summary = arguments.optionalString("summary"),
  )
