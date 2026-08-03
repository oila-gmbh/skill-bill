package skillbill.review.plan.model

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
    require(
      actualWaves.isEmpty() ||
        actualWaves.flatMap(DelegatedReviewWave::workerIds).toSet() == selectedWorkerIds.toSet(),
    )
    require(actualWaves.isEmpty() || actualWaves.flatMap(DelegatedReviewWave::workerIds).size == selectedWorkerIds.size)
  }

  val predictedWaveCount: Int get() = predictedWaves.size
  val actualWaveCount: Int get() = actualWaves.size

  fun recordActualWaves(waves: List<DelegatedReviewWave>): DelegatedReviewCapacityPlan {
    require(waves.map(DelegatedReviewWave::number) == waves.indices.map { it + 1 })
    require(waves.flatMap(DelegatedReviewWave::workerIds).toSet() == selectedWorkerIds.toSet())
    require(waves.flatMap(DelegatedReviewWave::workerIds).size == selectedWorkerIds.size)
    return copy(actualWaves = waves)
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

  companion object {
    val DEFAULT = DelegatedReviewDeadlinePolicy(
      startupMs = 30_000L,
      progressIdleMs = 120_000L,
      perWorkerMs = 30 * 60 * 1_000L,
      aggregationMs = 30_000L,
      wholeReviewMs = 30 * 60 * 1_000L,
    )
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
