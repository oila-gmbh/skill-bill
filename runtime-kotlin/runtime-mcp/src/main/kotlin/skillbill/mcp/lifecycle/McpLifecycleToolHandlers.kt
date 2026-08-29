package skillbill.mcp.lifecycle

import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.mcp.core.McpRuntimeContext
import skillbill.mcp.core.McpRuntimeLifecycle
import skillbill.mcp.core.boolean
import skillbill.mcp.core.int
import skillbill.mcp.core.optionalString
import skillbill.mcp.core.string
import skillbill.mcp.core.stringList

internal fun qualityCheckStarted(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> {
  return McpRuntimeLifecycle.qualityCheckStarted(
    QualityCheckStartedRequest(
      routedSkill = arguments.string("routed_skill"),
      detectedStack = arguments.string("detected_stack"),
      fallback = arguments.boolean("fallback"),
      fallbackReason = arguments.optionalString("fallback_reason"),
      scopeType = arguments.string("scope_type"),
      initialFailureCount = arguments.int("initial_failure_count", 0),
      orchestrated = arguments.boolean("orchestrated"),
    ),
    context,
  )
}

internal fun qualityCheckFinished(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> {
  return McpRuntimeLifecycle.qualityCheckFinished(
    QualityCheckFinishedRequest(
      finalFailureCount = arguments.int("final_failure_count", 0),
      iterations = arguments.int("iterations", 0),
      result = arguments.string("result"),
      sessionId = arguments.string("session_id"),
      failingCheckNames = arguments.stringList("failing_check_names"),
      unsupportedReason = arguments.string("unsupported_reason"),
      orchestrated = arguments.boolean("orchestrated"),
      routedSkill = arguments.string("routed_skill"),
      detectedStack = arguments.string("detected_stack"),
      fallback = arguments.boolean("fallback"),
      fallbackReason = arguments.optionalString("fallback_reason"),
      scopeType = arguments.string("scope_type"),
      initialFailureCount = arguments.int("initial_failure_count", 0),
      durationSeconds = arguments.int("duration_seconds", 0),
    ),
    context,
  )
}

internal fun featureVerifyStarted(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntimeLifecycle.featureVerifyStarted(
    FeatureVerifyStartedRequest(
      acceptanceCriteriaCount = arguments.int("acceptance_criteria_count", 0),
      rolloutRelevant = arguments.boolean("rollout_relevant"),
      specSummary = arguments.string("spec_summary"),
      orchestrated = arguments.boolean("orchestrated"),
    ),
    context,
  )

internal fun featureVerifyFinished(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntimeLifecycle.featureVerifyFinished(
    FeatureVerifyFinishedRequest(
      featureFlagAuditPerformed = arguments.boolean("feature_flag_audit_performed"),
      reviewIterations = arguments.int("review_iterations", 0),
      auditResult = arguments.string("audit_result"),
      completionStatus = arguments.string("completion_status"),
      historyRelevance = arguments.optionalString("history_relevance") ?: "none",
      historyHelpfulness = arguments.optionalString("history_helpfulness") ?: "none",
      sessionId = arguments.string("session_id"),
      gapsFound = arguments.stringList("gaps_found"),
      orchestrated = arguments.boolean("orchestrated"),
      acceptanceCriteriaCount = arguments.int("acceptance_criteria_count", 0),
      rolloutRelevant = arguments.boolean("rollout_relevant"),
      specSummary = arguments.string("spec_summary"),
      durationSeconds = arguments.int("duration_seconds", 0),
    ),
    context,
  )

internal fun prDescriptionGenerated(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntimeLifecycle.prDescriptionGenerated(
    PrDescriptionGeneratedRequest(
      commitCount = arguments.int("commit_count", 0),
      filesChangedCount = arguments.int("files_changed_count", 0),
      wasEditedByUser = arguments.boolean("was_edited_by_user"),
      prCreated = arguments.boolean("pr_created"),
      prTitle = arguments.string("pr_title"),
      orchestrated = arguments.boolean("orchestrated"),
      generatedDescription = arguments.optionalString("generated_description"),
      finalPrBody = arguments.optionalString("final_pr_body"),
    ),
    context,
  )
