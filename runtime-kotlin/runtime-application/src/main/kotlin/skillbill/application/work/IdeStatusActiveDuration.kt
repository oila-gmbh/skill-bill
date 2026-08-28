package skillbill.application.work

import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import java.time.Instant

/**
 * Zero accumulated time with no live anchor is not an observation that the goal did no work — it
 * is a goal the runtime never watched execute, including every goal that predates the accumulator.
 * Emitting the zero would have a consumer render "0s" as fact; omitting it lets the consumer fall
 * back to its own clock.
 */
internal fun GoalRunnerStatusProjection.recordedActiveDurationMs(): Long? =
  activeDurationMs.takeIf { it > 0 || liveActiveDurationAnchor() != null }

/**
 * The anchor is a licence to add `now - anchor`, so it is published only while the lease is
 * genuinely live. A killed runner never reaches `releaseExecutionLease`, leaving a stale anchor
 * behind; honouring it would grow an unbounded tail and restore the inflated clock this exists to
 * remove. UNKNOWN is a lease-read failure and is not evidence of a live lease.
 */
internal fun GoalRunnerStatusProjection.liveActiveDurationAnchor(): Instant? =
  parseInstantOrNull(activeDurationAsOf).takeIf { executionLiveness == ExecutionLiveness.LIVE }

internal fun GoalRunnerStatusProjection.recordedSubtaskActiveDurationMs(): Long? =
  subtaskActiveDurationMs.takeIf { it > 0 || liveSubtaskActiveDurationAnchor() != null }

internal fun GoalRunnerStatusProjection.liveSubtaskActiveDurationAnchor(): Instant? =
  parseInstantOrNull(subtaskActiveDurationAsOf).takeIf { executionLiveness == ExecutionLiveness.LIVE }
