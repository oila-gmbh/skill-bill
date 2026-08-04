package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

/**
 * Pure bounded fix-loop policy. Every fix-loop phase (`preplan`, `plan`, `implement`, `implement_fix`,
 * `review`, `audit`, `validate`) re-runs on a failed schema gate; each re-run is a higher iteration so
 * the latest output always wins. `implement` and `implement_fix` participate under the mutating-phase
 * idempotency contract: a mutating phase re-runs/resumes safely because it is given the intended-state
 * inputs plus the current working tree and MUST reconcile the tree to target — treating an
 * already-applied change as a no-op rather than blindly re-applying it — so a re-entry never
 * double-applies a mutation. That contract is what makes bounding them safe; the schema gate verifies
 * the reconciliation is reported rather than assumed. Every fix-loop phase — including `validate` — is
 * bounded by [MAX_FIX_LOOP_ITERATIONS]: validation failures are repair work and earn retries, but a
 * persistently-failing phase (for example a validator subagent that crashes before producing repairable
 * output) must stop rather than loop forever. The first run is iteration 1; the fix-loop index is `iteration - 1`.
 */
object FeatureTaskRuntimeFixLoopPolicy {
  /** A phase runs at most this many times total (1 initial attempt + 2 re-runs) before blocking. */
  const val MAX_FIX_LOOP_ITERATIONS: Int = 3

  /** Malformed serialization gets its own bounded correction budget and does not consume semantic retries. */
  const val MAX_FORMAT_RETRY_ATTEMPTS: Int = 3

  /**
   * Honest incomplete work gets its own bounded budget, independent of both caps above.
   *
   * A receipt that closes three of five plan tasks is not a malformed output and it is not a failed
   * repair: it is partial work that should be continued. Charging it to [MAX_FORMAT_RETRY_ATTEMPTS]
   * would spend the structural-repair budget on something structurally fine; charging it to
   * [MAX_FIX_LOOP_ITERATIONS] would let normal multi-segment delivery exhaust the budget that exists
   * for genuinely failing output. It is still bounded — this is a continuation loop, not an
   * unbounded autonomous one — and exhausting it blocks for a human rather than looping.
   */
  const val MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS: Int = 5

  private val FIX_LOOP_PHASES: Set<String> = setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
  )

  fun participatesInFixLoop(phaseId: String): Boolean = phaseId in FIX_LOOP_PHASES

  /**
   * Returns a block reason when the about-to-run [iteration] (1-based) already exceeds the bounded
   * budget, else null. Used on resume so a fix-loop phase that already burned the cap on a prior
   * run (durable attempt count >= cap) re-blocks immediately instead of relaunching the agent and
   * bypassing the budget across resumes/crashes. Non-fix-loop phases have no retry budget, so a
   * single re-attempt after a crash is allowed (their schema-gate failure blocks via [decideAfterFailure]).
   */
  fun blockReasonIfBudgetExhausted(phaseId: String, iteration: Int): String? =
    if (hasBoundedFixLoop(phaseId) && iteration > MAX_FIX_LOOP_ITERATIONS) {
      blockedReason(phaseId, iteration - 1)
    } else {
      null
    }

  /** Decides what to do after a phase attempt at [currentIteration] (1-based) failed its schema gate. */
  fun decideAfterFailure(phaseId: String, currentIteration: Int): FeatureTaskRuntimeFixLoopDecision {
    require(currentIteration >= 1) { "currentIteration must be >= 1, was $currentIteration." }
    val canRetry = participatesInFixLoop(phaseId) &&
      (!hasBoundedFixLoop(phaseId) || currentIteration < MAX_FIX_LOOP_ITERATIONS)
    return if (canRetry) {
      FeatureTaskRuntimeFixLoopDecision.Retry(
        nextIteration = currentIteration + 1,
        fixLoopIteration = currentIteration,
      )
    } else {
      FeatureTaskRuntimeFixLoopDecision.Block(
        blockedReason = blockedReason(phaseId, currentIteration),
      )
    }
  }

  fun malformedOutputBlockReason(phaseId: String, malformedAttemptCount: Int): String? {
    require(malformedAttemptCount >= 1) {
      "malformedAttemptCount must be >= 1, was $malformedAttemptCount."
    }
    return if (malformedAttemptCount >= MAX_FORMAT_RETRY_ATTEMPTS) {
      "Phase '$phaseId' exhausted the bounded output-format correction budget after " +
        "$malformedAttemptCount malformed attempts (cap=$MAX_FORMAT_RETRY_ATTEMPTS); semantic repair " +
        "attempts were not consumed."
    } else {
      null
    }
  }

  /**
   * Decides whether a semantically incomplete implementation receipt earns another continuation
   * segment. [segmentCount] is the number of incomplete segments already observed (1-based after the
   * first). Retry carries no fix-loop index because a continuation is not a fix-loop iteration; the
   * caller counts segments on its own axis.
   */
  fun incompleteWorkContinuationDecision(phaseId: String, segmentCount: Int): FeatureTaskRuntimeFixLoopDecision {
    require(segmentCount >= 1) { "segmentCount must be >= 1, was $segmentCount." }
    return if (segmentCount < MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS) {
      FeatureTaskRuntimeFixLoopDecision.Retry(
        nextIteration = segmentCount + 1,
        fixLoopIteration = segmentCount,
      )
    } else {
      FeatureTaskRuntimeFixLoopDecision.Block(
        blockedReason = incompleteWorkBlockedReason(phaseId, segmentCount),
      )
    }
  }

  /**
   * Used on resume so a run whose durable continuation-segment count already reached the cap
   * re-blocks immediately instead of relaunching the agent and bypassing the budget across resumes.
   */
  fun incompleteWorkBlockReasonIfBudgetExhausted(phaseId: String, segmentCount: Int): String? =
    if (segmentCount >= MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS) {
      incompleteWorkBlockedReason(phaseId, segmentCount)
    } else {
      null
    }

  fun incompleteWorkBlockedReason(phaseId: String, segmentCount: Int): String =
    "Phase '$phaseId' exhausted the bounded implementation-continuation budget after $segmentCount " +
      "segments (cap=$MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS) with obligations still open; the run " +
      "blocks for an operator decision rather than continuing indefinitely. The malformed-output and " +
      "semantic fix-loop budgets were not consumed."

  private fun blockedReason(phaseId: String, currentIteration: Int): String = if (participatesInFixLoop(phaseId)) {
    "Phase '$phaseId' exhausted the bounded fix loop after $currentIteration attempts " +
      "(cap=$MAX_FIX_LOOP_ITERATIONS); the run blocks rather than advancing on invalid output."
  } else {
    "Phase '$phaseId' produced schema-invalid output and does not participate in a fix loop; " +
      "the run blocks rather than advancing on invalid output."
  }

  private fun hasBoundedFixLoop(phaseId: String): Boolean = participatesInFixLoop(phaseId)
}
