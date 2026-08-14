package skillbill.application.model

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
  val durationMs: Long?
    get() = startedAt?.let { start ->
      finishedAt?.let { end -> Duration.between(start, end).toMillis() }
    }

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
