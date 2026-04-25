package skillbill.mcp

import skillbill.application.model.WorkflowFamilyKind
import skillbill.telemetry.model.RemoteStatsRequest

internal typealias McpToolHandler = (Map<String, Any?>) -> Map<String, Any?>

object McpToolDispatcher {
  private val nativeHandlers: Map<String, McpToolHandler> =
    mapOf(
      "doctor" to { McpRuntime.doctor() },
      "feature_implement_stats" to { McpRuntime.featureImplementStats() },
      "feature_implement_workflow_continue" to { workflowContinue(WorkflowFamilyKind.IMPLEMENT, it) },
      "feature_implement_workflow_get" to { workflowGet(WorkflowFamilyKind.IMPLEMENT, it) },
      "feature_implement_workflow_latest" to { McpWorkflowRuntime.latest(WorkflowFamilyKind.IMPLEMENT) },
      "feature_implement_workflow_list" to { workflowList(WorkflowFamilyKind.IMPLEMENT, it) },
      "feature_implement_workflow_open" to { workflowOpen(WorkflowFamilyKind.IMPLEMENT, it) },
      "feature_implement_workflow_resume" to { workflowResume(WorkflowFamilyKind.IMPLEMENT, it) },
      "feature_implement_workflow_update" to { workflowUpdate(WorkflowFamilyKind.IMPLEMENT, it) },
      "feature_verify_stats" to { McpRuntime.featureVerifyStats() },
      "feature_verify_workflow_continue" to { workflowContinue(WorkflowFamilyKind.VERIFY, it) },
      "feature_verify_workflow_get" to { workflowGet(WorkflowFamilyKind.VERIFY, it) },
      "feature_verify_workflow_latest" to { McpWorkflowRuntime.latest(WorkflowFamilyKind.VERIFY) },
      "feature_verify_workflow_list" to { workflowList(WorkflowFamilyKind.VERIFY, it) },
      "feature_verify_workflow_open" to { workflowOpen(WorkflowFamilyKind.VERIFY, it) },
      "feature_verify_workflow_resume" to { workflowResume(WorkflowFamilyKind.VERIFY, it) },
      "feature_verify_workflow_update" to { workflowUpdate(WorkflowFamilyKind.VERIFY, it) },
      "import_review" to ::importReview,
      "new_skill_scaffold" to ::newSkillScaffold,
      "resolve_learnings" to ::resolveLearnings,
      "review_stats" to { McpRuntime.reviewStats(it.optionalString("review_run_id")) },
      "telemetry_proxy_capabilities" to { McpRuntime.telemetryProxyCapabilities() },
      "telemetry_remote_stats" to ::telemetryRemoteStats,
      "triage_findings" to ::triageFindings,
    )

  fun call(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> =
    nativeHandlers[toolName]?.invoke(arguments)
      ?: legacyToolPayload(toolName, arguments)
      ?: error("Unknown MCP tool '$toolName'.")
}

private fun legacyToolPayload(toolName: String, arguments: Map<String, Any?>): Map<String, Any?>? =
  if (toolName in legacyPythonToolNames) LegacyPythonMcpBridge.call(toolName, arguments) else null

private val legacyPythonToolNames: Set<String> =
  setOf(
    "feature_implement_finished",
    "feature_implement_started",
    "feature_verify_finished",
    "feature_verify_started",
    "pr_description_generated",
    "quality_check_finished",
    "quality_check_started",
  )

internal fun importReview(arguments: Map<String, Any?>): Map<String, Any?> = McpRuntime.importReview(
  reviewText = arguments.string("review_text"),
  orchestrated = arguments.boolean("orchestrated"),
)

internal fun triageFindings(arguments: Map<String, Any?>): Map<String, Any?> = McpRuntime.triageFindings(
  reviewRunId = arguments.string("review_run_id"),
  decisions = arguments.stringList("decisions"),
  orchestrated = arguments.boolean("orchestrated"),
)

internal fun resolveLearnings(arguments: Map<String, Any?>): Map<String, Any?> = McpRuntime.resolveLearnings(
  repo = arguments.optionalString("repo"),
  skill = arguments.optionalString("skill"),
  reviewSessionId = arguments.optionalString("review_session_id"),
)

internal fun telemetryRemoteStats(arguments: Map<String, Any?>): Map<String, Any?> = McpRuntime.telemetryRemoteStats(
  RemoteStatsRequest(
    workflow = arguments.string("workflow"),
    since = arguments.string("since"),
    dateFrom = arguments.string("date_from"),
    dateTo = arguments.string("date_to"),
    groupBy = arguments.string("group_by"),
  ),
)

internal fun newSkillScaffold(arguments: Map<String, Any?>): Map<String, Any?> = McpRuntime.newSkillScaffold(
  payload = arguments.map("payload"),
  dryRun = arguments.boolean("dry_run"),
  orchestrated = arguments.boolean("orchestrated"),
)
