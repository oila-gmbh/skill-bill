package skillbill.workflow.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION
import skillbill.contracts.workflow.GOAL_PROGRESS_EVENT_CONTRACT_VERSION

const val GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY: String = "goal_observability_latest_event"
const val GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY: String = "goal_observability_run_history"
const val GOAL_OBSERVABILITY_HISTORY_LIMIT: Int = 50

const val GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY: String = "goal_progress_latest_event"
const val GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY: String = "goal_progress_run_history"
const val GOAL_PROGRESS_HISTORY_LIMIT: Int = 50

/**
 * SKILL-64 Subtask 3 (AC21): declared lifecycle event kinds. phase_* mark
 * workflow phase/step boundaries; operation_* bracket long operations such as
 * `gradlew check` or a test run.
 */
enum class GoalProgressEventKind(val wireValue: String) {
  PHASE_STARTED("phase_started"),
  PHASE_COMPLETED("phase_completed"),
  OPERATION_STARTED("operation_started"),
  OPERATION_HEARTBEAT("operation_heartbeat"),
  OPERATION_COMPLETED("operation_completed"),
  ;

  val isOperationEvent: Boolean
    get() = this == OPERATION_STARTED || this == OPERATION_HEARTBEAT || this == OPERATION_COMPLETED

  companion object {
    fun fromWire(value: String): GoalProgressEventKind = entries.firstOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException("Unknown goal progress event kind '$value'.")
  }
}

/**
 * Declared outcome of a progress event. In-flight events use [NONE];
 * phase_completed/operation_completed carry a terminal outcome.
 */
enum class GoalProgressOutcome(val wireValue: String) {
  NONE("none"),
  SUCCEEDED("succeeded"),
  FAILED("failed"),
  TIMED_OUT("timed_out"),
  CANCELLED("cancelled"),
  ;

  companion object {
    fun fromWire(value: String): GoalProgressOutcome = entries.firstOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException("Unknown goal progress outcome '$value'.")
  }
}

/**
 * SKILL-64 Subtask 3 (AC20-AC25): an effect-free, durable, monotonically
 * sequenced declared progress event. Timestamps are minted in the adapter
 * layer; the domain never reads the clock or generates identifiers.
 */
data class GoalProgressEvent(
  val eventKind: GoalProgressEventKind,
  val workflowId: String,
  val workflowPhase: String,
  val processAlive: Boolean,
  val sequenceNumber: Int,
  val timestamp: String,
  val stepId: String? = null,
  val operationName: String? = null,
  val operationKind: String? = null,
  val expectedLong: Boolean = false,
  val outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  val contractVersion: String = GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
) {
  init {
    require(workflowId.isNotBlank()) { "GoalProgressEvent.workflowId is required." }
    require(workflowPhase.isNotBlank()) { "GoalProgressEvent.workflowPhase is required." }
    require(sequenceNumber >= 0) { "GoalProgressEvent.sequenceNumber must be non-negative." }
    require(timestamp.isNotBlank()) { "GoalProgressEvent.timestamp is required." }
    if (eventKind.isOperationEvent) {
      require(!operationName.isNullOrBlank()) {
        "GoalProgressEvent.operationName is required for operation_* events."
      }
    }
  }

  @OpenBoundaryMap("Goal progress event artifact map at durable workflow-artifact/schema seams")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "event_kind" to eventKind.wireValue,
    "workflow_id" to workflowId,
    "workflow_phase" to workflowPhase,
    "process_alive" to processAlive,
    "sequence_number" to sequenceNumber,
    "timestamp" to timestamp,
  ).apply {
    stepId?.takeIf(String::isNotBlank)?.let { put("step_id", it) }
    operationName?.takeIf(String::isNotBlank)?.let { put("operation_name", it) }
    operationKind?.takeIf(String::isNotBlank)?.let { put("operation_kind", it) }
    if (eventKind.isOperationEvent) {
      put("expected_long", expectedLong)
    }
    if (outcome != GoalProgressOutcome.NONE) {
      put("outcome", outcome.wireValue)
    }
  }
}

data class GoalProgressHistory(
  val events: List<GoalProgressEvent> = emptyList(),
  val retentionLimit: Int = GOAL_PROGRESS_HISTORY_LIMIT,
) {
  fun append(event: GoalProgressEvent): GoalProgressHistory =
    copy(events = (events + event).sortedBy(GoalProgressEvent::sequenceNumber).takeLast(retentionLimit))

  @OpenBoundaryMap("Goal progress history artifact list at durable workflow-artifact/schema seams")
  fun toArtifactList(): List<Map<String, Any?>> = events.map(GoalProgressEvent::toArtifactMap)

  fun latest(): GoalProgressEvent? = events.maxByOrNull(GoalProgressEvent::sequenceNumber)
}
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

data class GoalObservabilitySelectedDiffHunk(
  val path: String,
  val staged: Boolean,
  val header: String,
  val lines: List<String>,
  val truncated: Boolean,
)

data class GoalObservabilitySelectedDiffHunks(
  val hunks: List<GoalObservabilitySelectedDiffHunk> = emptyList(),
  val truncated: Boolean = false,
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
