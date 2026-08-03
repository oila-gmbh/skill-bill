package skillbill.review.plan

import skillbill.review.plan.model.DelegatedReviewCapacityRequest
import skillbill.review.plan.model.DelegatedReviewDeadlinePolicy
import skillbill.review.plan.model.DelegatedReviewDeadlineScope
import skillbill.review.plan.model.DelegatedReviewLifecycleState
import skillbill.review.plan.model.DelegatedReviewWave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DelegatedReviewCapacityPlannerTest {
  @Test
  fun `capacity reserves coordinator slot and predicts deterministic waves`() {
    val plan = DelegatedReviewCapacityPlanner.plan(
      DelegatedReviewCapacityRequest(
        selectedWorkerIds = listOf("a", "b", "c", "d", "e"),
        totalProcessSlots = 3,
      ),
    )

    assertEquals(1, plan.coordinatorSlots)
    assertEquals(2, plan.workerSlots)
    assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")), plan.predictedWaves.map { it.workerIds })
    assertEquals(3, plan.predictedWaveCount)
  }

  @Test
  fun `actual waves reject dropped or duplicated selected workers`() {
    val plan = DelegatedReviewCapacityPlanner.plan(
      DelegatedReviewCapacityRequest(listOf("a", "b"), totalProcessSlots = 3),
    )
    assertFailsWith<IllegalArgumentException> {
      plan.recordActualWaves(listOf(DelegatedReviewWave(1, listOf("a", "a"))))
    }
    assertFailsWith<IllegalArgumentException> {
      plan.recordActualWaves(listOf(DelegatedReviewWave(1, listOf("a"))))
    }
  }

  @Test
  fun `worker lifecycle transitions are closed and terminal`() {
    var state = DelegatedReviewLifecycleTransitionRules.transition(
      null,
      DelegatedReviewLifecycleState.SELECTED,
    )
    listOf(
      DelegatedReviewLifecycleState.QUEUED,
      DelegatedReviewLifecycleState.LAUNCHED,
      DelegatedReviewLifecycleState.RUNNING,
      DelegatedReviewLifecycleState.COMPLETED,
      DelegatedReviewLifecycleState.AGGREGATED,
    ).forEach { next ->
      state = DelegatedReviewLifecycleTransitionRules.transition(state, next)
    }
    assertEquals(DelegatedReviewLifecycleState.AGGREGATED, state)
    assertFailsWith<IllegalArgumentException> {
      DelegatedReviewLifecycleTransitionRules.requireTransition(
        state,
        DelegatedReviewLifecycleState.RUNNING,
      )
    }
  }

  @Test
  fun `deadline policy distinguishes each bounded scope`() {
    val policy = DelegatedReviewDeadlinePolicy(
      startupMs = 10,
      progressIdleMs = 20,
      perWorkerMs = 30,
      aggregationMs = 40,
      wholeReviewMs = 50,
    )
    assertEquals(30, policy.limitMs(DelegatedReviewDeadlineScope.PER_WORKER))
    assertEquals(false, policy.isExpired(DelegatedReviewDeadlineScope.PER_WORKER, 29))
    assertEquals(true, policy.isExpired(DelegatedReviewDeadlineScope.PER_WORKER, 30))
  }
}
