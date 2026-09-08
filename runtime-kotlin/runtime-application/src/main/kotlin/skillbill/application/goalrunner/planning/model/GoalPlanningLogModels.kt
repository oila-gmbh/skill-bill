package skillbill.application.goalrunner.planning.model

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

data class GoalPlanningLogRequest(
  val issueKey: String,
  val repoRoot: Path? = null,
  val dbPathOverride: String? = null,
  val subtaskId: Int? = null,
  val failuresOnly: Boolean = false,
)

/**
 * One planning attempt as the durable ledger recorded it. [finishedAt] and [outcome] are absent
 * while an attempt is still in flight, which is the state that distinguishes a working attempt from
 * a wedged one.
 */
data class GoalPlanningLogAttempt(
  val phaseId: String,
  val subtaskId: Int,
  val attempt: Int,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val outcome: String,
  val rule: String? = null,
  val reason: String? = null,
  val agentId: String? = null,
  val rejectedOutputIdentity: String? = null,
  val rejectedOutputBytes: Long? = null,
) {
  /**
   * Measured interval, or null when these two stamps cannot express one.
   *
   * A completion stamped before its own start is not a duration, and returning the negative number
   * let it silently subtract from the planning total. Reporting the interval as absent keeps the
   * total honest while [timestampsInconsistent] carries the anomaly instead of hiding it.
   */
  val durationMs: Long?
    get() = startedAt?.let { start ->
      finishedAt?.let { end ->
        if (end.isBefore(start)) null else Duration.between(start, end).toMillis()
      }
    }

  /** A finish stamped before its own start: the record is unusable for timing and says so. */
  val timestampsInconsistent: Boolean
    get() = startedAt != null && finishedAt != null && finishedAt.isBefore(startedAt)

  val inFlight: Boolean get() = startedAt != null && finishedAt == null
}

data class GoalPlanningLog(
  val issueKey: String,
  val parentWorkflowId: String?,
  val attempts: List<GoalPlanningLogAttempt> = emptyList(),
) {
  val totalAttempts: Int get() = attempts.size
  val failedAttempts: Int get() = attempts.count { it.outcome == "failed" }
  val succeededAttempts: Int get() = attempts.count { it.outcome == "succeeded" }

  val totalPlanningMs: Long get() = attempts.mapNotNull(GoalPlanningLogAttempt::durationMs).sum()

  /**
   * Phases whose first attempt failed. A phase that reliably needs a second attempt doubles planning
   * wall clock, so this is the number that separates provider flake from a systematic prompt or gate
   * mismatch.
   */
  val firstAttemptFailures: Int
    get() = attempts.count { it.attempt == 1 && it.outcome == "failed" }

  val phasesObserved: Int get() = attempts.map { it.phaseId }.distinct().size
}
