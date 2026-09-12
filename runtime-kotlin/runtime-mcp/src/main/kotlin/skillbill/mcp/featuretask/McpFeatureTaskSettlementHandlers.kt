package skillbill.mcp.featuretask

import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementAuditRequest
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.optionalInt
import skillbill.mcp.shared.optionalString
import skillbill.mcp.shared.services
import skillbill.mcp.shared.string

internal fun featureTaskPhaseComplete(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  services(context).featureTaskPhaseSettlementService.complete(
    FeatureTaskPhaseSettlementCompleteRequest(
      workflowId = arguments.string("workflow_id"),
      phaseId = arguments.string("phase_id"),
      attempt = requireNotNull(arguments.optionalInt("attempt")) { "attempt is required." },
      value = arguments.string("value"),
      prompt = arguments.optionalString("prompt"),
      summary = arguments.optionalString("summary"),
    ),
  )

internal fun featureTaskPhaseBlock(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  services(context).featureTaskPhaseSettlementService.block(
    FeatureTaskPhaseSettlementBlockRequest(
      workflowId = arguments.string("workflow_id"),
      phaseId = arguments.string("phase_id"),
      attempt = requireNotNull(arguments.optionalInt("attempt")) { "attempt is required." },
      reason = arguments.string("reason"),
      failureDisposition = arguments.optionalString("failure_disposition") ?: "needs_user_action",
    ),
  )

internal fun featureTaskAuditSettle(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  services(context).featureTaskPhaseSettlementService.auditSettle(
    FeatureTaskPhaseSettlementAuditRequest(
      workflowId = arguments.string("workflow_id"),
      phaseId = arguments.optionalString("phase_id") ?: "audit",
      attempt = requireNotNull(arguments.optionalInt("attempt")) { "attempt is required." },
      verdict = arguments.string("verdict"),
      value = arguments.string("value"),
      summary = arguments.optionalString("summary"),
    ),
  )

internal fun featureTaskAuditStage(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> {
  val runtimeServices = services(context)
  return runtimeServices.featureTaskPhaseSettlementService.auditStage(arguments)
}
