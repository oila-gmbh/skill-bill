package skillbill.review.plan

data class DelegatedReviewCapacityRequest(
  val selectedWorkerIds: List<String>,
  val totalProcessSlots: Int,
  val coordinatorSlots: Int = 1,
) {
  init {
    require(selectedWorkerIds.distinct().size == selectedWorkerIds.size)
    require(selectedWorkerIds.all(String::isNotBlank))
    require(totalProcessSlots > 0)
    require(coordinatorSlots > 0 && coordinatorSlots < totalProcessSlots)
  }
}

data class DelegatedReviewWave(
  val number: Int,
  val workerIds: List<String>,
) {
  init {
    require(number >= 1)
    require(workerIds.isNotEmpty())
    require(workerIds.distinct().size == workerIds.size)
  }
}

data class DelegatedReviewCapacityPlan(
  val selectedWorkerIds: List<String>,
  val coordinatorSlots: Int,
  val workerSlots: Int,
  val predictedWaves: List<DelegatedReviewWave>,
  val actualWaves: List<DelegatedReviewWave> = emptyList(),
) {
  init {
    require(selectedWorkerIds.distinct().size == selectedWorkerIds.size)
    require(coordinatorSlots > 0 && workerSlots > 0)
    require(predictedWaves.map(DelegatedReviewWave::number) == predictedWaves.indices.map { it + 1 })
    require(predictedWaves.flatMap(DelegatedReviewWave::workerIds) == selectedWorkerIds)
    require(actualWaves.isEmpty() || actualWaves.flatMap(DelegatedReviewWave::workerIds).toSet() == selectedWorkerIds.toSet())
    require(actualWaves.isEmpty() || actualWaves.flatMap(DelegatedReviewWave::workerIds).size == selectedWorkerIds.size)
  }

  val predictedWaveCount: Int get() = predictedWaves.size
  val actualWaveCount: Int get() = actualWaves.size

  fun recordActualWaves(waves: List<DelegatedReviewWave>): DelegatedReviewCapacityPlan {
    require(waves.map(DelegatedReviewWave::number) == waves.indices.map { it + 1 })
    require(waves.flatMap(DelegatedReviewWave::workerIds).toSet() == selectedWorkerIds.toSet()) {
      "Actual waves must conserve exactly the selected worker identities."
    }
    require(waves.flatMap(DelegatedReviewWave::workerIds).size == selectedWorkerIds.size) {
      "Actual waves must not duplicate or drop a selected worker."
    }
    return copy(actualWaves = waves)
  }
}

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

enum class DelegatedReviewDeadlineScope {
  STARTUP,
  PROGRESS_IDLE,
  PER_WORKER,
  AGGREGATION,
  WHOLE_REVIEW,
}

data class DelegatedReviewDeadlinePolicy(
  val startupMs: Long,
  val progressIdleMs: Long,
  val perWorkerMs: Long,
  val aggregationMs: Long,
  val wholeReviewMs: Long,
) {
  init {
    require(listOf(startupMs, progressIdleMs, perWorkerMs, aggregationMs, wholeReviewMs).all { it > 0 })
  }

  fun limitMs(scope: DelegatedReviewDeadlineScope): Long = when (scope) {
    DelegatedReviewDeadlineScope.STARTUP -> startupMs
    DelegatedReviewDeadlineScope.PROGRESS_IDLE -> progressIdleMs
    DelegatedReviewDeadlineScope.PER_WORKER -> perWorkerMs
    DelegatedReviewDeadlineScope.AGGREGATION -> aggregationMs
    DelegatedReviewDeadlineScope.WHOLE_REVIEW -> wholeReviewMs
  }

  fun isExpired(scope: DelegatedReviewDeadlineScope, elapsedMs: Long): Boolean {
    require(elapsedMs >= 0)
    return elapsedMs >= limitMs(scope)
  }
}

enum class DelegatedReviewLifecycleState {
  SELECTED,
  QUEUED,
  LAUNCHED,
  RUNNING,
  COMPLETED,
  FAILED,
  TIMED_OUT,
  CANCELLED,
  AGGREGATED,
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

  fun requireTransition(
    current: DelegatedReviewLifecycleState?,
    next: DelegatedReviewLifecycleState,
  ) {
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
