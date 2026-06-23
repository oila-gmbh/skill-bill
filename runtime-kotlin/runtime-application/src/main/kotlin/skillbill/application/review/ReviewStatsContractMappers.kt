@file:Suppress("TooManyFunctions")

package skillbill.application.review

import skillbill.application.model.FeatureImplementStatsResult
import skillbill.application.model.FeatureTaskRuntimeStatsResult
import skillbill.application.model.FeatureVerifyStatsResult
import skillbill.application.model.GoalStatsResult
import skillbill.application.model.ReviewStatsResult
import skillbill.application.workflow.toPayload
import skillbill.contracts.JsonPayloadContract
import skillbill.review.model.FeatureImplementChildStepCoverageStats
import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureSizeOutcomeStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.GoalBlockedSubtaskSummary
import skillbill.review.model.GoalRunSummary
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.LargeFeatureHealthStats
import skillbill.review.model.ReviewFindingDetail
import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewHealthStats

fun ReviewStatsResult.toReviewStatsPayload(): JsonPayloadContract = MapPayloadContract(
  LinkedHashMap(stats.toPayload()).apply {
    put("health", health.toPayload())
    put("review_run_id", reviewRunId)
    put("db_path", dbPath)
  },
)

fun FeatureImplementStatsResult.toFeatureImplementStatsPayload(): JsonPayloadContract =
  MapPayloadContract(LinkedHashMap(stats.toPayload()).apply { put("db_path", dbPath) })

fun FeatureVerifyStatsResult.toFeatureVerifyStatsPayload(): JsonPayloadContract =
  MapPayloadContract(LinkedHashMap(stats.toPayload()).apply { put("db_path", dbPath) })

fun FeatureTaskRuntimeStatsResult.toFeatureTaskRuntimeStatsPayload(): JsonPayloadContract =
  MapPayloadContract(LinkedHashMap(stats.toPayload()).apply { put("db_path", dbPath) })

fun GoalStatsResult.toGoalStatsPayload(): JsonPayloadContract =
  MapPayloadContract(LinkedHashMap(stats.toPayload()).apply { put("db_path", dbPath) })

private class MapPayloadContract(
  private val payload: Map<String, Any?>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = payload
}

private fun ReviewFindingStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "total_findings" to totalFindings,
  "accepted_findings" to acceptedFindings,
  "rejected_findings" to rejectedFindings,
  "unresolved_findings" to unresolvedFindings,
  "accepted_rate" to acceptedRate,
  "rejected_rate" to rejectedRate,
  "latest_outcome_counts" to latestOutcomeCounts,
  "accepted_severity_counts" to acceptedSeverityCounts,
  "rejected_severity_counts" to rejectedSeverityCounts,
  "unresolved_severity_counts" to unresolvedSeverityCounts,
  "accepted_finding_details" to acceptedFindingDetails.map(ReviewFindingDetail::toPayload),
  "rejected_findings_with_notes" to rejectedFindingsWithNotes,
  "rejected_finding_details" to rejectedFindingDetails.map(ReviewFindingDetail::toPayload),
)

private fun ReviewHealthStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "total_review_payload_records" to totalReviewPayloadRecords,
  "included_review_payload_records" to includedReviewPayloadRecords,
  "standalone_review_payload_records" to standaloneReviewPayloadRecords,
  "embedded_review_payload_records" to embeddedReviewPayloadRecords,
  "malformed_review_payload_records" to malformedReviewPayloadRecords,
  "data_quality_debt_records" to dataQualityDebtRecords,
  "total_findings" to totalFindings,
  "average_findings" to averageFindings,
  "median_findings" to medianFindings,
  "p90_findings" to p90Findings,
  "accepted_findings" to acceptedFindings,
  "rejected_findings" to rejectedFindings,
  "unresolved_findings" to unresolvedFindings,
  "accepted_rate" to acceptedRate,
  "rejected_rate" to rejectedRate,
  "unresolved_rate" to unresolvedRate,
  "severity_counts" to severityCounts,
  "confidence_counts" to confidenceCounts,
  "latest_outcome_counts" to latestOutcomeCounts,
  "issue_category_counts" to issueCategoryCounts,
  "platform_counts" to platformCounts,
  "scope_counts" to scopeCounts,
  "source_counts" to sourceCounts,
)

private fun ReviewFindingDetail.toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "finding_id" to findingId,
  "issue_category" to issueCategory,
  "severity" to severity,
  "confidence" to confidence,
  "location" to location,
  "description" to description,
  "outcome_type" to outcomeType,
).apply {
  if (note.isNotEmpty()) put("note", note)
}

private fun FeatureImplementWorkflowStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "workflow" to "bill-feature-task",
  "total_runs" to totalRuns,
  "finished_runs" to finishedRuns,
  "in_progress_runs" to inProgressRuns,
  "raw_run_count" to rawRunCount,
  "source_counts" to sourceCounts,
  "valid_health_denominator_runs" to validHealthDenominatorRuns,
  "data_quality_debt_runs" to dataQualityDebtRuns,
  "malformed_session_id_runs" to malformedSessionIdRuns,
  "unknown_source_runs" to unknownSourceRuns,
  "duplicate_terminal_finished_events" to duplicateTerminalFinishedEvents,
  "open_runs" to openRuns,
  "completed_runs" to completedRuns,
  "completed_rate" to completedRate,
  "abandoned_at_planning_runs" to abandonedAtPlanningRuns,
  "abandoned_at_implementation_runs" to abandonedAtImplementationRuns,
  "abandoned_at_review_runs" to abandonedAtReviewRuns,
  "error_runs" to errorRuns,
  "error_rate" to errorRate,
  "normal_duration_runs" to normalDurationRuns,
  "synthetic_zero_duration_runs" to syntheticZeroDurationRuns,
  "long_running_duration_runs" to longRunningDurationRuns,
  "invalid_duration_runs" to invalidDurationRuns,
  "median_duration_seconds" to medianDurationSeconds,
  "p90_duration_seconds" to p90DurationSeconds,
  "child_step_coverage" to childStepCoverage.toPayload(),
  "feature_size_outcome_stats" to featureSizeOutcomeStats.mapValues { (_, value) -> value.toPayload() },
  "large_feature_health" to largeFeatureHealth.toPayload(),
  "feature_size_counts" to featureSizeCounts,
  "completion_status_counts" to completionStatusCounts,
  "audit_result_counts" to auditResultCounts,
  "validation_result_counts" to validationResultCounts,
  "feature_flag_pattern_counts" to featureFlagPatternCounts,
  "boundary_history_value_counts" to boundaryHistoryValueCounts,
  "rollout_needed_runs" to rolloutNeededRuns,
  "rollout_needed_rate" to rolloutNeededRate,
  "feature_flag_used_runs" to featureFlagUsedRuns,
  "feature_flag_used_rate" to featureFlagUsedRate,
  "pr_created_runs" to prCreatedRuns,
  "pr_created_rate" to prCreatedRate,
  "boundary_history_written_runs" to boundaryHistoryWrittenRuns,
  "boundary_history_written_rate" to boundaryHistoryWrittenRate,
  "average_acceptance_criteria_count" to averageAcceptanceCriteriaCount,
  "average_spec_word_count" to averageSpecWordCount,
  "average_review_iterations" to averageReviewIterations,
  "average_audit_iterations" to averageAuditIterations,
  "average_files_created" to averageFilesCreated,
  "average_files_modified" to averageFilesModified,
  "average_tasks_completed" to averageTasksCompleted,
  "average_duration_seconds" to averageDurationSeconds,
  "estimated_token_runs_with_value" to estimatedTokenRunsWithValue,
  "average_estimated_total_tokens" to averageEstimatedTotalTokens,
)

private fun FeatureImplementChildStepCoverageStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "runs_with_child_steps" to runsWithChildSteps,
  "review_child_step_runs" to reviewChildStepRuns,
  "quality_check_child_step_runs" to qualityCheckChildStepRuns,
  "pr_description_child_step_runs" to prDescriptionChildStepRuns,
  "malformed_child_step_runs" to malformedChildStepRuns,
  "child_step_coverage_rate" to childStepCoverageRate,
)

private fun FeatureSizeOutcomeStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "total_runs" to totalRuns,
  "completed_runs" to completedRuns,
  "completed_rate" to completedRate,
  "abandoned_at_planning_runs" to abandonedAtPlanningRuns,
  "abandoned_at_planning_rate" to abandonedAtPlanningRate,
  "abandoned_at_implementation_runs" to abandonedAtImplementationRuns,
  "abandoned_at_implementation_rate" to abandonedAtImplementationRate,
  "abandoned_at_review_runs" to abandonedAtReviewRuns,
  "abandoned_at_review_rate" to abandonedAtReviewRate,
  "error_runs" to errorRuns,
  "error_rate" to errorRate,
  "open_runs" to openRuns,
  "average_duration_seconds" to averageDurationSeconds,
  "median_duration_seconds" to medianDurationSeconds,
  "p90_duration_seconds" to p90DurationSeconds,
)

private fun LargeFeatureHealthStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "denominator_runs" to denominatorRuns,
  "completed_runs" to completedRuns,
  "abandoned_runs" to abandonedRuns,
  "error_runs" to errorRuns,
  "unhealthy_runs" to unhealthyRuns,
  "unhealthy_rate" to unhealthyRate,
  "overall_unhealthy_rate" to overallUnhealthyRate,
  "recommendation_threshold" to recommendationThreshold,
  "recommendation" to recommendation,
)

private fun FeatureTaskRuntimeWorkflowStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "workflow" to "feature-task-runtime",
  "total_runs" to totalRuns,
  "finished_runs" to finishedRuns,
  "in_progress_runs" to inProgressRuns,
  "feature_size_counts" to featureSizeCounts,
  "completion_status_counts" to completionStatusCounts,
  "phase_outcome_counts" to phaseOutcomeCounts,
  "completed_runs" to completedRuns,
  "completed_rate" to completedRate,
  "blocked_runs" to blockedRuns,
  "blocked_rate" to blockedRate,
  "decomposed_runs" to decomposedRuns,
  "decomposed_rate" to decomposedRate,
  "error_runs" to errorRuns,
  "error_rate" to errorRate,
  "average_completed_phase_count" to averageCompletedPhaseCount,
  "estimated_token_runs_with_value" to estimatedTokenRunsWithValue,
  "average_estimated_total_tokens" to averageEstimatedTotalTokens,
)

private fun GoalWorkflowStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "workflow" to "bill-goal-run",
  "total_runs" to totalRuns,
  "finished_runs" to finishedRuns,
  "in_progress_runs" to inProgressRuns,
  "completion_status_counts" to completionStatusCounts,
  "completed_runs" to completedRuns,
  "completed_rate" to completedRate,
  "blocked_runs" to blockedRuns,
  "blocked_rate" to blockedRate,
  "subtask_outcome_counts" to subtaskOutcomeCounts,
  "total_subtask_events" to totalSubtaskEvents,
  "average_run_duration_ms" to averageRunDurationMs,
  "average_subtask_duration_ms" to averageSubtaskDurationMs,
  "average_attempt_count" to averageAttemptCount,
  "top_blocked_subtasks" to topBlockedSubtasks.map(GoalBlockedSubtaskSummary::toPayload),
  "most_recent_run" to mostRecentRun?.toPayload(),
)

private fun GoalRunSummary.toPayload(): Map<String, Any?> = linkedMapOf(
  "workflow_id" to workflowId,
  "issue_key" to issueKey,
  "feature_name" to featureName,
  "status" to status,
  "started_at" to startedAt,
  "finished_at" to finishedAt,
  "duration_ms" to durationMs,
  "resumed" to resumed,
  "subtask_total" to subtaskTotal,
)

private fun GoalBlockedSubtaskSummary.toPayload(): Map<String, Any?> = linkedMapOf(
  "subtask_id" to subtaskId,
  "subtask_name" to subtaskName,
  "issue_key" to issueKey,
  "blocked_reason" to blockedReason,
  "attempt_count" to attemptCount,
)

private fun FeatureVerifyWorkflowStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "workflow" to "bill-feature-verify",
  "total_runs" to totalRuns,
  "finished_runs" to finishedRuns,
  "in_progress_runs" to inProgressRuns,
  "completion_status_counts" to completionStatusCounts,
  "audit_result_counts" to auditResultCounts,
  "rollout_relevant_runs" to rolloutRelevantRuns,
  "rollout_relevant_rate" to rolloutRelevantRate,
  "feature_flag_audit_performed_runs" to featureFlagAuditPerformedRuns,
  "feature_flag_audit_performed_rate" to featureFlagAuditPerformedRate,
  "history_read_runs" to historyReadRuns,
  "history_read_rate" to historyReadRate,
  "history_relevant_runs" to historyRelevantRuns,
  "history_relevant_rate" to historyRelevantRate,
  "history_helpful_runs" to historyHelpfulRuns,
  "history_helpful_rate" to historyHelpfulRate,
  "history_relevance_counts" to historyRelevanceCounts,
  "history_helpfulness_counts" to historyHelpfulnessCounts,
  "runs_with_gaps_found" to runsWithGapsFound,
  "average_acceptance_criteria_count" to averageAcceptanceCriteriaCount,
  "average_review_iterations" to averageReviewIterations,
  "average_duration_seconds" to averageDurationSeconds,
)
