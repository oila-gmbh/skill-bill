package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.openFeatureTask
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.services
import skillbill.ports.workflow.model.FeatureTaskRouteScope

data class McpWorkflowOpenArgs(
  val kind: WorkflowFamilyKind,
  val sessionId: String = "",
  val currentStepId: String? = null,
  val context: McpRuntimeContext = McpRuntimeContext(),
  val issueKey: String? = null,
  val repositoryIdentity: String? = null,
  val governedSpecPath: String? = null,
)

object McpWorkflowRuntime {
  fun open(args: McpWorkflowOpenArgs): Map<String, Any?> {
    val runtimeServices = services(args.context)
    val open = if (args.kind != WorkflowFamilyKind.VERIFY && args.issueKey != null) {
      runtimeServices.workflowService.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = args.kind,
          sessionId = args.sessionId,
          currentStepId = args.currentStepId,
          dbOverride = null,
          issueKey = args.issueKey,
          repositoryIdentity = requireNotNull(args.repositoryIdentity) {
            "Feature-task workflow opens require repository_identity."
          },
          governedSpecPath = requireNotNull(args.governedSpecPath) {
            "Feature-task workflow opens require governed_spec_path."
          },
          routeScope = FeatureTaskRouteScope.STANDALONE,
        ),
      )
    } else {
      runtimeServices.workflowService.open(
        WorkflowServiceOpenArgs(
          kind = args.kind,
          sessionId = args.sessionId,
          currentStepId = args.currentStepId,
          dbOverride = null,
          issueKey = args.issueKey,
          repositoryIdentity = args.repositoryIdentity,
          governedSpecPath = args.governedSpecPath,
          routeScope = FeatureTaskRouteScope.STANDALONE,
        ),
      )
    }
    return open.toMcpMap(runtimeServices.workflowService.goalObservabilityEventValidator)
  }

  fun update(
    kind: WorkflowFamilyKind,
    request: WorkflowUpdateRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val runtimeServices = services(context)
    return runtimeServices.workflowService.update(
      kind,
      request,
      dbOverride = null,
    ).toMcpMap()
  }

  fun get(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val runtimeServices = services(context)
    return runtimeServices.workflowService.get(kind, workflowId, dbOverride = null)
      .toMcpMap(runtimeServices.workflowService.goalObservabilityEventValidator)
  }

  fun list(
    kind: WorkflowFamilyKind,
    limit: Int = 20,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.list(kind, limit, dbOverride = null).toMcpMap()

  fun latest(kind: WorkflowFamilyKind, context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).workflowService.latest(kind, dbOverride = null).toMcpMap()

  fun resume(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.resume(kind, workflowId, dbOverride = null).toMcpMap()

  fun continueWorkflow(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
    subtaskId: Int? = null,
  ): Map<String, Any?> = services(context).workflowService.continueWorkflow(
    kind,
    workflowId,
    subtaskId = subtaskId,
    dbOverride = null,
  ).toMcpMap()
}
