package skillbill.ports.review

import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult

interface ParallelReviewLaneRunner {
  fun runTwoLanes(request: ParallelReviewLaneRunRequest): ParallelReviewLaneRunResult

  fun <T> runWave(tasks: List<() -> T>): List<Result<T>> = tasks.map { task -> runCatching(task) }

  fun isInterrupted(): Boolean = false

  fun restoreInterruption() = Unit
}
