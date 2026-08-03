package skillbill.review.plan

import skillbill.review.plan.model.DelegatedReviewCapacityPlan
import skillbill.review.plan.model.DelegatedReviewCapacityRequest
import skillbill.review.plan.model.DelegatedReviewLifecycleState
import skillbill.review.plan.model.DelegatedReviewWave

object DelegatedReviewCapacityPlanner {
  fun plan(request: DelegatedReviewCapacityRequest): DelegatedReviewCapacityPlan {
    val workerSlots = request.totalProcessSlots - request.coordinatorSlots
    val waves = request.selectedWorkerIds.chunked(workerSlots).mapIndexed { index, workerIds ->
      DelegatedReviewWave(index + 1, workerIds)
    }
    return DelegatedReviewCapacityPlan(
      selectedWorkerIds = request.selectedWorkerIds,
      coordinatorSlots = request.coordinatorSlots,
      workerSlots = workerSlots,
      predictedWaves = waves,
    )
  }
}

object DelegatedReviewLifecycleTransitionRules {
  private val allowed: Map<DelegatedReviewLifecycleState?, Set<DelegatedReviewLifecycleState>> = mapOf(
    null to setOf(DelegatedReviewLifecycleState.SELECTED),
    DelegatedReviewLifecycleState.SELECTED to setOf(
      DelegatedReviewLifecycleState.QUEUED,
      DelegatedReviewLifecycleState.CANCELLED,
    ),
    DelegatedReviewLifecycleState.QUEUED to setOf(
      DelegatedReviewLifecycleState.LAUNCHED,
      DelegatedReviewLifecycleState.TIMED_OUT,
      DelegatedReviewLifecycleState.CANCELLED,
    ),
    DelegatedReviewLifecycleState.LAUNCHED to setOf(
      DelegatedReviewLifecycleState.RUNNING,
      DelegatedReviewLifecycleState.FAILED,
      DelegatedReviewLifecycleState.TIMED_OUT,
      DelegatedReviewLifecycleState.CANCELLED,
    ),
    DelegatedReviewLifecycleState.RUNNING to setOf(
      DelegatedReviewLifecycleState.COMPLETED,
      DelegatedReviewLifecycleState.FAILED,
      DelegatedReviewLifecycleState.TIMED_OUT,
      DelegatedReviewLifecycleState.CANCELLED,
    ),
    DelegatedReviewLifecycleState.COMPLETED to setOf(DelegatedReviewLifecycleState.AGGREGATED),
    DelegatedReviewLifecycleState.FAILED to emptySet(),
    DelegatedReviewLifecycleState.TIMED_OUT to emptySet(),
    DelegatedReviewLifecycleState.CANCELLED to emptySet(),
    DelegatedReviewLifecycleState.AGGREGATED to emptySet(),
  )

  fun requireTransition(current: DelegatedReviewLifecycleState?, next: DelegatedReviewLifecycleState) {
    require(next in allowed.getValue(current)) {
      "Illegal delegated-review lifecycle transition ${current ?: "<none>"} -> $next."
    }
  }

  fun transition(
    current: DelegatedReviewLifecycleState?,
    next: DelegatedReviewLifecycleState,
  ): DelegatedReviewLifecycleState {
    requireTransition(current, next)
    return next
  }
}
