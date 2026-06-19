package skillbill.mcp.core

import skillbill.application.model.WorkflowFamilyKind
import skillbill.mcp.lifecycle.featureImplementFinished
import skillbill.mcp.lifecycle.featureImplementStarted
import skillbill.mcp.lifecycle.featureTaskRuntimeFinished
import skillbill.mcp.lifecycle.featureTaskRuntimeStarted
import skillbill.mcp.lifecycle.featureVerifyFinished
import skillbill.mcp.lifecycle.featureVerifyStarted
import skillbill.mcp.lifecycle.prDescriptionGenerated
import skillbill.mcp.lifecycle.qualityCheckFinished
import skillbill.mcp.lifecycle.qualityCheckStarted
import skillbill.mcp.telemetry.TELEMETRY_EVENT_CONTRACT_VERSION
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
import skillbill.mcp.workflow.workflowContinue
import skillbill.mcp.workflow.workflowGet
import skillbill.mcp.workflow.workflowList
import skillbill.mcp.workflow.workflowOpen
import skillbill.mcp.workflow.workflowResume
import skillbill.mcp.workflow.workflowUpdate
import skillbill.telemetry.model.RemoteStatsRequest

internal typealias McpToolHandler = (Map<String, Any?>, McpRuntimeContext) -> Map<String, Any?>

object McpToolDispatcher {
  private val nativeHandlers: Map<String, McpToolHandler> =
    mapOf(
      "doctor" to { _, context -> McpRuntime.doctor(context) },
      "feature_task_prose_finished" to ::featureImplementFinished,
      "feature_task_prose_started" to ::featureImplementStarted,
      "feature_task_prose_stats" to { _, context -> McpRuntime.featureImplementStats(context) },
      "feature_task_prose_workflow_continue" to
        { arguments, context -> workflowContinue(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_task_prose_workflow_get" to
        { arguments, context -> workflowGet(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_task_prose_workflow_latest" to
        { _, context -> McpWorkflowRuntime.latest(WorkflowFamilyKind.TASK_PROSE, context) },
      "feature_task_prose_workflow_list" to
        { arguments, context -> workflowList(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_task_prose_workflow_open" to
        { arguments, context -> workflowOpen(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_task_prose_workflow_resume" to
        { arguments, context -> workflowResume(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_task_prose_workflow_update" to
        { arguments, context -> workflowUpdate(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_implement_finished" to ::featureImplementFinished,
      "feature_implement_started" to ::featureImplementStarted,
      "feature_implement_stats" to { _, context -> McpRuntime.featureImplementStats(context) },
      "feature_implement_workflow_continue" to
        { arguments, context -> workflowContinue(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_implement_workflow_get" to
        { arguments, context -> workflowGet(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_implement_workflow_latest" to
        { _, context -> McpWorkflowRuntime.latest(WorkflowFamilyKind.TASK_PROSE, context) },
      "feature_implement_workflow_list" to
        { arguments, context -> workflowList(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_implement_workflow_open" to
        { arguments, context -> workflowOpen(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_implement_workflow_resume" to
        { arguments, context -> workflowResume(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
      "feature_implement_workflow_update" to
        { arguments, context -> workflowUpdate(WorkflowFamilyKind.TASK_PROSE, arguments, context) },
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
      "feature_task_runtime_finished" to ::featureTaskRuntimeFinished,
      "feature_task_runtime_started" to ::featureTaskRuntimeStarted,
      "feature_task_runtime_stats" to { _, context -> McpRuntime.featureTaskRuntimeStats(context) },
      "feature_task_runtime_workflow_continue" to
        { arguments, context -> workflowContinue(WorkflowFamilyKind.TASK_RUNTIME, arguments, context) },
      "feature_task_runtime_workflow_get" to
        { arguments, context -> workflowGet(WorkflowFamilyKind.TASK_RUNTIME, arguments, context) },
      "feature_task_runtime_workflow_latest" to
        { _, context -> McpWorkflowRuntime.latest(WorkflowFamilyKind.TASK_RUNTIME, context) },
      "feature_task_runtime_workflow_list" to
        { arguments, context -> workflowList(WorkflowFamilyKind.TASK_RUNTIME, arguments, context) },
      "feature_task_runtime_workflow_open" to
        { arguments, context -> workflowOpen(WorkflowFamilyKind.TASK_RUNTIME, arguments, context) },
      "feature_task_runtime_workflow_resume" to
        { arguments, context -> workflowResume(WorkflowFamilyKind.TASK_RUNTIME, arguments, context) },
      "feature_task_runtime_workflow_update" to
        { arguments, context -> workflowUpdate(WorkflowFamilyKind.TASK_RUNTIME, arguments, context) },
      "goal_stats" to { _, context -> McpRuntime.goalStats(context) },
      "import_review" to ::importReview,
      "new_skill_scaffold" to ::newSkillScaffold,
      "pr_description_generated" to ::prDescriptionGenerated,
      "quality_check_finished" to ::qualityCheckFinished,
      "quality_check_started" to ::qualityCheckStarted,
      "readian_auth_status" to { _, context -> ReadianMcpRuntime.authStatus(context) },
      "readian_get_article" to
        { arguments, context -> ReadianMcpRuntime.call("readian_get_article", arguments, context) },
      "readian_get_articles_for_topic_query" to
        { arguments, context -> ReadianMcpRuntime.call("readian_get_articles_for_topic_query", arguments, context) },
      "readian_get_spotlight" to
        { arguments, context -> ReadianMcpRuntime.call("readian_get_spotlight", arguments, context) },
      "readian_mark_story_status" to
        { arguments, context -> ReadianMcpRuntime.call("readian_mark_story_status", arguments, context) },
      "readian_save_candidate" to
        { arguments, context -> ReadianMcpRuntime.call("readian_save_candidate", arguments, context) },
      "resolve_learnings" to ::resolveLearnings,
      "review_stats" to
        { arguments, context -> McpRuntime.reviewStats(arguments.optionalString("review_run_id"), context) },
      "telemetry_proxy_capabilities" to { _, context -> McpRuntime.telemetryProxyCapabilities(context) },
      "telemetry_remote_stats" to ::telemetryRemoteStats,
      "triage_findings" to ::triageFindings,
    )

  internal val legacyToolAliases: Map<String, String> =
    mapOf(
      "feature_implement_finished" to "feature_task_prose_finished",
      "feature_implement_started" to "feature_task_prose_started",
      "feature_implement_stats" to "feature_task_prose_stats",
      "feature_implement_workflow_continue" to "feature_task_prose_workflow_continue",
      "feature_implement_workflow_get" to "feature_task_prose_workflow_get",
      "feature_implement_workflow_latest" to "feature_task_prose_workflow_latest",
      "feature_implement_workflow_list" to "feature_task_prose_workflow_list",
      "feature_implement_workflow_open" to "feature_task_prose_workflow_open",
      "feature_implement_workflow_resume" to "feature_task_prose_workflow_resume",
      "feature_implement_workflow_update" to "feature_task_prose_workflow_update",
    )

  internal fun canonicalToolName(toolName: String): String = legacyToolAliases[toolName] ?: toolName

  fun call(
    toolName: String,
    arguments: Map<String, Any?>,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val handler = nativeHandlers[toolName] ?: error("Unknown MCP tool '$toolName'.")
    val canonicalName = canonicalToolName(toolName)
    // SKILL-48 Subtask 2d: validate every telemetry envelope at the
    // single parse seam BEFORE the handler builds its typed model.
    // The dispatcher is the one place where the tool name (and
    // therefore the discriminator) is known a-priori, so synthesizing
    // the envelope here keeps the validator close to the wire and the
    // typed models on the inside unchanged. Loud-fail via
    // InvalidTelemetryEventSchemaError carrying field path + event
    // name (see TelemetryEventSchemaValidator).
    //
    // F-002 audit (review-run rvw-20260519-162500-a2d4): every in-tree
    // native emitter supplies all `required` schema fields. There is
    // exactly ONE production caller of `McpToolDispatcher.call` —
    // `McpStdioServer.callToolResult` (runtime-kotlin/runtime-mcp/.../McpStdioServer.kt)
    // — which threads the JSON-RPC `tools/call` `arguments` map straight
    // through. Concrete telemetry payloads come from the orchestrators
    // (`/feature_implement_*`, `/feature_verify_*`, quality-check skills,
    // etc.) which already supply every required key per their telemetry
    // contracts. The handlers in `McpLifecycleToolHandlers.kt` and
    // `McpToolDispatcher.kt` only *default* on `arguments.int(name, 0)`,
    // `arguments.string(name) -> ""`, and `arguments.boolean(name) -> false`
    // — those defaults serve typed-model construction once the schema
    // already accepted the payload, NOT to mask missing required keys
    // from real emitters. Defaults are exercised only in unit tests
    // (`McpRuntimeTest.kt:224`), which now build schema-complete
    // payloads explicitly. Loud-fail at the dispatcher is therefore
    // safe; any in-flight production emitter that omits a required key
    // is already breaking its own telemetry contract.
    TelemetryEventSchemaValidator.validate(
      envelope = telemetryEnvelope(canonicalName, arguments),
      eventName = canonicalName,
    )
    return handler.invoke(arguments, context)
  }

  /**
   * SKILL-48 Subtask 2d: builds the telemetry envelope that
   * `TelemetryEventSchemaValidator` validates against
   * `orchestration/contracts/telemetry-event-schema.yaml`. The
   * envelope keys are: `event_name` (discriminator),
   * `contract_version` (pinned), plus every key from the inbound
   * `arguments` map. Callers in production already carry these as
   * dispatch metadata; codifying the synthesis here keeps it out of
   * every handler.
   */
  internal fun telemetryEnvelope(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> {
    val envelope = linkedMapOf<String, Any?>(
      "event_name" to toolName,
      "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
    )
    arguments.forEach { (key, value) ->
      // The wire envelope keeps caller-supplied `event_name` /
      // `contract_version` out of the way of the discriminator —
      // arguments use those names only as payload fields when a future
      // tool genuinely needs them, which today's `inputSchemas` never
      // do. Skipping them here means a stray caller-supplied
      // `event_name` cannot shadow the dispatcher's discriminator.
      if (key != "event_name" && key != "contract_version") {
        envelope[key] = value
      }
    }
    return envelope
  }
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
      workflow = mapRemoteStatsWorkflow(arguments.string("workflow")),
      since = arguments.string("since"),
      dateFrom = arguments.string("date_from"),
      dateTo = arguments.string("date_to"),
      groupBy = arguments.string("group_by"),
    ),
    context,
  )

private fun mapRemoteStatsWorkflow(workflow: String): String = when (workflow) {
  "verify" -> "bill-feature-verify"
  "implement", "bill-feature-task", "feature-task-prose" -> "feature-task-prose"
  "goal" -> "bill-feature-goal"
  "bill-feature-verify", "feature-task-runtime", "bill-feature-goal" -> workflow
  else -> throw IllegalArgumentException(
    "workflow must be one of: verify, implement, bill-feature-task, goal, " +
      "bill-feature-verify, feature-task-prose, feature-task-runtime, bill-feature-goal.",
  )
}

internal fun newSkillScaffold(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.newSkillScaffold(
    payload = arguments.map("payload"),
    dryRun = arguments.boolean("dry_run"),
    orchestrated = arguments.boolean("orchestrated"),
    context = context,
  )
