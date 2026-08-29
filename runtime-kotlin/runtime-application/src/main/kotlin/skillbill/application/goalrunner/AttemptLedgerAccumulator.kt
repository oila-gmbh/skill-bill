package skillbill.application.goalrunner

import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class AttemptLedgerAccumulator {
  var blockedAttemptCount = 0
  var supervisorKillCount = 0
  val phaseAttemptCounts = mutableMapOf<String, Int>()
  val cumulativeFixIterations = mutableMapOf<String, Int>()
  val reAttemptCauseCounts = mutableMapOf<String, Int>()
  var findingsInScope: Int? = null

  fun accumulate(entry: Map<*, *>) {
    val action = entry["action"]?.toString() ?: return
    if (entry["stop_reason"] != null) {
      if (isBlockStopReason(entry["stop_reason"]?.toString())) blockedAttemptCount++
      entry["re_attempt_cause"]?.toString()?.takeIf(String::isNotBlank)?.let { cause ->
        reAttemptCauseCounts.merge(cause, 1, Int::plus)
      }
      entry["findings_in_scope"].asGoalRunnerIntOrNull()?.let { findingsInScope = it }
    }
    if (entry["diagnostic_class"]?.toString() == "supervisor_killed_confirmed_alive") supervisorKillCount++
    if (action == "child_activation" || action == "resume") {
      val step = entry["current_step"]?.toString()?.takeIf(String::isNotBlank)
        ?: entry["previous_step"]?.toString()?.takeIf(String::isNotBlank)
        ?: "initial_start"
      phaseAttemptCounts.merge(step, 1, Int::plus)
    }
    if (action == "backward_edge_entry") accumulateBackwardEdge(entry)
  }

  private fun accumulateBackwardEdge(entry: Map<*, *>) {
    val subtaskId = entry["subtask_id"].asGoalRunnerIntOrNull() ?: return
    val loopId = entry["loop_id"]?.toString()?.takeIf(String::isNotBlank) ?: return
    val count = entry["cumulative_loop_count"].asGoalRunnerIntOrNull() ?: return
    cumulativeFixIterations.merge("$subtaskId:$loopId", count, ::maxOf)
  }

  private fun isBlockStopReason(stopReason: String?): Boolean =
    stopReason != null && stopReason.lowercase() in BLOCK_STOP_REASONS

  fun toSummary() = GoalRunnerAttemptLedgerSummary(
    blockedAttemptCount = blockedAttemptCount,
    supervisorKillCount = supervisorKillCount,
    phaseAttemptCounts = phaseAttemptCounts,
    cumulativeFixIterations = cumulativeFixIterations,
    reAttemptCauseCounts = reAttemptCauseCounts,
    findingsInScope = findingsInScope,
  )
}

internal val BLOCK_STOP_REASONS: Set<String> = setOf(
  "failed",
  "blocked",
  "policy_blocked",
  "dependencies_blocked",
  "pull_request_failed",
)

internal fun backwardEdgeCountsFromLedger(artifacts: Map<String, Any?>): Map<String, Int> {
  val entries = (artifacts[GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY] as? List<*>).orEmpty()
  val counts = mutableMapOf<String, Int>()
  entries.forEach { item ->
    val entry = item as? Map<*, *> ?: return@forEach
    if (entry["action"]?.toString() != "backward_edge_entry") return@forEach
    val subtaskId = entry["subtask_id"].asGoalRunnerIntOrNull() ?: return@forEach
    val loopId = entry["loop_id"]?.toString()?.takeIf(String::isNotBlank) ?: return@forEach
    val count = entry["cumulative_loop_count"].asGoalRunnerIntOrNull() ?: return@forEach
    val key = "$subtaskId:$loopId"
    counts.merge(key, count, ::maxOf)
  }
  return counts
}

internal fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
  ?: runCatching {
    LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC)
  }.getOrNull()

internal val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
