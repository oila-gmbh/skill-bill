package skillbill.mcp

import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.FeatureVerifyStartedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest

internal fun featureImplementStarted(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.featureImplementStarted(
    FeatureImplementStartedRequest(
      featureSize = arguments.string("feature_size"),
      acceptanceCriteriaCount = arguments.int("acceptance_criteria_count", 0),
      openQuestionsCount = arguments.int("open_questions_count", 0),
      specInputTypes = arguments.stringList("spec_input_types"),
      specWordCount = arguments.int("spec_word_count", 0),
      rolloutNeeded = arguments.boolean("rollout_needed"),
      featureName = arguments.string("feature_name"),
      issueKey = arguments.string("issue_key"),
      issueKeyType = arguments.optionalString("issue_key_type") ?: "none",
      specSummary = arguments.string("spec_summary"),
    ),
    context,
  )

internal fun featureImplementFinished(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.featureImplementFinished(
    FeatureImplementFinishedRequest(
      sessionId = arguments.string("session_id"),
      completionStatus = arguments.string("completion_status"),
      planCorrectionCount = arguments.int("plan_correction_count", 0),
      planTaskCount = arguments.int("plan_task_count", 0),
      planPhaseCount = arguments.int("plan_phase_count", 0),
      featureFlagUsed = arguments.boolean("feature_flag_used"),
      filesCreated = arguments.int("files_created", 0),
      filesModified = arguments.int("files_modified", 0),
      tasksCompleted = arguments.int("tasks_completed", 0),
      reviewIterations = arguments.int("review_iterations", 0),
      auditResult = arguments.string("audit_result"),
      auditIterations = arguments.int("audit_iterations", 0),
      validationResult = arguments.string("validation_result"),
      boundaryHistoryWritten = arguments.boolean("boundary_history_written"),
      prCreated = arguments.boolean("pr_created"),
      featureFlagPattern = arguments.optionalString("feature_flag_pattern") ?: "none",
      boundaryHistoryValue = arguments.optionalString("boundary_history_value") ?: "none",
      planDeviationNotes = arguments.string("plan_deviation_notes"),
      childSteps = arguments.optionalListMap("child_steps").orEmpty(),
    ),
    context,
  )

internal fun qualityCheckStarted(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.qualityCheckStarted(
    QualityCheckStartedRequest(
      routedSkill = arguments.string("routed_skill"),
      detectedStack = arguments.string("detected_stack"),
      scopeType = arguments.string("scope_type"),
      initialFailureCount = arguments.int("initial_failure_count", 0),
      orchestrated = arguments.boolean("orchestrated"),
    ),
    context,
  )

internal fun qualityCheckFinished(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.qualityCheckFinished(
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
      scopeType = arguments.string("scope_type"),
      initialFailureCount = arguments.int("initial_failure_count", 0),
      durationSeconds = arguments.int("duration_seconds", 0),
    ),
    context,
  )

internal fun featureVerifyStarted(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.featureVerifyStarted(
    FeatureVerifyStartedRequest(
      acceptanceCriteriaCount = arguments.int("acceptance_criteria_count", 0),
      rolloutRelevant = arguments.boolean("rollout_relevant"),
      specSummary = arguments.string("spec_summary"),
      orchestrated = arguments.boolean("orchestrated"),
    ),
    context,
  )

internal fun featureVerifyFinished(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.featureVerifyFinished(
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
  McpRuntime.prDescriptionGenerated(
    PrDescriptionGeneratedRequest(
      commitCount = arguments.int("commit_count", 0),
      filesChangedCount = arguments.int("files_changed_count", 0),
      wasEditedByUser = arguments.boolean("was_edited_by_user"),
      prCreated = arguments.boolean("pr_created"),
      prTitle = arguments.string("pr_title"),
      orchestrated = arguments.boolean("orchestrated"),
    ),
    context,
  )
