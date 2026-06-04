package skillbill.application

import skillbill.application.model.FeatureImplementStatsResult
import skillbill.application.model.FeatureTaskRuntimeStatsResult
import skillbill.application.model.FeatureVerifyStatsResult
import skillbill.application.model.ReviewStatsResult
import skillbill.contracts.JsonPayloadContract
import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.ReviewFindingDetail
import skillbill.review.model.ReviewFindingStats

fun ReviewStatsResult.toReviewStatsPayload(): JsonPayloadContract = MapPayloadContract(
  LinkedHashMap(stats.toPayload()).apply {
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

private fun ReviewFindingDetail.toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "finding_id" to findingId,
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
