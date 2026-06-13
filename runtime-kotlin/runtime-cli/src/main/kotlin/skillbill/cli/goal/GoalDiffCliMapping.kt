package skillbill.cli.goal

import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks

internal fun GoalObservabilityDiffStat.toGoalDiffStatCliMap(): Map<String, Any?> = linkedMapOf(
  "files_changed" to filesChanged,
  "insertions" to insertions,
  "deletions" to deletions,
)

internal fun GoalObservabilitySelectedDiffHunks.toGoalSelectedDiffHunksCliMap(): Map<String, Any?> = linkedMapOf(
  "truncated" to truncated,
  "hunks" to hunks.map(GoalObservabilitySelectedDiffHunk::toGoalSelectedDiffHunkCliMap),
)

private fun GoalObservabilitySelectedDiffHunk.toGoalSelectedDiffHunkCliMap(): Map<String, Any?> = linkedMapOf(
  "path" to path,
  "staged" to staged,
  "header" to header,
  "truncated" to truncated,
  "lines" to lines,
)
