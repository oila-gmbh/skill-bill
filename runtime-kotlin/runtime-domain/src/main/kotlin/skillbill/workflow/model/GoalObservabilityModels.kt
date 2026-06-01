package skillbill.workflow.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION

const val GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY: String = "goal_observability_latest_event"
const val GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY: String = "goal_observability_run_history"
const val GOAL_OBSERVABILITY_HISTORY_LIMIT: Int = 50
private const val GOAL_OBSERVABILITY_SAMPLE_PATH_LIMIT: Int = 10

data class GoalObservabilityChangedFileSummary(
  val total: Int,
  val added: Int,
  val modified: Int,
  val deleted: Int,
  val renamed: Int,
  val untracked: Int,
  val samplePaths: List<String> = emptyList(),
)

data class GoalObservabilityDiffStat(
  val filesChanged: Int,
  val insertions: Int,
  val deletions: Int,
)

data class GoalObservabilityFileDiffStat(
  val path: String,
  val insertions: Int,
  val deletions: Int,
)

data class GoalObservabilityEvent(
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val timestamp: String,
  val sequenceNumber: Int,
  val workflowId: String? = null,
  val changedFileSummary: GoalObservabilityChangedFileSummary? = null,
  val diffStat: GoalObservabilityDiffStat? = null,
  val changedFiles: List<String> = emptyList(),
  val diffStatByFile: List<GoalObservabilityFileDiffStat> = emptyList(),
  val contractVersion: String = GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION,
) {
  @OpenBoundaryMap("Goal observability event artifact map at durable workflow-artifact/schema seams")
  fun toArtifactMap(includeHeavyFields: Boolean = false): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "workflow_id" to workflowId,
    "workflow_phase" to workflowPhase,
    "worker_role" to workerRole,
    "liveness_class" to livenessClass,
    "activity_summary" to activitySummary,
    "timestamp" to timestamp,
    "sequence_number" to sequenceNumber,
  ).apply {
    changedFileSummary?.let { summary -> put("changed_file_summary", summary.toArtifactMap()) }
    diffStat?.let { stat -> put("diff_stat", stat.toArtifactMap()) }
    if (includeHeavyFields && changedFiles.isNotEmpty()) {
      put("changed_files", changedFiles)
    }
    if (includeHeavyFields && diffStatByFile.isNotEmpty()) {
      put("diff_stat_by_file", diffStatByFile.map(GoalObservabilityFileDiffStat::toArtifactMap))
    }
  }.filterValues { value -> value != null }

  fun compactLivenessSummary(): String = buildString {
    append("liveness=")
    append(livenessClass)
    append(" phase=")
    append(workflowPhase)
    append(" role=")
    append(workerRole)
    append(" sequence=")
    append(sequenceNumber)
    append(" at=")
    append(timestamp)
    append(" activity=")
    append(activitySummary)
    changedFileSummary?.let { summary ->
      append(" files=")
      append(summary.total)
    }
    diffStat?.let { stat ->
      append(" diff=+")
      append(stat.insertions)
      append("/-")
      append(stat.deletions)
    }
  }

  @OpenBoundaryMap("Compact goal observability summary map rendered by CLI/MCP workflow adapters")
  fun toCompactSummaryMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "workflow_phase" to workflowPhase,
    "worker_role" to workerRole,
    "liveness_class" to livenessClass,
    "activity_summary" to activitySummary,
    "sequence_number" to sequenceNumber,
    "timestamp" to timestamp,
  ).apply {
    changedFileSummary?.let { summary -> put("changed_file_summary", summary.toArtifactMap()) }
    diffStat?.let { stat -> put("diff_stat", stat.toArtifactMap()) }
  }
}

data class GoalObservabilityHistory(
  val events: List<GoalObservabilityEvent> = emptyList(),
  val retentionLimit: Int = GOAL_OBSERVABILITY_HISTORY_LIMIT,
) {
  fun append(event: GoalObservabilityEvent): GoalObservabilityHistory =
    copy(events = (events + event).sortedBy(GoalObservabilityEvent::sequenceNumber).takeLast(retentionLimit))

  @OpenBoundaryMap("Goal observability history artifact list at durable workflow-artifact/schema seams")
  fun toArtifactList(includeHeavyFields: Boolean = false): List<Map<String, Any?>> =
    events.map { event -> event.toArtifactMap(includeHeavyFields) }
}

private fun GoalObservabilityChangedFileSummary.toArtifactMap(): Map<String, Any?> = linkedMapOf(
  "total" to total,
  "added" to added,
  "modified" to modified,
  "deleted" to deleted,
  "renamed" to renamed,
  "untracked" to untracked,
  "sample_paths" to samplePaths.take(GOAL_OBSERVABILITY_SAMPLE_PATH_LIMIT),
)

private fun GoalObservabilityDiffStat.toArtifactMap(): Map<String, Any?> = linkedMapOf(
  "files_changed" to filesChanged,
  "insertions" to insertions,
  "deletions" to deletions,
)

private fun GoalObservabilityFileDiffStat.toArtifactMap(): Map<String, Any?> = linkedMapOf(
  "path" to path,
  "insertions" to insertions,
  "deletions" to deletions,
)
