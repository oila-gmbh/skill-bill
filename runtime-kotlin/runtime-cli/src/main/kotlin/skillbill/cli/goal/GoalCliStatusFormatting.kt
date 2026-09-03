package skillbill.cli.goal

import skillbill.application.goalrunner.model.GoalRunnerStatusRequest
import skillbill.cli.kernel.detectInvokingAgentId
import skillbill.cli.model.CliRunInputs
import skillbill.cli.model.canonicalRepositoryRoot
import skillbill.error.DatabaseAccessError
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import java.nio.file.Path

internal data class GoalStatusCliRequestOptions(
  val issueKey: String,
  val monitorOnly: Boolean = false,
  val agent: String?,
  val agentOverride: String?,
  val repoRoot: String?,
  val diff: GoalStatusCliDiffOptions = GoalStatusCliDiffOptions(),
)

internal data class GoalStatusCliDiffOptions(
  val includeDiffStat: Boolean = false,
  val selectedDiffHunkPaths: List<String> = emptyList(),
  val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
)

internal fun CliRunInputs.goalStatusRequest(options: GoalStatusCliRequestOptions): GoalRunnerStatusRequest =
  GoalRunnerStatusRequest(
    issueKey = options.issueKey,
    invokedAgentId = detectInvokingAgentId(options.agent, environment),
    configuredAgentOverrideId = options.agentOverride,
    dbPathOverride = dbPathOverride,
    repoRoot = options.repoRoot?.let(Path::of)
      ?.let { root -> if (options.monitorOnly) canonicalRepositoryRoot(root) else root.toAbsolutePath().normalize() }
      ?: repositoryRoot,
    includeDiffStat = options.diff.includeDiffStat,
    selectedDiffHunkPaths = options.diff.selectedDiffHunkPaths,
    selectedDiffMaxHunks = options.diff.selectedDiffMaxHunks,
    selectedDiffMaxLines = options.diff.selectedDiffMaxLines,
    selectedDiffMaxBytes = options.diff.selectedDiffMaxBytes,
  )

internal fun GoalRunnerStatusProjection?.toGoalStatusCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf<String, Any?>(
    "status" to "ok",
    "issue_key" to it.issueKey,
    "complete_count" to it.completeCount,
    "pending_count" to it.pendingCount,
    "blocked_count" to it.blockedCount,
    "current_subtask" to it.currentSubtaskId,
    "current_step" to it.currentStep,
    "active_agent" to it.activeAgent,
    "execution_liveness" to it.executionLiveness.wireValue,
    "latest_liveness_signal" to it.latestLivenessSignal,
    "paused" to it.paused,
    "pause_requested" to it.pauseRequested,
    "pause_reason" to it.pauseReason,
    "stop_after_subtask" to it.stopAfterSubtaskId,
  ).apply {
    it.planning?.let { planning ->
      put(
        "planning",
        linkedMapOf(
          "state" to planning.state.wireValue,
          "shared_preplan_prepared" to planning.sharedPreplanPrepared,
          "planned_subtask_count" to planning.plannedSubtaskCount,
          "total_subtask_count" to planning.totalSubtaskCount,
          "current_planning_subtask" to planning.currentPlanningSubtaskId,
          "planning_wave_subtasks" to planning.planningWaveSubtaskIds,
          "reason" to planning.reason,
        ),
      )
    }
    it.latestObservabilityEvent?.let { event -> put("latest_observability_event", event) }
    it.requestedDiffStat?.let { stat -> put("diff_stat", stat.toGoalDiffStatCliMap()) }
    it.selectedDiffHunks?.let { hunks -> put("selected_diff_hunks", hunks.toGoalSelectedDiffHunksCliMap()) }
    putGoalLedgerCliEntries(it)
    it.outOfBandAcceptances.toGoalAcceptanceCliList()?.let { list -> put("out_of_band_acceptances", list) }
  }
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "complete_count" to 0,
  "pending_count" to 0,
  "blocked_count" to 0,
  "current_subtask" to null,
  "current_step" to null,
  "active_agent" to null,
  "execution_liveness" to ExecutionLiveness.UNKNOWN.wireValue,
  "latest_liveness_signal" to null,
  "paused" to false,
  "pause_requested" to false,
  "pause_reason" to null,
  "stop_after_subtask" to null,
)

internal fun GoalRunnerStatusProjection?.toBoundedGoalStatusCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf(
    "complete_count" to it.completeCount,
    "pending_count" to it.pendingCount,
    "blocked_count" to it.blockedCount,
    "current_subtask" to it.currentSubtaskId,
    "current_step" to it.currentStep?.let(::singleLineBounded),
    "execution_liveness" to it.executionLiveness.wireValue,
    "resumable_state" to it.monitorResumableState(),
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to singleLineBounded(issueKey),
  "resumable_state" to "not_found",
)

internal fun databaseUnavailableGoalStatusCliMap(issueKey: String, error: DatabaseAccessError): Map<String, Any?> =
  linkedMapOf(
    "status" to GOAL_STATUS_DATABASE_UNAVAILABLE,
    "issue_key" to singleLineBounded(issueKey),
    "resumable_state" to GOAL_STATUS_DATABASE_UNAVAILABLE,
    "reason" to singleLineBounded(error.condition),
  )

internal fun GoalRunnerStatusProjection.monitorResumableState(): String = currentStep.let { step ->
  when {
    paused -> "paused"
    pauseRequested -> "pause_requested"
    currentSubtaskId == null && pendingCount == 0 && blockedCount == 0 -> "complete"
    step.isNullOrBlank() -> "resumable"
    else -> "resumable_at:${singleLineBounded(step)}"
  }
}

internal fun MutableMap<String, Any?>.putGoalLedgerCliEntries(projection: GoalRunnerStatusProjection) {
  if (projection.blockedAttemptCount > 0) put("blocked_attempt_count", projection.blockedAttemptCount)
  if (projection.supervisorKillCount > 0) put("supervisor_kill_count", projection.supervisorKillCount)
  if (projection.phaseAttemptCounts.isNotEmpty()) put("phase_attempt_counts", projection.phaseAttemptCounts)
  if (projection.cumulativeFixIterations.isNotEmpty()) {
    put("cumulative_fix_iterations", projection.cumulativeFixIterations)
  }
  if (projection.reAttemptCauseCounts.isNotEmpty()) put("re_attempt_causes", projection.reAttemptCauseCounts)
  projection.findingsInScope?.let { count -> put("findings_in_scope", count) }
}

internal fun List<GoalRunnerAcceptedSubtask>.toGoalAcceptanceCliList(): List<Map<String, Any?>>? = takeIf {
  it.isNotEmpty()
}?.map { acceptance ->
  linkedMapOf(
    "subtask_id" to acceptance.subtaskId,
    "commit_sha" to acceptance.commitSha,
    "reason" to acceptance.reason,
    "accepted_at" to acceptance.acceptedAt,
  )
}

internal fun goalStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_subtask: ${payload["current_subtask"] ?: "none"}")
  appendLine("current_step: ${payload["current_step"] ?: "none"}")
  appendLine("active_agent: ${payload["active_agent"] ?: "none"}")
  appendLine("execution_liveness: ${payload["execution_liveness"]}")
  appendLine("latest_liveness_signal: ${payload["latest_liveness_signal"] ?: "none"}")
  appendLine("paused: ${payload["paused"]}")
  appendLine("pause_requested: ${payload["pause_requested"]}")
  appendLine("pause_reason: ${payload["pause_reason"] ?: "none"}")
  appendLine("stop_after_subtask: ${payload["stop_after_subtask"] ?: "none"}")
  (payload["planning"] as? Map<*, *>)?.let { planning ->
    appendLine(
      "planning: state=${planning["state"]} shared_preplan=${planning["shared_preplan_prepared"]} " +
        "planned=${planning["planned_subtask_count"]}/${planning["total_subtask_count"]} " +
        "current=${planning["current_planning_subtask"] ?: "none"}" +
        planningWaveText((planning["planning_wave_subtasks"] as? List<*>).orEmpty().size),
    )
    planning["reason"]?.let { appendLine("planning_reason: $it") }
  }
  (payload["latest_observability_event"] as? Map<*, *>)?.let { event ->
    appendLine(
      "latest_observability: phase=${event["workflow_phase"]} role=${event["worker_role"]} " +
        "liveness=${event["liveness_class"]} sequence=${event["sequence_number"]}",
    )
  }
  appendOperatorSurfaceLines(payload)
  appendDiffStatusLines(payload)
}

private fun planningWaveText(waveSize: Int): String = when (waveSize) {
  0 -> ""
  1 -> " wave=1 subtask"
  else -> " wave=$waveSize subtasks"
}

internal fun goalMonitorStatusText(payload: Map<String, Any?>): String = if (payload["status"] == "not_found") {
  buildString {
    appendLine("goal: ${payload["issue_key"]}")
    appendLine("status: not_found")
    appendLine("resumable_state: not_found")
  }
} else if (payload["status"] == GOAL_STATUS_DATABASE_UNAVAILABLE) {
  buildString {
    appendLine("goal: ${payload["issue_key"]}")
    appendLine("status: $GOAL_STATUS_DATABASE_UNAVAILABLE")
    appendLine("resumable_state: $GOAL_STATUS_DATABASE_UNAVAILABLE")
    appendLine("reason: ${payload["reason"]}")
  }
} else {
  buildString {
    appendLine("complete: ${payload["complete_count"]}")
    appendLine("pending: ${payload["pending_count"]}")
    appendLine("blocked: ${payload["blocked_count"]}")
    appendLine("current_subtask: ${payload["current_subtask"] ?: "none"}")
    appendLine("current_step: ${payload["current_step"] ?: "none"}")
    appendLine("execution_liveness: ${payload["execution_liveness"]}")
    appendLine("resumable_state: ${payload["resumable_state"]}")
  }
}
