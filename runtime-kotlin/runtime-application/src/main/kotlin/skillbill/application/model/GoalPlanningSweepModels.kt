package skillbill.application.model

import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface GoalPlanningSweepOutcome {
  data class PreparedAll(
    val identity: GoalPlanningIdentity? = null,
    val provenance: GoalPlanningContractProvenance? = null,
    val descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList(),
  ) : GoalPlanningSweepOutcome {
    fun hydrationFor(subtaskId: Int) = identity?.let { expectedIdentity ->
      val expectedProvenance = requireNotNull(provenance)
      val descriptor = descriptors.singleOrNull { it.subtaskId == subtaskId } ?: return@let null
      skillbill.ports.goalrunner.model.GoalChildPlanningHydrationRequest(
        expectedIdentity,
        expectedProvenance,
        descriptor,
      )
    }
  }

  data class Stopped(
    val issueKey: String,
    val currentSubtaskId: Int,
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val lastResumableStep: String,
  ) : GoalPlanningSweepOutcome
}

internal sealed interface GoalPlanningPhaseProduction {
  data class Captured(
    val payload: String,
    val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  ) : GoalPlanningPhaseProduction

  data class SchemaRejected(
    val reason: String,
  ) : GoalPlanningPhaseProduction

  /**
   * The launch itself succeeded and the provider returned nothing usable. Distinct from
   * [SchemaRejected] because there is no rejected output to remediate: relaunching the identical
   * prompt is the whole fix, and the prior-failure remediation text would describe output that was
   * never produced.
   */
  data class EmptyProviderTurn(
    val reason: String,
    val evidence: GoalPlanningEmptyTurnEvidence,
  ) : GoalPlanningPhaseProduction

  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : GoalPlanningPhaseProduction
}

/**
 * Launch-shaped facts about a zero-exit planning turn that yielded nothing. These are the facts that
 * separate a provider that answered nothing from a harvest that lost the answer, and they are the
 * only evidence the run retains once the undecodable transport is dropped.
 */
data class GoalPlanningEmptyTurnEvidence(
  val agentId: String,
  val durationMs: Long,
  val exitStatus: Int?,
  val inputTokens: Long?,
  val outputTokens: Long?,
  val assistantEventCount: Int?,
  val rawOutputPreview: String?,
) {
  /** Payload-free one-line summary safe to surface to an operator and to store as a reason. */
  fun summary(): String = buildString {
    append("EmptyProviderTurn: agent=")
    append(agentId)
    append(" durationMs=")
    append(durationMs)
    append(" exitStatus=")
    append(exitStatus ?: "none")
    append(" assistantEvents=")
    append(assistantEventCount ?: "unknown")
    append(" inputTokens=")
    append(inputTokens ?: "unknown")
    append(" outputTokens=")
    append(outputTokens ?: "unknown")
  }
}

data class GoalPlanningRejectionRecord(
  val parentWorkflowId: String,
  val issueKey: String,
  val dbPathOverride: String?,
  val phaseId: String,
  val subtaskId: Int,
  val attempt: Int,
  val rule: String,
  val reason: String,
  val agentId: String,
  val rawEvidence: String,
)

/**
 * Fixed pacing and empty-turn backoff for the planning sweep. Injected so tests can drive waits
 * without real elapsed time; production uses these defaults.
 *
 * Defaults and wall-clock arithmetic:
 * - [planLaunchPace] = 20s between consecutive per-subtask plan launches (never before the first
 *   or after the last of a `prepare()` call).
 * - [emptyTurnBackoffBase] = 30s with [emptyTurnBackoffFactor] = 2, so waits before EmptyProviderTurn
 *   attempts 2 and 3 are `base * factor^(attempt-1)` after each failed attempt: 30s then 60s
 *   (90s max empty-turn backoff per phase across both waits).
 * - A 15-subtask goal adds `14 * 20s = 280s` (4m40s) of pace wait on the happy path, which is inside
 *   [DEFAULT_GOAL_PLANNING_BUDGET] (30m) and does not require or breach the default uncapped
 *   `--max-wall-clock-minutes`.
 * - The fix-loop attempt cap (`MAX_FIX_LOOP_ITERATIONS`) stays unchanged.
 *
 * [waitSlice] bounds how long each `RuntimeTimingPort.wait` call may block before the sweep
 * re-checks the durable pause boundary; it is an interruptibility knob, not a rate-control input.
 */
data class GoalPlanningBurstSchedule(
  val planLaunchPace: Duration = DEFAULT_PLAN_LAUNCH_PACE,
  val emptyTurnBackoffBase: Duration = DEFAULT_EMPTY_TURN_BACKOFF_BASE,
  val emptyTurnBackoffFactor: Int = DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
  val waitSlice: Duration = DEFAULT_WAIT_SLICE,
) {
  init {
    require(planLaunchPace.isPositive()) { "planLaunchPace must be positive." }
    require(emptyTurnBackoffBase.isPositive()) { "emptyTurnBackoffBase must be positive." }
    require(emptyTurnBackoffFactor >= 2) { "emptyTurnBackoffFactor must be at least 2." }
    require(waitSlice.isPositive()) { "waitSlice must be positive." }
  }

  /** Backoff to wait after a failed EmptyProviderTurn [failedAttempt] before the next relaunch. */
  fun emptyTurnBackoffAfterAttempt(failedAttempt: Int): Duration {
    require(failedAttempt >= 1) { "failedAttempt must be at least 1." }
    var scale = 1
    repeat(failedAttempt - 1) { scale *= emptyTurnBackoffFactor }
    return emptyTurnBackoffBase * scale
  }

  companion object {
    val DEFAULT_PLAN_LAUNCH_PACE: Duration = 20.seconds
    val DEFAULT_EMPTY_TURN_BACKOFF_BASE: Duration = 30.seconds
    const val DEFAULT_EMPTY_TURN_BACKOFF_FACTOR: Int = 2
    val DEFAULT_WAIT_SLICE: Duration = 1.seconds
  }
}
