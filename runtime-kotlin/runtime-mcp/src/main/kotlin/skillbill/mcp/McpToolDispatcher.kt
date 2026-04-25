package skillbill.mcp

import skillbill.application.model.WorkflowFamilyKind
import skillbill.telemetry.model.RemoteStatsRequest

internal typealias McpToolHandler = (Map<String, Any?>, McpRuntimeContext) -> Map<String, Any?>

object McpToolDispatcher {
  private val nativeHandlers: Map<String, McpToolHandler> =
    mapOf(
      "doctor" to { _, context -> McpRuntime.doctor(context) },
      "feature_implement_finished" to ::featureImplementFinished,
      "feature_implement_started" to ::featureImplementStarted,
      "feature_implement_stats" to { _, context -> McpRuntime.featureImplementStats(context) },
      "feature_implement_workflow_continue" to
        { arguments, context -> workflowContinue(WorkflowFamilyKind.IMPLEMENT, arguments, context) },
      "feature_implement_workflow_get" to
        { arguments, context -> workflowGet(WorkflowFamilyKind.IMPLEMENT, arguments, context) },
      "feature_implement_workflow_latest" to
        { _, context -> McpWorkflowRuntime.latest(WorkflowFamilyKind.IMPLEMENT, context) },
      "feature_implement_workflow_list" to
        { arguments, context -> workflowList(WorkflowFamilyKind.IMPLEMENT, arguments, context) },
      "feature_implement_workflow_open" to
        { arguments, context -> workflowOpen(WorkflowFamilyKind.IMPLEMENT, arguments, context) },
      "feature_implement_workflow_resume" to
        { arguments, context -> workflowResume(WorkflowFamilyKind.IMPLEMENT, arguments, context) },
      "feature_implement_workflow_update" to
        { arguments, context -> workflowUpdate(WorkflowFamilyKind.IMPLEMENT, arguments, context) },
      "feature_verify_finished" to ::featureVerifyFinished,
      "feature_verify_started" to ::featureVerifyStarted,
      "feature_verify_stats" to { _, context -> McpRuntime.featureVerifyStats(context) },
      "feature_verify_workflow_continue" to
        { arguments, context -> workflowContinue(WorkflowFamilyKind.VERIFY, arguments, context) },
      "feature_verify_workflow_get" to
        { arguments, context -> workflowGet(WorkflowFamilyKind.VERIFY, arguments, context) },
      "feature_verify_workflow_latest" to
        { _, context -> McpWorkflowRuntime.latest(WorkflowFamilyKind.VERIFY, context) },
      "feature_verify_workflow_list" to
        { arguments, context -> workflowList(WorkflowFamilyKind.VERIFY, arguments, context) },
      "feature_verify_workflow_open" to
        { arguments, context -> workflowOpen(WorkflowFamilyKind.VERIFY, arguments, context) },
      "feature_verify_workflow_resume" to
        { arguments, context -> workflowResume(WorkflowFamilyKind.VERIFY, arguments, context) },
      "feature_verify_workflow_update" to
        { arguments, context -> workflowUpdate(WorkflowFamilyKind.VERIFY, arguments, context) },
      "import_review" to ::importReview,
      "new_skill_scaffold" to ::newSkillScaffold,
      "pr_description_generated" to ::prDescriptionGenerated,
      "quality_check_finished" to ::qualityCheckFinished,
      "quality_check_started" to ::qualityCheckStarted,
      "resolve_learnings" to ::resolveLearnings,
      "review_stats" to
        { arguments, context -> McpRuntime.reviewStats(arguments.optionalString("review_run_id"), context) },
      "telemetry_proxy_capabilities" to { _, context -> McpRuntime.telemetryProxyCapabilities(context) },
      "telemetry_remote_stats" to ::telemetryRemoteStats,
      "triage_findings" to ::triageFindings,
    )

  fun call(
    toolName: String,
    arguments: Map<String, Any?>,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = nativeHandlers[toolName]?.invoke(arguments, context)
    ?: error("Unknown MCP tool '$toolName'.")
}

internal fun importReview(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.importReview(
    reviewText = arguments.string("review_text"),
    orchestrated = arguments.boolean("orchestrated"),
    context = context,
  )

internal fun triageFindings(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.triageFindings(
    reviewRunId = arguments.string("review_run_id"),
    decisions = arguments.stringList("decisions"),
    orchestrated = arguments.boolean("orchestrated"),
    context = context,
  )

internal fun resolveLearnings(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.resolveLearnings(
    repo = arguments.optionalString("repo"),
    skill = arguments.optionalString("skill"),
    reviewSessionId = arguments.optionalString("review_session_id"),
    context = context,
  )

internal fun telemetryRemoteStats(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.telemetryRemoteStats(
    RemoteStatsRequest(
      workflow = arguments.string("workflow"),
      since = arguments.string("since"),
      dateFrom = arguments.string("date_from"),
      dateTo = arguments.string("date_to"),
      groupBy = arguments.string("group_by"),
    ),
    context,
  )

internal fun newSkillScaffold(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.newSkillScaffold(
    payload = arguments.map("payload"),
    dryRun = arguments.boolean("dry_run"),
    orchestrated = arguments.boolean("orchestrated"),
    context = context,
  )
