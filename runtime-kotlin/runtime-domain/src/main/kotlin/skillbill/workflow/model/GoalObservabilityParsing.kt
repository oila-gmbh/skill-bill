package skillbill.workflow.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.invalidGoalObservabilityEvent

@OpenBoundaryMap("Goal observability latest-event durable artifact parse seam")
fun goalObservabilityLatestEventFromArtifacts(
  artifacts: Map<String, Any?>,
  validator: GoalObservabilityEventValidator,
): GoalObservabilityEvent? = artifacts[GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY]
  ?.let { raw -> goalObservabilityEventFromArtifact(raw, GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY, validator) }

@OpenBoundaryMap("Goal observability bounded-history durable artifact parse seam")
fun goalObservabilityHistoryFromArtifacts(
  artifacts: Map<String, Any?>,
  validator: GoalObservabilityEventValidator,
): GoalObservabilityHistory {
  val rawHistory = artifacts[GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY] ?: return GoalObservabilityHistory()
  val rawEvents = rawHistory as? List<*>
    ?: throw invalidGoalObservabilityEvent(
      GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY,
      "",
      "run history must be an array of goal-observability events.",
    )
  return GoalObservabilityHistory(
    events = rawEvents.mapIndexed { index, raw ->
      goalObservabilityEventFromArtifact(raw, "$GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY[$index]", validator)
    },
  )
}

fun goalObservabilityEventFromArtifact(
  raw: Any?,
  sourceLabel: String,
  validator: GoalObservabilityEventValidator,
): GoalObservabilityEvent {
  val eventMap = raw.toGoalObservabilityEventMap(sourceLabel)
  validator.validate(eventMap, sourceLabel)
  eventMap.requireOnlyKeys(GOAL_OBSERVABILITY_EVENT_KEYS, sourceLabel)
  return GoalObservabilityEvent(
    contractVersion = eventMap.requiredContractVersion(sourceLabel),
    issueKey = eventMap.requiredString("issue_key", sourceLabel),
    subtaskId = eventMap.requiredPositiveInt("subtask_id", sourceLabel),
    workflowId = eventMap.optionalString("workflow_id"),
    workflowPhase = eventMap.requiredString("workflow_phase", sourceLabel),
    workerRole = eventMap.requiredString("worker_role", sourceLabel),
    livenessClass = eventMap.requiredString("liveness_class", sourceLabel),
    activitySummary = eventMap.requiredString("activity_summary", sourceLabel),
    timestamp = eventMap.requiredString("timestamp", sourceLabel),
    sequenceNumber = eventMap.requiredNonNegativeInt("sequence_number", sourceLabel),
    changedFileSummary = eventMap.optionalMap("changed_file_summary", sourceLabel)?.toChangedFileSummary(sourceLabel),
    diffStat = eventMap.optionalMap("diff_stat", sourceLabel)?.toDiffStat(sourceLabel),
    changedFiles = eventMap.optionalStringList("changed_files", sourceLabel),
    diffStatByFile = eventMap.optionalList("diff_stat_by_file", sourceLabel).toFileDiffStats(sourceLabel),
  )
}

@Suppress("UNCHECKED_CAST")
private fun Any?.toGoalObservabilityEventMap(sourceLabel: String): Map<String, Any?> = this as? Map<String, Any?>
  ?: (this as? Map<*, *>)?.let { map ->
    map.entries.associate { (key, value) ->
      val stringKey = key as? String
        ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "event keys must be strings.")
      stringKey to value
    }
  }
  ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "event must be a JSON object.")

private fun List<*>?.toFileDiffStats(sourceLabel: String): List<GoalObservabilityFileDiffStat> =
  this?.mapIndexed { index, rawFile ->
    rawFile.asRequiredMap("$sourceLabel.diff_stat_by_file[$index]")
      .toFileDiffStat("$sourceLabel.diff_stat_by_file[$index]")
  }.orEmpty()

private fun Map<*, *>.toChangedFileSummary(sourceLabel: String): GoalObservabilityChangedFileSummary =
  requireOnlyKeys(GOAL_OBSERVABILITY_CHANGED_FILE_SUMMARY_KEYS, "$sourceLabel.changed_file_summary").let {
    GoalObservabilityChangedFileSummary(
      total = requiredProjectedNonNegativeInt("total", sourceLabel),
      added = requiredProjectedNonNegativeInt("added", sourceLabel),
      modified = requiredProjectedNonNegativeInt("modified", sourceLabel),
      deleted = requiredProjectedNonNegativeInt("deleted", sourceLabel),
      renamed = requiredProjectedNonNegativeInt("renamed", sourceLabel),
      untracked = requiredProjectedNonNegativeInt("untracked", sourceLabel),
      samplePaths = optionalProjectedStringList("sample_paths", sourceLabel),
    )
  }

private fun Map<*, *>.toDiffStat(sourceLabel: String): GoalObservabilityDiffStat =
  requireOnlyKeys(GOAL_OBSERVABILITY_DIFF_STAT_KEYS, "$sourceLabel.diff_stat").let {
    GoalObservabilityDiffStat(
      filesChanged = requiredProjectedNonNegativeInt("files_changed", sourceLabel),
      insertions = requiredProjectedNonNegativeInt("insertions", sourceLabel),
      deletions = requiredProjectedNonNegativeInt("deletions", sourceLabel),
    )
  }

private fun Map<*, *>.toFileDiffStat(sourceLabel: String): GoalObservabilityFileDiffStat =
  requireOnlyKeys(GOAL_OBSERVABILITY_FILE_DIFF_STAT_KEYS, sourceLabel).let {
    GoalObservabilityFileDiffStat(
      path = this["path"].asRequiredString(sourceLabel, "path")
        ?: throw invalidGoalObservabilityEvent(
          sourceLabel,
          "path",
          "field is required and must be a non-empty string.",
        ),
      insertions = requiredProjectedNonNegativeInt("insertions", sourceLabel),
      deletions = requiredProjectedNonNegativeInt("deletions", sourceLabel),
    )
  }

private val GOAL_OBSERVABILITY_EVENT_KEYS = setOf(
  "contract_version",
  "issue_key",
  "subtask_id",
  "workflow_id",
  "workflow_phase",
  "worker_role",
  "liveness_class",
  "activity_summary",
  "timestamp",
  "sequence_number",
  "changed_file_summary",
  "diff_stat",
  "changed_files",
  "diff_stat_by_file",
)

private val GOAL_OBSERVABILITY_CHANGED_FILE_SUMMARY_KEYS = setOf(
  "total",
  "added",
  "modified",
  "deleted",
  "renamed",
  "untracked",
  "sample_paths",
)

private val GOAL_OBSERVABILITY_DIFF_STAT_KEYS = setOf("files_changed", "insertions", "deletions")

private val GOAL_OBSERVABILITY_FILE_DIFF_STAT_KEYS = setOf("path", "insertions", "deletions")
